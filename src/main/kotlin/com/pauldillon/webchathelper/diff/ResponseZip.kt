package com.pauldillon.webchathelper.diff

import com.intellij.util.io.Decompressor
import java.nio.file.Path

/** Unpacks a zip from one of ChatGPT's responses (#0RY). */
object ResponseZip {
    /** Whether a zip entry named [entryName] stays inside the folder it is unpacked into. */
    fun isSafe(entryName: String): Boolean {
        val path = entryName.replace("\\", "/")
        return !path.startsWith("/") && ':' !in path && path.split('/').none { it == ".." }
    }

    /** Unpacks [zip] into [folder], skipping entries that could point outside it. */
    fun extract(zip: Path, folder: Path) {
        Decompressor.Zip(zip).filter { isSafe(it) }.extract(folder)
    }
}
