package com.pauldillon.webchathelper.chatgpt

/**
 * Everything that depends on ChatGPT's web page lives here, so it can be updated in one place
 * when the page changes (see CLAUDE.md). Selectors and injected JavaScript are added in Phase 5.
 */
object ChatGptPage {
    /** Page loaded into the tool window's browser (#R4F). */
    const val URL = "https://chatgpt.com/"
}
