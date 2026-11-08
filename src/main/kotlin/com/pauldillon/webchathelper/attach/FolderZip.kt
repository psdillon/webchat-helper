package com.pauldillon.webchathelper.attach

import com.intellij.util.io.Compressor
import java.nio.file.Path

/**
 * Writes the zip that a folder of more than 3 files is attached as (#Q18).
 *
 * Entry paths are relative to the project root (#VV0), so a file `src/main/A.kt` is stored as
 * `src/main/A.kt` whichever folder was attached. Every folder is stored as a folder entry, so empty
 * folders are included (#R58).
 */
object FolderZip {

    /** A file to store in the zip: its path relative to the project root, and where it is on disk. */
    class Entry(val relativePath: String, val file: Path)

    /** The zip's file name for a folder named [folderName], which is the project root's name for the root (#01S). */
    fun fileName(folderName: String): String = "$folderName.zip"

    /**
     * Writes a zip to [target] holding [folders] (project-relative paths; the project root's empty path is
     * skipped) and [files], in the order given.
     */
    fun write(target: Path, folders: List<String>, files: List<Entry>) {
        Compressor.Zip(target).use { zip ->
            folders.filter { it.isNotEmpty() }.forEach { zip.addDirectory(it) }
            files.forEach { zip.addFile(it.relativePath, it.file) }
        }
    }
}
