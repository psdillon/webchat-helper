package com.pauldillon.webchathelper.chatgpt

import com.google.gson.Gson

/**
 * Everything that depends on ChatGPT's web page lives here: URLs, selectors and the JavaScript
 * injected into the page, so it can be updated in one place when the page changes (see CLAUDE.md).
 * [ChatGptBridge] runs the scripts; nothing else should know about the page's structure.
 *
 * Page structure as of November 2026:
 * - The prompt form ("composer") is `form[data-chatgpt-composer]`.
 * - The prompt box is a ProseMirror editor (`.ProseMirror[contenteditable]`), not a textarea. Each
 *   line of the prompt is a top-level `<p>`, including lines made with Shift+Enter; an empty line is
 *   an empty `<p>`. So the prompt's text is the paragraphs' text joined with linebreaks.
 * - The form has several hidden file inputs; the general one ("Add files") is the one with
 *   `multiple` and no `accept` filter. The others only take images or video.
 * - Attached files are listed in the form's `[data-composer-attachments]` element (present but hidden
 *   when there are none). Each has a button labelled "Remove <file name>", which assumes ChatGPT's
 *   English UI.
 * - Conversation pages are `/c/<id>`, or `/g/<gpt or project>/c/<id>` for chats in a project or
 *   custom GPT. A new chat has no ID until its first message is sent.
 */
object ChatGptPage {
    /** Page loaded into the tool window's browser (#R4F). */
    const val URL = "https://chatgpt.com/"

    private const val COMPOSER_SELECTOR = "form[data-chatgpt-composer]"
    private const val PROMPT_EDITOR_SELECTOR = "$COMPOSER_SELECTOR .ProseMirror[contenteditable='true']"
    private const val FILE_INPUT_SELECTOR = "$COMPOSER_SELECTOR input[type='file'][multiple]"
    private const val ATTACHMENT_LIST_SELECTOR = "$COMPOSER_SELECTOR [data-composer-attachments]"
    /** Each attached file's "Remove <file name>" button; one per attachment, whatever its type. */
    private const val ATTACHMENT_REMOVE_BUTTON_SELECTOR = "button[aria-label^='Remove ']"

    /**
     * Most files ChatGPT accepts in one message (#UP4). Raised from 10 to 20 on the web in February 2026
     * (ChatGPT release notes, 13 February 2026).
     */
    const val MAX_FILES_PER_MESSAGE = 20

    /** Largest file ChatGPT accepts, in bytes (#UP4); from OpenAI's "File Uploads FAQ". */
    const val MAX_FILE_SIZE = 512L * 1024 * 1024

    /** Names of page parts, used in the error shown when a selector stops matching. */
    const val PART_PROMPT_BOX = "prompt box"
    const val PART_FILE_UPLOAD = "file upload"
    const val PART_ATTACHMENT_LIST = "list of attached files"

    // Declared before the scripts below, which use it while the object initialises.
    private val gson = Gson()

    private val CONVERSATION_URL = Regex("""^https://chatgpt\.com/(?:g/[^/?#]+/)?c/([^/?#]+)""")
    private val CHATGPT_URL = Regex("""^https://chatgpt\.com(?:[/?#]|$)""")

    /** The conversation ID in a ChatGPT page address, or null if the page is not a saved conversation. */
    fun conversationId(url: String): String? = CONVERSATION_URL.find(url)?.groupValues?.get(1)

    fun isChatGptUrl(url: String): Boolean = CHATGPT_URL.containsMatchIn(url)

    /** The address's path, without query or fragment; used to tell new-chat pages apart. */
    fun pagePath(url: String): String = url.substringBefore('#').substringBefore('?')

    /**
     * Installs `window.__webChatHelper.post(type, data)` in the page, which sends an event to the plugin.
     * [sendRequest] is the JavaScript call made with the JSON request in a variable named `request`
     * (from `JBCefJSQuery.inject("request")`).
     */
    internal fun installBridgeScript(sendRequest: String): String = """
        (() => {
          if (window.__webChatHelper) return;
          window.__webChatHelper = {
            post(type, data) {
              const request = JSON.stringify({ type: type, data: data === undefined ? null : data });
              $sendRequest;
            }
          };
          window.__webChatHelper.post('bridgeReady', { url: location.href });
        })();
    """.trimIndent()

    /**
     * Shared page helpers for the scripts below. Scripts return `{ ok: true, value }` on success,
     * `{ missing: <part name> }` when a selector finds nothing, or `{ error: <message> }`.
     */
    private val prelude = """
        const sleep = ms => new Promise(r => setTimeout(r, ms));
        const editor = () => document.querySelector(${js(PROMPT_EDITOR_SELECTOR)});
        const fileInput = () => [...document.querySelectorAll(${js(FILE_INPUT_SELECTOR)})].find(i => !i.accept);
        const promptText = ed => [...ed.children].map(b => b.textContent).join('\n');
    """.trimIndent()

    /** Reads the prompt's text, with a linebreak between lines. */
    internal fun readPromptScript(): String = script(
        """
        const ed = editor();
        if (!ed) return { missing: ${js(PART_PROMPT_BOX)} };
        return { ok: true, value: promptText(ed) };
        """,
    )

    /**
     * Inserts [text] into the prompt at character [offset] of the prompt's text (as read by
     * [readPromptScript]) and returns the new text. Linebreaks in [text] become new lines (paragraphs),
     * the same as Shift+Enter, so nothing is submitted.
     *
     * Uses `document.execCommand`, which ProseMirror treats as typing, so ChatGPT's own state stays in
     * step. Inserted text is not turned into markdown formatting (checked with `_`, `*`, `**` and backticks).
     */
    internal fun insertPromptTextScript(offset: Int, text: String): String = script(
        """
        const ed = editor();
        if (!ed) return { missing: ${js(PART_PROMPT_BOX)} };
        const offset = $offset, text = ${js(text)};
        // Find the line (top-level block) and column for the offset.
        let block = null, column = offset;
        for (const b of ed.children) {
          const length = b.textContent.length;
          if (column <= length) { block = b; break; }
          column -= length + 1;
        }
        if (!block) return { error: 'offset ' + offset + ' is past the end of the prompt' };
        // Find the text node and position within it for the column.
        let node = block, nodeOffset = 0;
        if (column > 0) {
          const walker = document.createTreeWalker(block, NodeFilter.SHOW_TEXT);
          let remaining = column;
          for (let t = walker.nextNode(); t; t = walker.nextNode()) {
            if (remaining <= t.length) { node = t; nodeOffset = remaining; break; }
            remaining -= t.length;
          }
        }
        ed.focus();
        const range = document.createRange();
        range.setStart(node, nodeOffset);
        range.collapse(true);
        const selection = getSelection();
        selection.removeAllRanges();
        selection.addRange(range);
        await sleep(0); // let ProseMirror pick up the new selection
        text.split('\n').forEach((line, i) => {
          if (i > 0) document.execCommand('insertParagraph');
          if (line) document.execCommand('insertText', false, line);
        });
        await sleep(0);
        return { ok: true, value: promptText(ed) };
        """,
    )

    /** Counts the files attached to the prompt. */
    internal fun countAttachmentsScript(): String = script(
        """
        const list = document.querySelector(${js(ATTACHMENT_LIST_SELECTOR)});
        if (!list) return { missing: ${js(PART_ATTACHMENT_LIST)} };
        return { ok: true, value: list.querySelectorAll(${js(ATTACHMENT_REMOVE_BUTTON_SELECTOR)}).length };
        """,
    )

    /**
     * Evaluates to ChatGPT's general file input element, or null. The plugin then sets its files with
     * the DevTools protocol (`DOM.setFileInputFiles`), which behaves like the user choosing the files
     * in the "Add files" dialog.
     */
    internal fun findFileInputScript(): String = "(() => { $prelude\n return fileInput() || null; })()"

    private fun script(body: String) = "(async () => {\n$prelude\n${body.trimIndent()}\n})()"

    /** A JavaScript literal for [value]. */
    private fun js(value: String): String = gson.toJson(value)
}
