package com.pauldillon.webchathelper.attach

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * A copy of every file sent to one ChatGPT conversation, as it was when last sent (#0RX), each at its
 * path relative to the project root (#H37).
 */
class Snapshot(private val folder: Path) {

    /** The project-relative paths of the files in the snapshot. */
    fun files(): Set<String> {
        val root = folder.toFile()
        return root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath }.toSet()
    }

    /** Where the snapshot's copy of [relativePath] is, whether or not there is one. */
    fun pathOf(relativePath: String): Path = folder.resolve(relativePath)

    /** Copies [file] in as [relativePath], replacing any earlier copy (#62W). */
    fun store(relativePath: String, file: Path) = FileUtil.copy(file.toFile(), pathOf(relativePath).toFile())

    fun remove(relativePath: String) {
        Files.deleteIfExists(pathOf(relativePath))
    }
}

/**
 * The snapshot of each conversation. Application-level, as snapshots are kept until the IDE exits (#BN4),
 * even if the project is closed before then. Conversations are kept apart per project, as paths in a
 * snapshot are relative to the project root.
 */
@Service(Service.Level.APP)
class ConversationSnapshots {
    private val snapshots = ConcurrentHashMap<String, Snapshot>()

    /** The snapshot of the conversation with ID [conversationId], made empty if there is none yet. */
    fun of(project: Project, conversationId: String): Snapshot =
        snapshots.computeIfAbsent("${project.locationHash}/$conversationId") {
            // In the OS temp location (#5Z5). The platform deletes the folder and its contents on exit (#BN4).
            Snapshot(FileUtil.createTempDirectory("webchathelper-snapshot-", null, true).toPath())
        }

    companion object {
        fun getInstance(): ConversationSnapshots = service()
    }
}
