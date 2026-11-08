package com.pauldillon.webchathelper.attach

import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.pauldillon.webchathelper.WebChatNotifications
import com.pauldillon.webchathelper.chatgpt.ChatGptBridge
import com.pauldillon.webchathelper.chatgpt.ChatGptPage
import com.pauldillon.webchathelper.chatgpt.ChatGptPageException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Attaches project files and folders to ChatGPT's prompt (#7HS): uploads the files and injects a
 * message for each into the prompt.
 *
 * Attachments are handled one request at a time, in the order they were requested, so their messages
 * appear in the order the user attached them (#25C). Each request is checked as a whole before anything
 * is sent: if any part of it can't be attached, nothing is attached and the user is told why. The
 * exception is empty files, which ChatGPT refuses: they are left out and the rest is attached (#E0Z).
 */
@Service(Service.Level.PROJECT)
class AttachmentService(private val project: Project, private val scope: CoroutineScope) {

    /** Files attached so far, for matching files in ChatGPT's responses (#AD4). */
    val attachedFiles = AttachedFiles()

    private val messages = InjectedMessages()
    private val lock = Mutex()

    /** A file to upload, with its path relative to the project root (#VV0). */
    private class Upload(val file: VirtualFile, val relativePath: String)

    /** Stops an attachment request; [message] is shown to the user. */
    private class AttachmentStopped(message: String) : Exception(message)

    /** Attaches [selected] files and folders through [bridge], in the background. */
    fun attach(bridge: ChatGptBridge, selected: List<VirtualFile>) {
        if (selected.isEmpty()) return
        scope.launch {
            lock.withLock {
                try {
                    attachNow(bridge, selected)
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
        // Only individual files are affected: files in a zip don't appear here, and a zip is never empty.
        val (empty, uploads) = planned.partition { it.file.length == 0L }
        if (empty.isNotEmpty()) {
            val names = empty.joinToString { it.relativePath }
            WebChatNotifications.warning(
                project,
                "ChatGPT doesn't accept empty files, so ${if (empty.size == 1) "this file was" else "these files were"} " +
                    "not attached: $names",
            )
        }
        if (uploads.isEmpty()) return

        checkLimits(bridge, uploads)

        bridge.attachFiles(uploads.map { it.file.toNioPath() })
        uploads.forEach { attachedFiles.recordAttached(it.relativePath) }
        thisLogger().info("Attached ${uploads.joinToString { it.relativePath }}")

        injectMessage(bridge, uploads.joinToString("") { "${it.relativePath} attached\n\n" }) // #C5S
    }

    /** Works out the files to upload for [selected], in the order selected. */
    private fun plan(selected: List<VirtualFile>): List<Upload> {
        val projectFiles = ProjectFiles.of(project)
            ?: throw AttachmentStopped("This project has no root folder, so files can't be attached.")
        return selected.flatMap { file ->
            val path = projectFiles.relativePath(file)
            if (path == null || !file.isInLocalFileSystem) {
                throw AttachmentStopped("${file.presentableUrl} is not in the project, so it can't be attached.")
            }
            if (!file.isDirectory) {
                // An explicitly attached file is attached even if it matches an exclusion (#O8Y).
                listOf(Upload(file, path))
            } else {
                // Files in an attached folder are subject to the exclusions (#AV2).
                val files = projectFiles.filesIn(file)
                val shown = path.ifEmpty { file.name }
                when {
                    files.isEmpty() -> throw AttachmentStopped("$shown has no files to attach after exclusions.")
                    // TODO Phase 8: folders of more than 3 files are attached as a zip (#Q18).
                    files.size > MAX_INDIVIDUAL_FOLDER_FILES -> throw AttachmentStopped(
                        "$shown has ${files.size} files. Attaching folders of more than " +
                            "$MAX_INDIVIDUAL_FOLDER_FILES files (as a zip) is not supported yet.",
                    )
                    else -> files.map { Upload(it, projectFiles.relativePath(it)!!) }
                }
            }
        }
    }

    /** Stops the attachment if ChatGPT would refuse it for its file count or size limits (#UP4). */
    private suspend fun checkLimits(bridge: ChatGptBridge, uploads: List<Upload>) {
        uploads.firstOrNull { it.file.length > ChatGptPage.MAX_FILE_SIZE }?.let {
            throw AttachmentStopped(
                "${it.relativePath} is ${StringUtil.formatFileSize(it.file.length)}, over ChatGPT's limit of " +
                    "${StringUtil.formatFileSize(ChatGptPage.MAX_FILE_SIZE)} per file. Nothing was attached.",
            )
        }
        val existing = bridge.countAttachments()
        if (existing + uploads.size > ChatGptPage.MAX_FILES_PER_MESSAGE) {
            val already = if (existing > 0) ", and the prompt already has $existing" else ""
            throw AttachmentStopped(
                "Can't attach ${uploads.size} file(s): ChatGPT allows ${ChatGptPage.MAX_FILES_PER_MESSAGE} " +
                    "files per message$already. Nothing was attached.",
            )
        }
    }

    /** Inserts [message] into the prompt after the messages injected before it (#25C). */
    private suspend fun injectMessage(bridge: ChatGptBridge, message: String) {
        val offset = messages.insertionOffset(bridge.readPrompt())
        messages.inserted(message, bridge.insertPromptText(offset, message))
    }

    companion object {
        /** A folder with more files than this is attached as a zip (#Q18). */
        const val MAX_INDIVIDUAL_FOLDER_FILES = 3

        fun getInstance(project: Project): AttachmentService = project.getService(AttachmentService::class.java)
    }
}
