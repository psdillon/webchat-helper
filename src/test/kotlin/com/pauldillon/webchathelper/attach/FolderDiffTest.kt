package com.pauldillon.webchathelper.attach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class FolderDiffTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var project: Path
    private lateinit var snapshot: Snapshot

    @Before
    fun setUp() {
        project = temp.newFolder("project").toPath()
        snapshot = Snapshot(temp.newFolder("snapshot").toPath())
    }

    /** Writes a file in the project and returns it as a folder entry. */
    private fun projectFile(path: String, content: String): FolderZip.Entry {
        val file = project.resolve(path)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
        return FolderZip.Entry(path, file)
    }

    private fun sent(path: String, content: String) {
        val source = temp.newFile().toPath()
        Files.writeString(source, content)
        snapshot.store(path, source)
    }

    private fun changes(folder: String, files: List<FolderZip.Entry>) =
        FolderDiff.changes(folder, files, snapshot, project)

    private fun List<FolderDiff.Change>.described() = map {
        val kind = if (it.sent == null) "added" else if (it.current == null) "deleted" else "modified"
        "$kind ${it.relativePath}"
    }

    @Test
    fun `finds added, modified and deleted files, and leaves out unchanged ones`() {
        val same = projectFile("src/Same.kt", "same")
        val modified = projectFile("src/Modified.kt", "new")
        val added = projectFile("src/Added.kt", "added")
        sent("src/Same.kt", "same")
        sent("src/Modified.kt", "old")
        sent("src/Deleted.kt", "gone")

        assertEquals(
            listOf("added src/Added.kt", "deleted src/Deleted.kt", "modified src/Modified.kt"),
            changes("src", listOf(same, modified, added)).described(),
        )
    }

    @Test
    fun `no changes gives an empty list`() {
        val file = projectFile("src/A.kt", "a")
        sent("src/A.kt", "a")

        assertTrue(changes("src", listOf(file)).isEmpty())
    }

    @Test
    fun `a file that still exists but is now excluded is not a deletion`() {
        val kept = projectFile("src/Kept.kt", "kept")
        projectFile("src/Excluded.kt", "excluded")
        sent("src/Kept.kt", "kept")
        sent("src/Excluded.kt", "excluded")

        assertTrue(changes("src", listOf(kept)).isEmpty())
    }

    @Test
    fun `only snapshot files under the folder are compared`() {
        val file = projectFile("src/A.kt", "a")
        sent("src/A.kt", "a")
        sent("src2/Other.kt", "other")
        sent("Top.kt", "top")

        assertTrue(changes("src", listOf(file)).isEmpty())
    }

    @Test
    fun `the project root compares the whole snapshot`() {
        sent("src/Gone.kt", "gone")

        assertEquals(listOf("deleted src/Gone.kt"), changes("", emptyList()).described())
    }

    @Test
    fun `writes every change as one unified diff`() {
        val modified = projectFile("src/A.kt", "new\n")
        sent("src/A.kt", "old\n")
        sent("src/B.kt", "gone\n")
        val target = temp.root.toPath().resolve(FolderDiff.fileName("src"))

        FolderDiff.write(target, changes("src", listOf(modified)))

        assertEquals(
            "--- src/A.kt\n+++ src/A.kt\n@@ -1,1 +1,1 @@\n-old\n+new\n" +
                "--- src/B.kt\n+++ /dev/null\n@@ -1,1 +0,0 @@\n-gone\n",
            Files.readString(target),
        )
    }

    @Test
    fun `diff is named after the folder`() {
        assertEquals("webchat-helper.diff", FolderDiff.fileName("webchat-helper"))
    }

    @Test
    fun `snapshot stores files at their project-relative paths, replaces them and removes them`() {
        sent("src/main/A.kt", "one")
        sent("src/main/A.kt", "two")
        sent("B.kt", "b")

        assertEquals(setOf("src/main/A.kt", "B.kt"), snapshot.files())
        assertEquals("two", Files.readString(snapshot.pathOf("src/main/A.kt")))

        snapshot.remove("B.kt")

        assertEquals(setOf("src/main/A.kt"), snapshot.files())
        assertFalse(Files.exists(snapshot.pathOf("B.kt")))
    }
}
