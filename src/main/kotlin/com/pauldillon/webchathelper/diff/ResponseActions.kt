package com.pauldillon.webchathelper.diff

import com.google.gson.JsonElement
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.ide.diff.DirDiffSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.writeCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.pauldillon.webchathelper.WebChatNotifications
import com.pauldillon.webchathelper.attach.AttachmentService
import com.pauldillon.webchathelper.chatgpt.ChatGptBridge
import com.pauldillon.webchathelper.chatgpt.ChatGptPage
import com.pauldillon.webchathelper.chatgpt.ChatGptPageException
import com.pauldillon.webchathelper.settings.WebChatHelperSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.path.invariantSeparatorsPathString

/**
 * Carries out the Diff and Apply buttons the page shows on the files in ChatGPT's responses (#XIO,
 * [ChatGptPage]). In a diff the project is on the left, and is editable so changes can be applied with the
 * diff window's arrows (#3PS), unless it is a patch's diff and the settings make that read-only.
 */
@Service(Service.Level.PROJECT)
class ResponseActions(private val project: Project, private val scope: CoroutineScope) {

    /** A file from a response. [charset] is known for a code block; a download is in the project file's. */
    private class Received(val bytes: ByteArray, private val charset: Charset? = null) {
        fun text(file: VirtualFile? = null): String = String(bytes, charset ?: file?.charset ?: Charsets.UTF_8)
    }

    /** A project-relative path and the file there, if there is one yet. */
    private class Target(val path: String, val file: VirtualFile?)

    /** Handles the events [bridge]'s page posts, until [parent] is disposed. */
    fun connect(bridge: ChatGptBridge, parent: Disposable) {
        bridge.addEventListener(ChatGptPage.EVENT_BRIDGE_READY, parent) {
            inBackground { bridge.setAttachedFileNames(attachedFiles().names()) }
        }
        bridge.addEventListener(ChatGptPage.EVENT_CODE_ACTION, parent) { data ->
            val content = data.string("content") ?: return@addEventListener
            carryOut(data) { Received(content.toByteArray(), Charsets.UTF_8) }
        }
        bridge.addEventListener(ChatGptPage.EVENT_DOWNLOAD_ACTION, parent) { data ->
            val link = data.string("link") ?: return@addEventListener
            carryOut(data) { Received(bridge.downloadLink(link)) }
        }
        bridge.addEventListener(ChatGptPage.EVENT_RESPONSES_UNRECOGNISED, parent) {
            WebChatNotifications.warning(
                project,
                "Can't recognise the code in ChatGPT's responses, so diff buttons may be missing. " +
                    "ChatGPT's page has probably changed and WebChatHelper needs updating.",
            )
        }
    }

    private fun attachedFiles() = AttachmentService.getInstance(project).attachedFiles

    private fun JsonElement.string(key: String): String? =
        takeIf { it.isJsonObject }?.asJsonObject?.get(key)?.takeIf { it.isJsonPrimitive }?.asString

    /** Runs [action] in the background, telling the user if it fails. */
    private fun inBackground(action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
            } catch (e: ChatGptPageException) {
                WebChatNotifications.error(project, e.message!!)
            } catch (e: ResponsePatchException) {
                WebChatNotifications.warning(project, e.message!!)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                thisLogger().warn("Couldn't use ChatGPT's file", e)
                WebChatNotifications.warning(project, "Couldn't use ChatGPT's file (${e.message}).")
            }
        }
    }

    private fun carryOut(event: JsonElement, read: suspend () -> Received) {
        val action = event.string("action") ?: return
        val kind = event.string("kind") ?: return
        val name = event.string("name") ?: return
        val apply = action == ChatGptPage.ACTION_APPLY
        inBackground {
            val root = project.guessProjectDir()
            if (root == null) {
                WebChatNotifications.warning(project, "This project has no root folder, so $name can't be compared with it.")
                return@inBackground
            }
            when (kind) {
                ChatGptPage.KIND_PATCH -> if (apply) applyPatch(root, read()) else diffPatch(root, read())
                ChatGptPage.KIND_ZIP -> if (apply) applyZip(root, read()) else diffZip(root, name, read())
                else -> {
                    val target = target(root, name) ?: return@inBackground
                    if (apply) applyFile(root, target, read()) else diffFile(target, read())
                }
            }
        }
    }

    /** The project file a response's [name] refers to (#AD4, #Z98), or null, having told the user, if it can't be used. */
    private suspend fun target(root: VirtualFile, name: String): Target? {
        val path = if ('/' in name) name else attachedFiles().latestWithName(name) ?: name
        val file = if (ResponseZip.isSafe(path)) readAction { root.findFileByRelativePath(path) } else null
        if (!ResponseZip.isSafe(path) || file?.isDirectory == true) {
            WebChatNotifications.warning(project, "$path isn't a file in the project, so ChatGPT's file can't be used with it.")
            return null
        }
        return Target(path, file)
    }

    /** Diffs a file or snippet against the whole project file (#YH8). */
    private suspend fun diffFile(target: Target, received: Received) {
        val factory = DiffContentFactory.getInstance()
        val projectFile = target.file?.let { factory.create(project, it) } ?: factory.createEmpty() // editable (#3PS)
        val chatGpt = target.file?.let { factory.createFromBytes(project, received.bytes, it) }
            ?: factory.create(project, received.text(), FileTypeManager.getInstance().getFileTypeByFileName(target.path))
        show(listOf(SimpleDiffRequest(target.path, projectFile, chatGpt, "Project: ${target.path}", "ChatGPT")))
    }

    private suspend fun applyFile(root: VirtualFile, target: Target, received: Received) {
        changeProject { write(root, target.path, received) }
        WebChatNotifications.info(project, "Applied ChatGPT's file to ${target.path}.")
    }

    /** Shows the whole of each file before and after [received], a patch. */
    private suspend fun diffPatch(root: VirtualFile, received: Received) {
        val requests = patchedFiles(root, received).let { changes -> readAction { changes.map { preview(root, it) } } }
        show(requests)
    }

    private fun preview(root: VirtualFile, change: PatchedFile): SimpleDiffRequest {
        val factory = DiffContentFactory.getInstance()
        val file = root.findFileByRelativePath(change.path)
        val original = file?.let { factory.create(project, it) } ?: factory.createEmpty() // editable (#3PS)
        val after = change.after
        val patched = when {
            after == null -> factory.createEmpty()
            file != null -> factory.create(project, after, file)
            else -> factory.create(project, after, FileTypeManager.getInstance().getFileTypeByFileName(change.path))
        }
        return SimpleDiffRequest("Patch Preview: ${change.path}", original, patched, "Original", "Patched").apply {
            if (WebChatHelperSettings.getInstance().state.patchDiffReadOnly) putUserData(DiffUserDataKeys.FORCE_READ_ONLY, true)
        }
    }

    private suspend fun applyPatch(root: VirtualFile, received: Received) {
        // All worked out before anything changes, so a patch that doesn't fit leaves the project alone.
        val changes = patchedFiles(root, received)
        changeProject {
            for (change in changes) {
                val after = change.after
                if (after == null) {
                    root.findFileByRelativePath(change.path)?.delete(this@ResponseActions)
                } else {
                    write(root, change.path, Received(after.toByteArray(), Charsets.UTF_8))
                }
            }
        }
        WebChatNotifications.info(project, "Applied ChatGPT's patch to ${changes.size} file(s).")
    }

    /** What [received], a patch, does to the project as it is in the editors. */
    private suspend fun patchedFiles(root: VirtualFile, received: Received): List<PatchedFile> {
        val text = received.text()
        return readAction {
            ResponsePatch.apply(text) { path ->
                root.findFileByRelativePath(path)?.takeUnless { it.isDirectory }?.let { FileDocumentManager.getInstance().getDocument(it)?.text }
            }
        }
    }

    private suspend fun diffZip(root: VirtualFile, name: String, received: Received) {
        saveAllDocuments() // to compare with what is in the editors, not an older version on disk

        // Compared with the project root, as paths in the zip are relative to it (#15X).
        // Kept until the IDE exits, as the diff window reads the folder for as long as it is open.
        val unpacked = withContext(Dispatchers.IO) { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(unpack(received)) }
            ?: throw IOException("the unpacked zip can't be found")
        val factory = DiffContentFactory.getInstance()
        val request = SimpleDiffRequest(name, factory.create(project, root), factory.create(project, unpacked), "Project", "ChatGPT: $name")
        // The left side is the source: files only in the project are hidden, but new files in the zip stay (#F3R).
        show(listOf(request), DirDiffSettings().apply { showNewOnSource = false })
    }

    private suspend fun applyZip(root: VirtualFile, received: Received) {
        val folder = withContext(Dispatchers.IO) { unpack(received) }
        try {
            val entries = withContext(Dispatchers.IO) {
                Files.walk(folder).use { paths -> paths.filter(Files::isRegularFile).toList() }
                    .map { folder.relativize(it).invariantSeparatorsPathString to Received(Files.readAllBytes(it)) }
            }
            saveAllDocuments()
            changeProject { entries.forEach { (path, entry) -> write(root, path, entry) } }
            WebChatNotifications.info(project, "Copied ${entries.size} file(s) from ChatGPT's zip into the project.")
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { FileUtil.delete(folder.toFile()) }
        }
    }

    /** Unpacks [received], a zip, into a new folder that is deleted when the IDE exits. */
    private fun unpack(received: Received): Path {
        val folder = FileUtil.createTempDirectory("webchathelper-zip-", null, true).toPath()
        val zip = FileUtil.createTempFile("webchathelper-", ".zip", true).toPath()
        try {
            Files.write(zip, received.bytes)
            ResponseZip.extract(zip, folder)
        } finally {
            FileUtil.delete(zip.toFile())
        }
        return folder
    }

    private suspend fun saveAllDocuments() {
        withContext(Dispatchers.EDT + ModalityState.nonModal().asContextElement()) {
            FileDocumentManager.getInstance().saveAllDocuments()
        }
    }

    private suspend fun changeProject(change: () -> Unit) {
        writeCommandAction(project, "Apply ChatGPT's Changes") { change() }
    }

    /** Sets the file at [path] to [received]. Must be called in a write action. */
    private fun write(root: VirtualFile, path: String, received: Received) {
        val existing = root.findFileByRelativePath(path)
        val document = existing?.let { FileDocumentManager.getInstance().getDocument(it) }
        when {
            document != null -> setText(document, received.text(existing))
            existing != null -> existing.setBinaryContent(received.bytes)
            else -> create(root, path).setBinaryContent(received.bytes)
        }
    }

    /** Through the document, so the file keeps its line separators. */
    private fun setText(document: Document, text: String) {
        document.setText(StringUtil.convertLineSeparators(text))
        FileDocumentManager.getInstance().saveDocument(document)
    }

    private fun create(root: VirtualFile, path: String): VirtualFile {
        val folder = if ('/' in path) VfsUtil.createDirectoryIfMissing(root, path.substringBeforeLast('/')) else root
        return (folder ?: throw IOException("can't create the folder for $path")).createChildData(this, path.substringAfterLast('/'))
    }

    private suspend fun show(requests: List<SimpleDiffRequest>, folderDiffSettings: DirDiffSettings? = null) {
        withContext(Dispatchers.EDT + ModalityState.nonModal().asContextElement()) {
            val chain = SimpleDiffRequestChain(requests)
            folderDiffSettings?.let { chain.putUserData(DirDiffSettings.KEY, it) }
            DiffManager.getInstance().showDiff(project, chain, DiffDialogHints.DEFAULT)
        }
    }

    companion object {
        fun getInstance(project: Project): ResponseActions = project.getService(ResponseActions::class.java)
    }
}
