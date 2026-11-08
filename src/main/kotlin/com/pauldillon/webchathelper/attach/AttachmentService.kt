package com.pauldillon.webchathelper.attach

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.pauldillon.webchathelper.WebChatNotifications
import com.pauldillon.webchathelper.chatgpt.ChatGptBridge
import com.pauldillon.webchathelper.chatgpt.ChatGptPage
import com.pauldillon.webchathelper.chatgpt.ChatGptPageException
import com.pauldillon.webchathelper.chatgpt.Conversation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Attaches project files and folders to ChatGPT's prompt (#7HS): uploads the files and injects a
 * message for each into the prompt. A folder of more than 3 files is attached as a zip (#Q18), or as a
 * diff against what the conversation has already been sent if it has been sent any of them (#I81).
 *
 * Attachments are handled one request at a time, in the order they were requested, so their messages
 * appear in the order the user attached them (#25C). Each request is checked as a whole before anything
 * is sent: if any part of it can't be attached, nothing is attached and the user is told why. The
 * exception is empty files, which ChatGPT refuses: they are left out and the rest is attached (#E0Z).
 */
@Service(Service.Level.PROJECT)
class AttachmentService(private val project: Project, private val scope: CoroutineScope) : Disposable {

    /** Files attached so far, for matching files in ChatGPT's responses (#AD4). */
    val attachedFiles = AttachedFiles()

    private val messages = InjectedMessages()
    private val lock = Mutex()
    private val tempUploads = TempUploads(scope)

    /** Part of an attachment request, uploaded as one file. Paths are relative to the project root (#VV0). */
    private sealed interface Part

    /** A file attached as itself. */
    private class FilePart(val file: VirtualFile, val relativePath: String) : Part

    /**
     * A folder attached as a temporary file named [uploadName], covering its [files] as read from the
     * project while planning. [shownPath] is the folder in the prompt message.
     */
    private sealed class FolderPart(val shownPath: String, val uploadName: String, val files: List<FolderZip.Entry>) : Part {
        /** The temporary file, once written. */
        var output: Path? = null
    }

    /** A folder attached as a zip (#Q18, #01S), also holding its subfolders [folders]. */
    private class ZipPart(
        shownPath: String,
        uploadName: String,
        val folders: List<String>,
        files: List<FolderZip.Entry>,
    ) : FolderPart(shownPath, uploadName, files)

    /** A folder at project-relative [folderPath] attached as a diff against [snapshot] (#I81, #94Q). */
    private class DiffPart(
        shownPath: String,
        uploadName: String,
        val folderPath: String,
        files: List<FolderZip.Entry>,
        val snapshot: Snapshot,
        val projectRoot: Path,
    ) : FolderPart(shownPath, uploadName, files) {
        /** What has changed since the folder was last sent, once compared. */
        var changes: List<FolderDiff.Change> = emptyList()
    }

    /** Stops an attachment request; [message] is shown to the user. */
    private class AttachmentStopped(message: String) : Exception(message)

    /**
     * Attaches [selected] files and folders through [bridge], in the background. [beforeAttach] runs first,
     * in turn with other attachment requests, so it can change the prompt without disturbing them.
     */
    fun attach(bridge: ChatGptBridge, selected: List<VirtualFile>, beforeAttach: suspend () -> Unit = {}) {
        if (selected.isEmpty()) return
        submit(bridge) {
            beforeAttach()
            selected
        }
    }

    /** Attaches files and folders dropped onto the tool window (#LI7), given by their paths on disk. */
    fun attachDropped(bridge: ChatGptBridge, paths: List<Path>) {
        if (paths.isEmpty()) return
        submit(bridge) {
            // Refresh, as files dropped from the OS file explorer may be new to the IDE.
            paths.map {
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(it)
                    ?: throw AttachmentStopped("$it can't be found, so nothing was attached.")
            }
        }
    }

    /** Runs an attachment request after the ones before it; [files] gives the files and folders to attach. */
    private fun submit(bridge: ChatGptBridge, files: suspend () -> List<VirtualFile>) {
        scope.launch {
            lock.withLock {
                try {
                    attachNow(bridge, files())
                } catch (e: AttachmentStopped) {
                    WebChatNotifications.warning(project, e.message!!)
                } catch (e: ChatGptPageException) {
                    WebChatNotifications.error(project, e.message!!)
                }
            }
        }
    }

    private suspend fun attachNow(bridge: ChatGptBridge, selected: List<VirtualFile>) {
        // Files are read from disk, so edits still in the editor have to be saved to be sent (#D4X).
        withContext(Dispatchers.EDT + ModalityState.nonModal().asContextElement()) {
            FileDocumentManager.getInstance().saveAllDocuments()
        }

        // A new chat has no ID, so has no snapshot until it gets one (#W3J).
        val conversation = bridge.conversations.current as? Conversation.Existing
        val (snapshot, sent) = withContext(Dispatchers.IO) {
            val snapshot = conversation?.let { ConversationSnapshots.getInstance().of(project, it.id) }
            snapshot to snapshot?.files().orEmpty()
        }
        val planned = readAction { plan(selected, snapshot, sent) }

        // ChatGPT refuses empty files, so they are left out, with no message in the prompt (#E0Z).
        // Only individual files are affected: empty files in a zip are kept, and a zip is never empty.
        val empty = planned.filter { it is FilePart && it.file.length == 0L }
        if (empty.isNotEmpty()) {
            val names = empty.joinToString { (it as FilePart).relativePath }
            WebChatNotifications.warning(
                project,
                "ChatGPT doesn't accept empty files, so ${if (empty.size == 1) "this file was" else "these files were"} " +
                    "not attached: $names",
            )
        }

        val diffs = planned.filterIsInstance<DiffPart>()
        withContext(Dispatchers.IO) { diffs.forEach(::findChanges) }
        // A folder with no changes isn't attached, and the user is told (#25L).
        val unchanged = diffs.filter { it.changes.isEmpty() }
        unchanged.forEach {
            WebChatNotifications.info(project, "No changes to ${it.shownPath} since last attachment, so it was not attached.")
        }

        val parts = planned.filter { it !in empty && it !in unchanged }
        if (parts.isEmpty()) return

        checkLimits(bridge, parts)

        val folderParts = parts.filterIsInstance<FolderPart>()
        try {
            withContext(Dispatchers.IO) { folderParts.forEach(::writeUpload) }
            folderParts.forEach { checkSize(Files.size(it.output!!), it.uploadName) }
            bridge.attachFiles(parts.map { it.uploadPath() })
        } catch (e: Throwable) {
            folderParts.forEach { part -> part.output?.let { tempUploads.delete(it.parent) } }
            throw e
        }
        folderParts.forEach { tempUploads.uploaded(bridge, it.output!!) }

        if (snapshot != null) withContext(Dispatchers.IO) { updateSnapshot(snapshot, parts) }

        // Files attached as part of a folder count as attached for matching ChatGPT's responses (#7P2).
        parts.flatMap { part ->
            when (part) {
                is FilePart -> listOf(part.relativePath)
                is FolderPart -> part.files.map { it.relativePath }
            }
        }.forEach { attachedFiles.recordAttached(it) }
        thisLogger().info("Attached ${parts.joinToString { it.describe() }}")

        val message = parts.joinToString("") { part ->
            when (part) {
                // Paths are in backticks (#FYF).
                is FilePart -> "`${part.relativePath}` attached\n\n" // #C5S
                is ZipPart -> "`${part.shownPath}/` attached\n\n" // #Q18
                is DiffPart -> "`${part.shownPath}/` diff attached (changes since last upload)\n\n" // #I81
            }
        }
        injectMessage(bridge, message, noFilesSent = sent.isEmpty())
    }

    /**
     * Works out how to upload [selected], in the order selected. [sent] is the files in [snapshot], the
     * conversation's snapshot if it has one.
     */
    private fun plan(selected: List<VirtualFile>, snapshot: Snapshot?, sent: Set<String>): List<Part> {
        val projectFiles = ProjectFiles.of(project)
            ?: throw AttachmentStopped("This project has no root folder, so files can't be attached.")
        return selected.flatMap { file ->
            val path = projectFiles.relativePath(file)
            if (path == null || !file.isInLocalFileSystem) {
                throw AttachmentStopped("${file.presentableUrl} is not in the project, so it can't be attached.")
            }
            if (!file.isDirectory) {
                // An explicitly attached file is attached even if it matches an exclusion (#O8Y).
                listOf(FilePart(file, path))
            } else {
                // Files in an attached folder are subject to the exclusions (#AV2); only files are counted (#XI4).
                val contents = projectFiles.contentsOf(file)
                // The project root's path is empty, so it is shown by name, like its zip (#01S).
                val shown = path.ifEmpty { file.name }
                val entries = contents.files.map { FolderZip.Entry(projectFiles.relativePath(it)!!, it.toNioPath()) }
                when {
                    contents.files.isEmpty() -> throw AttachmentStopped("$shown has no files to attach after exclusions.")
                    contents.files.size <= MAX_INDIVIDUAL_FOLDER_FILES -> contents.files.map { FilePart(it, projectFiles.relativePath(it)!!) }
                    // The conversation already has some of these files, so it only needs the changes (#I81).
                    snapshot != null && entries.any { it.relativePath in sent } -> listOf(
                        DiffPart(
                            shownPath = shown,
                            uploadName = FolderDiff.fileName(file.name),
                            folderPath = path,
                            files = entries,
                            snapshot = snapshot,
                            projectRoot = projectFiles.root.toNioPath(),
                        ),
                    )
                    else -> listOf(
                        ZipPart(
                            shownPath = shown,
                            uploadName = FolderZip.fileName(file.name),
                            folders = contents.folders.map { projectFiles.relativePath(it)!! },
                            files = entries,
                        ),
                    )
                }
            }
        }
    }

    private fun findChanges(part: DiffPart) {
        try {
            part.changes = FolderDiff.changes(part.folderPath, part.files, part.snapshot, part.projectRoot)
        } catch (e: IOException) {
            thisLogger().warn("Couldn't compare ${part.shownPath} with its snapshot", e)
            throw AttachmentStopped("Couldn't work out the changes to ${part.shownPath} (${e.message}). Nothing was attached.")
        }
    }

    /** Writes [part]'s zip or diff file in a new temporary folder (#MM4). */
    private fun writeUpload(part: FolderPart) {
        val output = tempUploads.newFolder().resolve(part.uploadName)
        part.output = output
        try {
            when (part) {
                is ZipPart -> FolderZip.write(output, part.folders, part.files)
                is DiffPart -> FolderDiff.write(output, part.changes)
            }
        } catch (e: IOException) {
            thisLogger().warn("Couldn't write $output", e)
            throw AttachmentStopped("Couldn't prepare ${part.shownPath} for attaching (${e.message}). Nothing was attached.")
        }
    }

    /** Records in [snapshot] what was just sent (#62W, #0R9). */
    private fun updateSnapshot(snapshot: Snapshot, parts: List<Part>) {
        try {
            for (part in parts) {
                when (part) {
                    is FilePart -> snapshot.store(part.relativePath, part.file.toNioPath())
                    is ZipPart -> part.files.forEach { snapshot.store(it.relativePath, it.file) }
                    is DiffPart -> {
                        // Deletions first: a deleted file's path may now be a folder holding added files.
                        part.changes.filter { it.current == null }.forEach { snapshot.remove(it.relativePath) }
                        part.changes.forEach { change -> change.current?.let { snapshot.store(change.relativePath, it) } }
                    }
                }
            }
        } catch (e: IOException) {
            // The files have been sent by now, so the attachment carries on.
            thisLogger().warn("Couldn't update the conversation's snapshot", e)
            WebChatNotifications.warning(project, "Couldn't record what was sent (${e.message}), so the next diff of these files may be wrong.")
        }
    }

    private fun Part.uploadPath(): Path = when (this) {
        is FilePart -> file.toNioPath()
        is FolderPart -> output!!
    }

    private fun Part.describe(): String = when (this) {
        is FilePart -> relativePath
        is FolderPart -> "$shownPath as $uploadName"
    }

    /**
     * Stops the attachment if ChatGPT would refuse it for its file count limit or for the size of an
     * individual file (#UP4). Zips and diff files are checked once written.
     */
    private suspend fun checkLimits(bridge: ChatGptBridge, parts: List<Part>) {
        parts.filterIsInstance<FilePart>().forEach { checkSize(it.file.length, it.relativePath) }
        val existing = bridge.countAttachments()
        if (existing + parts.size > ChatGptPage.MAX_FILES_PER_MESSAGE) {
            val already = if (existing > 0) ", and the prompt already has $existing" else ""
            throw AttachmentStopped(
                "Can't attach ${parts.size} file(s): ChatGPT allows ${ChatGptPage.MAX_FILES_PER_MESSAGE} " +
                    "files per message$already. Nothing was attached.",
            )
        }
    }

    private fun checkSize(size: Long, name: String) {
        if (size > ChatGptPage.MAX_FILE_SIZE) {
            throw AttachmentStopped(
                "$name is ${StringUtil.formatFileSize(size)}, over ChatGPT's limit of " +
                    "${StringUtil.formatFileSize(ChatGptPage.MAX_FILE_SIZE)} per file. Nothing was attached.",
            )
        }
    }

    /**
     * Inserts [message] into the prompt after the messages injected before it (#25C), with the project
     * context ahead of it the first time the conversation is sent files (#I2K): when [noFilesSent] and the
     * context isn't on the page already.
     */
    private suspend fun injectMessage(bridge: ChatGptBridge, message: String, noFilesSent: Boolean) {
        val first = noFilesSent && !bridge.pageShowsText(InjectedMessages.PROJECT_CONTEXT)
        val text = if (first) InjectedMessages.withProjectContext(message) else message
        val offset = messages.insertionOffset(bridge.readPrompt())
        messages.inserted(text, bridge.insertPromptText(offset, text))
    }

    /** Deletes the temporary files still waiting for ChatGPT when the project closes (#T10). */
    override fun dispose() = tempUploads.dispose()

    companion object {
        /** A folder with more files than this is attached as a zip (#Q18). */
        const val MAX_INDIVIDUAL_FOLDER_FILES = 3

        fun getInstance(project: Project): AttachmentService = project.getService(AttachmentService::class.java)
    }
}
