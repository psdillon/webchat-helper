package com.pauldillon.webchathelper.attach

/**
 * Where attachment messages go in the prompt, so they appear in the order the user attached the
 * files and folders (#25C).
 *
 * The messages injected so far form one block at the start of the prompt. The first message goes at the
 * start (#9N6) and each later one directly after the block (#R1F). If the prompt no longer starts with
 * the block, because the user edited it, sent it or cleared it, the new message goes at the start and
 * begins a new block (#8K9). Each message ends with a blank line, which separates it from the next (#4UZ).
 */
class InjectedMessages {
    private var block = ""

    /** The character offset in [prompt] at which to insert the next message. */
    @Synchronized
    fun insertionOffset(prompt: String): Int {
        if (!prompt.startsWith(block)) block = ""
        return block.length
    }

    /**
     * Records that [message] was inserted at the offset from [insertionOffset], giving [newPrompt].
     * If the prompt doesn't show the expected text, the next message starts a new block.
     */
    @Synchronized
    fun inserted(message: String, newPrompt: String) {
        val expected = block + message
        block = if (newPrompt.startsWith(expected)) expected else ""
    }

    companion object {
        /** Tells ChatGPT what the attachments are (#I2K). */
        const val PROJECT_CONTEXT =
            "These files are from a software project I have open in my IDE. All paths are relative to the project root. " +
                "These files provide a snapshot of a part or all of my project.  When uploading multiple files I may zip them first.  " +
                "If I upload a file for the same path more than once, the most recently uploaded file replaces the previous file(s), " +
                "thereby updating the snapshot.  I may also upload a diff, which also updates the snapshot."

        /** [message] with [PROJECT_CONTEXT] ahead of it (#I2K). */
        fun withProjectContext(message: String): String = "$PROJECT_CONTEXT\n\n$message"
    }
}
