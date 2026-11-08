package com.pauldillon.webchathelper.attach

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.util.zip.ZipFile

class FolderZipTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `stores files at project-relative paths and includes empty folders`() {
        val a = temp.newFile("A.kt").toPath().also { Files.writeString(it, "class A") }
        val empty = temp.newFile("Empty.kt").toPath()
        val zip = temp.root.toPath().resolve(FolderZip.fileName("main"))

        FolderZip.write(
            zip,
            folders = listOf("src/main", "src/main/empty", "src/main/kotlin"),
            files = listOf(FolderZip.Entry("src/main/kotlin/A.kt", a), FolderZip.Entry("src/main/kotlin/Empty.kt", empty)),
        )

        ZipFile(zip.toFile()).use { file ->
            val entries = file.entries().toList()
            assertEquals(
                listOf("src/main/", "src/main/empty/", "src/main/kotlin/", "src/main/kotlin/A.kt", "src/main/kotlin/Empty.kt"),
                entries.map { it.name },
            )
            assertEquals("class A", file.getInputStream(file.getEntry("src/main/kotlin/A.kt")).readAllBytes().decodeToString())
            assertEquals(0L, file.getEntry("src/main/kotlin/Empty.kt").size)
        }
    }

    @Test
    fun `project root has no folder entry of its own`() {
        val a = temp.newFile("A.kt").toPath()
        val zip = temp.root.toPath().resolve(FolderZip.fileName("project"))

        FolderZip.write(zip, folders = listOf("", "src"), files = listOf(FolderZip.Entry("src/A.kt", a)))

        ZipFile(zip.toFile()).use { file ->
            assertEquals(listOf("src/", "src/A.kt"), file.entries().toList().map { it.name })
        }
    }

    @Test
    fun `zip is named after the folder`() {
        assertEquals("webchat-helper.zip", FolderZip.fileName("webchat-helper"))
    }
}
