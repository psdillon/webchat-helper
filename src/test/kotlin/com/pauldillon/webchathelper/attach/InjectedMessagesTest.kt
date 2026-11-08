package com.pauldillon.webchathelper.attach

import org.junit.Assert.assertEquals
import org.junit.Test

class InjectedMessagesTest {

    private val messages = InjectedMessages()

    /** Inserts [message] into [prompt] as the plugin would, returning the new prompt. */
    private fun inject(prompt: String, message: String): String {
        val offset = messages.insertionOffset(prompt)
        val result = prompt.substring(0, offset) + message + prompt.substring(offset)
        messages.inserted(message, result)
        return result
    }

    @Test
    fun messagesFollowEachOtherInOrder() { // #9N6, #R1F, #4UZ
        var prompt = "explain this"
        prompt = inject(prompt, "a.kt attached\n\n")
        prompt = inject(prompt, "b.kt attached\n\n")
        assertEquals("a.kt attached\n\nb.kt attached\n\nexplain this", prompt)
    }

    @Test
    fun modifiedMessagesSendTheNextToTheStart() { // #8K9
        var prompt = inject("", "a.kt attached\n\n")
        prompt = prompt.replace("a.kt", "A.kt")
        prompt = inject(prompt, "b.kt attached\n\n")
        assertEquals("b.kt attached\n\nA.kt attached\n\n", prompt)
        // The new message starts a new block, which later messages follow.
        prompt = inject(prompt, "c.kt attached\n\n")
        assertEquals("b.kt attached\n\nc.kt attached\n\nA.kt attached\n\n", prompt)
    }

    @Test
    fun sentPromptStartsAgain() {
        inject("", "a.kt attached\n\n")
        assertEquals("b.kt attached\n\n", inject("", "b.kt attached\n\n"))
    }
}
