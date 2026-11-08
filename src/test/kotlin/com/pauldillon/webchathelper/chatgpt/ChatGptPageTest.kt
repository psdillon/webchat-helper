package com.pauldillon.webchathelper.chatgpt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGptPageTest {
    @Test
    fun `attachment matches its own name`() {
        assertTrue(ChatGptPage.isAttachmentNamed("src.zip", "src.zip"))
    }

    @Test
    fun `attachment matches the name ChatGPT gives a duplicate`() {
        assertTrue(ChatGptPage.isAttachmentNamed("src(1).zip", "src.zip"))
        assertTrue(ChatGptPage.isAttachmentNamed("src(12).zip", "src.zip"))
        assertTrue(ChatGptPage.isAttachmentNamed("Makefile(2)", "Makefile"))
    }

    @Test
    fun `attachment does not match other names`() {
        assertFalse(ChatGptPage.isAttachmentNamed("src2.zip", "src.zip"))
        assertFalse(ChatGptPage.isAttachmentNamed("src().zip", "src.zip"))
        assertFalse(ChatGptPage.isAttachmentNamed("src.zip", "main.zip"))
        assertFalse(ChatGptPage.isAttachmentNamed("src(1).diff", "src.zip"))
    }
}
