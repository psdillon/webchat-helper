package com.pauldillon.webchathelper.attach

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.io.FileUtil
import com.pauldillon.webchathelper.chatgpt.ChatGptBridge
import com.pauldillon.webchathelper.chatgpt.ChatGptPage
import com.pauldillon.webchathelper.chatgpt.ChatGptPageException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.file.Path

/**
 * Temporary files made only to be uploaded to ChatGPT: folder zips, and later diff files (#T10).
 *
 * Each file is made in its own folder in the OS's temp location (#MM4), so it can have the name ChatGPT
 * should see. Once uploaded, the file is deleted when it is no longer in the prompt's attachment list
 * (#H4O): the prompt was sent, which ChatGPT only allows once its uploads have finished, or the user
 * removed it. The page doesn't show when an upload finishes in a way the plugin can read, and deleting a
 * file before ChatGPT has read it would break the upload.
 *
 * Anything left is deleted when the project closes, and on IDE exit as a last resort.
 */
class TempUploads(private val scope: CoroutineScope) : Disposable {

    private class Pending(val folder: Path, val fileName: String, val bridge: ChatGptBridge) {
        val since = System.currentTimeMillis()
        /** Whether the file has been seen in the prompt's attachment list. */
        var seen = false
    }

    private val pending = mutableListOf<Pending>()
    private var watcher: Job? = null

    /** Makes a new, empty folder in the OS temp location, deleted on IDE exit if not before. */
    fun newFolder(): Path = FileUtil.createTempDirectory("webchathelper-", null, true).toPath()

    /** Deletes [folder] (from [newFolder]) and its contents now, e.g. when its file was not uploaded. */
    fun delete(folder: Path) {
        if (!FileUtil.delete(folder.toFile()))thisLogger().warn("Couldn't delete temporary folder $folder")
    }

    /** Records that [file], in a folder from [newFolder], was attached through [bridge]; deletes its folder once ChatGPT is done with it. */
    @Synchronized
    fun uploaded(bridge: ChatGptBridge, file: Path) {
        pending += Pending(file.parent, file.fileName.toString(), bridge)
        if (watcher?.isActive != true) watcher = scope.launch { watch() }
    }

    private suspend fun watch() {
        while (true) {
            delay(CHECK_INTERVAL_MS)
            val bridges = synchronized(this) {
                if (pending.isEmpty()) return
                pending.map { it.bridge }.distinct()
            }
            // Pages that can't be read now (e.g. the browser is reloading) are checked again next time.
            val shown = bridges.mapNotNull { bridge ->
                try {
                    bridge to bridge.attachmentNames()
                } catch (_: ChatGptPageException) {
                    null
                }
            }.toMap()
            synchronized(this) {
                val now = System.currentTimeMillis()
                pending.removeAll { file ->
                    val names = shown[file.bridge] ?: return@removeAll false
                    if (names.any { ChatGptPage.isAttachmentNamed(it, file.fileName) }) {
                        file.seen = true
                        false
                    } else if (file.seen || now - file.since > NEVER_SHOWN_TIMEOUT_MS) {
                        // Gone from the prompt, or never shown, which means ChatGPT refused it.
                        delete(file.folder)
                        true
                    } else {
                        false
                    }
                }
            }
        }
    }

    @Synchronized
    override fun dispose() {
        watcher?.cancel()
        pending.forEach { delete(it.folder) }
        pending.clear()
    }

    private companion object {
        const val CHECK_INTERVAL_MS = 2_000L
        /** How long an uploaded file may take to appear in the attachment list before it is taken as refused. */
        const val NEVER_SHOWN_TIMEOUT_MS = 60_000L
    }
}
