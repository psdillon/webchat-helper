package com.pauldillon.webchathelper.attach

import org.junit.Assert.assertEquals
import org.junit.Test

class UnifiedDiffTest {
    private fun diff(before: String?, after: String?, path: String = "src/A.kt") =
        UnifiedDiff.of(path, before?.toByteArray(), after?.toByteArray()).decodeToString()

    private fun numbered(range: IntRange) = range.joinToString("") { "$it\n" }

    @Test
    fun `modified line has three lines of context`() {
        assertEquals(
            """
            --- src/A.kt
            +++ src/A.kt
            @@ -2,7 +2,7 @@
             2
             3
             4
            -5
            +five
             6
             7
             8

            """.trimIndent(),
            diff(numbered(1..9), numbered(1..4) + "five\n" + numbered(6..9)),
        )
    }

    @Test
    fun `changes far apart get separate hunks and close ones share a hunk`() {
        val before = numbered(1..30)
        val after = before.replace("\n2\n", "\ntwo\n").replace("\n9\n", "\nnine\n").replace("\n25\n", "\n")

        assertEquals(
            """
            --- src/A.kt
            +++ src/A.kt
            @@ -1,12 +1,12 @@
             1
            -2
            +two
             3
             4
             5
             6
             7
             8
            -9
            +nine
             10
             11
             12
            @@ -22,7 +22,6 @@
             22
             23
             24
            -25
             26
             27
             28

            """.trimIndent(),
            diff(before, after),
        )
    }

    @Test
    fun `added file compares against dev null`() {
        assertEquals("--- /dev/null\n+++ src/A.kt\n@@ -0,0 +1,2 @@\n+a\n+b\n", diff(null, "a\nb\n"))
    }

    @Test
    fun `deleted file compares against dev null`() {
        assertEquals("--- src/A.kt\n+++ /dev/null\n@@ -1,2 +0,0 @@\n-a\n-b\n", diff("a\nb\n", null))
    }

    @Test
    fun `added empty file has only its header`() {
        assertEquals("--- /dev/null\n+++ src/A.kt\n", diff(null, ""))
    }

    @Test
    fun `missing linebreak at end of file is marked`() {
        assertEquals(
            "--- src/A.kt\n+++ src/A.kt\n@@ -1,1 +1,1 @@\n-a\n+a\n\\ No newline at end of file\n",
            diff("a\n", "a"),
        )
    }

    @Test
    fun `binary files are reported without their contents`() {
        assertEquals("Binary files src/A.png and src/A.png differ\n", diff("a\u0000", "b\u0000", "src/A.png"))
        assertEquals("Binary files /dev/null and src/A.png differ\n", diff(null, "b\u0000", "src/A.png"))
    }

    @Test
    fun `contents in any encoding pass through unchanged, and paths are UTF-8`() {
        val latin1 = byteArrayOf('a'.code.toByte(), 0xE9.toByte(), '\n'.code.toByte())

        val diff = UnifiedDiff.of("src/é.txt", null, latin1)

        val header = "--- /dev/null\n+++ src/é.txt\n@@ -0,0 +1,1 @@\n+".toByteArray()
        assertEquals((header + latin1).toList(), diff.toList())
    }
}
