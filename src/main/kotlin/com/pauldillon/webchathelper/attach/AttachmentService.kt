package com.pauldillon.webchathelper.attach

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.pauldillon.webchathelper.WebChatNotifications
import com.pauldillon.webchathelper.chatgpt.ChatGptBridge
import com.pauldillon.webchathelper.chatgpt.ChatGptPage
import com.pauldillon.webchathelper.chatgpt.ChatGptPageException
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
 * message for each into the prompt. A folder of more than 3 files is attached as a zip (#Q18).
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
     * A folder attached as a zip (#Q18) named [zipName] (#01S), holding [folders] and [files] as read from
     * the project while planning. [shownPath] is the folder in the prompt message.
     */
    private class ZipPart(
        val shownPath: String,
        val zipName: String,
        val folders: List<String>,
        val files: List<FolderZip.Entry>,
    ) : Part {
        /** The zip, once written. */
        var zip: Path? = null
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
        val planned = readAction { plan(selected) }

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
        val parts = planned.filter { it !in empty }
        if (parts.isEmpty()) return

        checkLimits(bridge, parts)

        val zips = parts.filterIsInstance<ZipPart>()
        try {
            withContext(Dispatchers.IO) { zips.forEach(::writeZip) }
            zips.forEach { checkSize(Files.size(it.zip!!), it.zipName) }
            bridge.attachFiles(parts.map { it.uploadPath() })
        } catch (e: Throwable) {
            zips.forEach { part -> part.zip?.let { tempUploads.delete(it.parent) } }
            throw e
        }
        zips.forEach { tempUploads.uploaded(bridge, it.zip!!) }

        // Files in a zip count as attached for matching ChatGPT's responses (#7P2).
        parts.flatMap { part ->
            when (part) {
                is FilePart -> listOf(part.relativePath)
                is ZipPart -> part.files.map { it.relativePath }
            }
        }.forEach { attachedFiles.recordAttached(it) }
        thisLogger().info("Attached ${parts.joinToString { it.describe() }}")

        injectMessage(bridge, parts.joinToString("") { part ->
            when (part) {
                is FilePart -> "${part.relativePath} attached\n\n" // #C5S
                is ZipPart -> "${part.shownPath}/ attached\n\n" // #Q18
            }
        })
    }

    /** Works out how to upload [selected], in the order selected. */
    private fun plan(selected: List<VirtualFile>): List<Part> {
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
                when {
                    contents.files.isEmpty() -> throw AttachmentStopped("$shown has no files to attach after exclusions.")
                    contents.files.size > MAX_INDIVIDUAL_FOLDER_FILES -> listOf(
                        ZipPart(
                            shownPath = shown,
                            zipName = FolderZip.fileName(file.name),
                            folders = contents.folders.map { projectFiles.relativePath(it)!! },
                            files = contents.files.map { FolderZip.Entry(projectFiles.relativePath(it)!!, it.toNioPath()) },
                        ),
                    )
                    else -> contents.files.map { FilePart(it, projectFiles.relativePath(it)!!) }
                }
            }
        }
    }

    /** Writes [part]'s zip in a new temporary folder (#MM4). */
    private fun writeZip(part: ZipPart) {
        val zip = tempUploads.newFolder().resolve(part.zipName)
        part.zip = zip
        try {
            FolderZip.write(zip, part.folders, part.files)
        } catch (e: IOException) {
            thisLogger().warn("Couldn't write $zip", e)
            throw AttachmentStopped("Couldn't zip ${part.shownPath} (${e.message}). Nothing was attached.")
        }
    }

    private fun Part.uploadPath(): Path = when (this) {
        is FilePart -> file.toNioPath()
        is ZipPart -> zip!!
    }

    private fun Part.describe(): String = when (this) {
        is FilePart -> relativePath
        is ZipPart -> "$shownPath as $zipName"
    }

    /**
     * Stops the attachment if ChatGPT would refuse it for its file count limit or for the size of an
     * individual file (#UP4). Zips are checked once written.
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

    /** Inserts [message] into the prompt after the messages injected before it (#25C). */
    private suspend fun injectMessage(bridge: ChatGptBridge, message: String) {
        val offset = messages.insertionOffset(bridge.readPrompt())
        messages.inserted(message, bridge.insertPromptText(offset, message))
    }

    /** Deletes the temporary files still waiting for ChatGPT when the project closes (#T10). */
    override fun dispose() = tempUploads.dispose()

    companion object {
        /** A folder with more files than this is attached as a zip (#Q18). */
        const val MAX_INDIVIDUAL_FOLDER_FILES = 3

        fun getInstance(project: Project): AttachmentService = project.getService(AttachmentService::class.java)
    }
}
