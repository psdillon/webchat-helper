package com.pauldillon.webchathelper.diff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ResponsePatchTest {
    private val project = mapOf("src/a.txt" to "one\ntwo\nthree\n", "src/gone.txt" to "bye\n")

    private fun apply(patch: String) = ResponsePatch.apply(patch) { project[it] }

    private val modifyA = """
        --- src/a.txt
        +++ src/a.txt
        @@ -1,3 +1,3 @@
         one
        -two
        +TWO
         three

    """.trimIndent()

    @Test
    fun `gives the whole of the file before and after`() {
        val file = apply(modifyA).single()
        assertEquals("src/a.txt", file.path)
        assertEquals("one\ntwo\nthree\n", file.before)
        assertEquals("one\nTWO\nthree\n", file.after)
    }

    @Test
    fun `removes the prefixes patches usually add`() {
        val patch = modifyA.replace("--- src", "--- a/src").replace("+++ src", "+++ b/src")
        assertEquals("src/a.txt", apply(patch).single().path)
    }

    @Test
    fun `handles every file in the patch, including new and deleted ones`() {
        val patch = modifyA + """
            --- /dev/null
            +++ b/src/new.txt
            @@ -0,0 +1,2 @@
            +hello
            +world
            --- a/src/gone.txt
            +++ /dev/null
            @@ -1 +0,0 @@
            -bye

        """.trimIndent()
        val (modified, created, deleted) = apply(patch)
        assertEquals("src/a.txt", modified.path)
        assertEquals("src/new.txt", created.path)
        assertNull(created.before)
        assertEquals("hello\nworld\n", created.after)
        assertEquals("src/gone.txt", deleted.path)
        assertEquals("bye\n", deleted.before)
        assertNull(deleted.after)
    }

    @Test
    fun `refuses a patch that doesn't fit`() {
        assertThrows(ResponsePatchException::class.java) { apply(modifyA.replace("-two", "-something else")) }
    }

    @Test
    fun `refuses a patch for a file that isn't in the project`() {
        assertThrows(ResponsePatchException::class.java) { apply(modifyA.replace("src/a.txt", "src/other.txt")) }
    }

    @Test
    fun `refuses a patch that reaches outside the project`() {
        assertThrows(ResponsePatchException::class.java) { apply(modifyA.replace("src/a.txt", "../a.txt")) }
    }

    @Test
    fun `refuses text that isn't a patch`() {
        assertThrows(ResponsePatchException::class.java) { apply("just some text\n") }
    }
}
