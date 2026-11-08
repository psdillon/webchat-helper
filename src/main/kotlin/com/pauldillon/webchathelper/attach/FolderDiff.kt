package com.pauldillon.webchathelper.attach

import java.nio.file.Files
import java.nio.file.Path

/** Compares a folder with a conversation's snapshot, and writes the diff attached in place of its zip (#I81). */
object FolderDiff {

    /** A file's change since it was last sent: [sent] is null if it was added, [current] null if it was deleted. */
    class Change(val relativePath: String, val sent: Path?, val current: Path?)

    /** The diff's file name for a folder named [folderName], which is the project root's name for the root (#94Q). */
    fun fileName(folderName: String): String = "$folderName.diff"

    /**
     * What has changed between [files], the folder's current files after exclusions, and the [snapshot]'s
     * files under the same path (#D4X). [folderPath] is the folder's project-relative path, empty for the
     * project root. Sorted by path.
     */
    fun changes(folderPath: String, files: List<FolderZip.Entry>, snapshot: Snapshot, projectRoot: Path): List<Change> {
        val prefix = if (folderPath.isEmpty()) "" else "$folderPath/"
        val sent = snapshot.files().filter { it.startsWith(prefix) }.toSet()

        val addedOrModified = files.mapNotNull { file ->
            val copy = snapshot.pathOf(file.relativePath)
            when {
                file.relativePath !in sent -> Change(file.relativePath, null, file.file) // #YN9
                Files.mismatch(copy, file.file) != -1L -> Change(file.relativePath, copy, file.file)
                else -> null
            }
        }
        // Files that still exist but aren't in files are now excluded, which isn't a deletion (#7EX).
        val deleted = (sent - files.map { it.relativePath }.toSet())
            .filterNot { Files.isRegularFile(projectRoot.resolve(it)) }
            .map { Change(it, snapshot.pathOf(it), null) }

        return (addedOrModified + deleted).sortedBy { it.relativePath }
    }

    /** Writes [changes] to [target] as a unified diff (#0Y9). */
    fun write(target: Path, changes: List<Change>) {
        Files.newOutputStream(target).buffered().use { out ->
            changes.forEach { change ->
                out.write(UnifiedDiff.of(change.relativePath, change.sent?.let(Files::readAllBytes), change.current?.let(Files::readAllBytes)))
            }
        }
    }
}
