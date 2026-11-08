package com.pauldillon.webchathelper.diff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ResponseZipTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun zip(vararg entries: Pair<String, String>): Path {
        val path = temp.newFile("response.zip").toPath()
        ZipOutputStream(Files.newOutputStream(path)).use { out ->
            for ((name, text) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(text.toByteArray())
                out.closeEntry()
            }
        }
        return path
    }

    @Test
    fun `ordinary entries are safe`() {
        assertTrue(ResponseZip.isSafe("src/A.kt"))
        assertTrue(ResponseZip.isSafe("src\\A.kt"))
        assertTrue(ResponseZip.isSafe("src/"))
    }

    @Test
    fun `entries that could point outside the folder are not safe`() {
        assertFalse(ResponseZip.isSafe("../secret.txt"))
        assertFalse(ResponseZip.isSafe("src/../../secret.txt"))
        assertFalse(ResponseZip.isSafe("/etc/passwd"))
        assertFalse(ResponseZip.isSafe("C:/Windows/win.ini"))
        assertFalse(ResponseZip.isSafe("..\\secret.txt"))
    }

    @Test
    fun `unpacks every file at its path`() {
        val zip = zip("src/" to "", "src/A.kt" to "a", "new/extra.txt" to "n", "B.kt" to "b")
        val folder = temp.newFolder("out").toPath()
        ResponseZip.extract(zip, folder)
        assertEquals("a", Files.readString(folder.resolve("src/A.kt")))
        assertEquals("n", Files.readString(folder.resolve("new/extra.txt")))
        assertEquals("b", Files.readString(folder.resolve("B.kt")))
    }

    @Test
    fun `skips entries that could point outside the folder`() {
        val zip = zip("../evil.txt" to "x", "ok.txt" to "o")
        val parent = temp.newFolder("parent").toPath()
        val folder = Files.createDirectory(parent.resolve("out"))
        ResponseZip.extract(zip, folder)
        assertEquals("o", Files.readString(folder.resolve("ok.txt")))
        assertFalse(Files.exists(parent.resolve("evil.txt")))
    }
}
