package com.pauldillon.webchathelper.attach

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AtTriggerTest {
    @Test
    fun `opens at the start of the prompt`() {
        assertTrue(AtTrigger.opensDialog("@", 0))
        assertTrue(AtTrigger.opensDialog("@rest", 0))
    }

    @Test
    fun `opens after whitespace`() {
        assertTrue(AtTrigger.opensDialog("look at @", 8))
        assertTrue(AtTrigger.opensDialog("tab\t@", 4))
        // contenteditable turns a trailing space into a non-breaking space
        assertTrue(AtTrigger.opensDialog("look at @", 8))
    }

    @Test
    fun `opens after a linebreak`() {
        assertTrue(AtTrigger.opensDialog("first line\n@", 11))
        assertTrue(AtTrigger.opensDialog("a.kt attached\n\n@", 15))
    }

    @Test
    fun `does not open inside a word`() {
        assertFalse(AtTrigger.opensDialog("me@example.com", 2))
        assertFalse(AtTrigger.opensDialog("node_modules/@types", 13))
    }

    @Test
    fun `does not open when the offset is not an at sign`() {
        assertFalse(AtTrigger.opensDialog("abc", 1))
        assertFalse(AtTrigger.opensDialog("abc", 5))
        assertFalse(AtTrigger.opensDialog("", 0))
    }
}
