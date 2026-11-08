package com.pauldillon.webchathelper.attach

/** When typing '@' into the prompt opens the file/folder dialog (#B11). */
object AtTrigger {
    /**
     * Whether the '@' at [offset] in [prompt] was typed at the start of the prompt or after whitespace,
     * including a linebreak (lines are separated by `\n` in the prompt's text).
     */
    fun opensDialog(prompt: String, offset: Int): Boolean =
        prompt.getOrNull(offset) == '@' && (offset == 0 || prompt[offset - 1].isWhitespace())
}
