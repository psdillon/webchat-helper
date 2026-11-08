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
 *   English UI. A file whose name is already in the draft is shown renamed, e.g. `A.kt` as `A(1).kt`.
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
    /** Start of each attached file's "Remove <file name>" button label. */
    private const val ATTACHMENT_REMOVE_LABEL = "Remove "
    /** Each attached file's "Remove <file name>" button; one per attachment, whatever its type. */
    private const val ATTACHMENT_REMOVE_BUTTON_SELECTOR = "button[aria-label^='$ATTACHMENT_REMOVE_LABEL']"

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

    /**
     * Page event posted when '@' is typed into the prompt box: `{ prompt, offset }`, the prompt's text and
     * the '@''s character offset in it. The plugin decides whether to open the file dialog (#B11).
     */
    const val EVENT_AT_TYPED = "atTyped"

    // Declared before the scripts below, which use it while the object initialises.
    private val gson = Gson()

    private val CONVERSATION_URL = Regex("""^https://chatgpt\.com/(?:g/[^/?#]+/)?c/([^/?#]+)""")
    private val CHATGPT_URL = Regex("""^https://chatgpt\.com(?:[/?#]|$)""")

    /** The conversation ID in a ChatGPT page address, or null if the page is not a saved conversation. */
    fun conversationId(url: String): String? = CONVERSATION_URL.find(url)?.groupValues?.get(1)

    fun isChatGptUrl(url: String): Boolean = CHATGPT_URL.containsMatchIn(url)

    /**
     * Whether [shownName], a name in the prompt's attachment list, is the file uploaded as [fileName].
     * ChatGPT renames a file whose name is already in the draft by adding a number: `A.kt` becomes `A(1).kt`.
     */
    fun isAttachmentNamed(shownName: String, fileName: String): Boolean {
        if (shownName == fileName) return true
        val dot = fileName.lastIndexOf('.').takeIf { it > 0 } ?: fileName.length
        val base = fileName.substring(0, dot)
        val extension = fileName.substring(dot)
        return shownName.startsWith(base) && shownName.endsWith(extension) &&
            shownName.length > fileName.length &&
            RENAMED_SUFFIX.matches(shownName.substring(base.length, shownName.length - extension.length))
    }

    private val RENAMED_SUFFIX = Regex("""\(\d+\)""")

    /** The address's path, without query or fragment; used to tell new-chat pages apart. */
    fun pagePath(url: String): String = url.substringBefore('#').substringBefore('?')

    /**
     * Installs `window.__webChatHelper.post(type, data)` in the page, which sends an event to the plugin.
     * [sendRequest] is the JavaScript call made with the JSON request in a variable named `request`
     * (from `JBCefJSQuery.inject("request")`).
     *
     * Also posts [EVENT_AT_TYPED] when '@' is typed into the prompt box (#B11). Only typing counts
     * (`insertText` of the single character '@'), not pasting or the plugin's own insertions. The listener
     * is on the document, so it keeps working when ChatGPT replaces the prompt box.
     */
    internal fun installBridgeScript(sendRequest: String): String = """
        (() => {
          if (window.__webChatHelper) return;
          $prelude
          window.__webChatHelper = {
            post(type, data) {
              const request = JSON.stringify({ type: type, data: data === undefined ? null : data });
              $sendRequest;
            }
          };
          document.addEventListener('input', e => {
            if (e.inputType !== 'insertText' || e.data !== '@') return;
            const ed = editor();
            if (!ed || !ed.contains(e.target)) return;
            const selection = getSelection();
            if (!selection.rangeCount || !selection.isCollapsed) return;
            const caret = offsetOf(ed, selection.focusNode, selection.focusOffset);
            if (caret < 1) return;
            window.__webChatHelper.post(${js(EVENT_AT_TYPED)}, { prompt: promptText(ed), offset: caret - 1 });
          }, true);
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
        // The DOM position ({ node, offset }) of a character offset in promptText(ed), or null if past the end.
        const positionOf = (ed, offset) => {
          let column = offset;
          for (const b of ed.children) {
            const length = b.textContent.length;
            if (column > length) { column -= length + 1; continue; }
            if (column === 0) return { node: b, offset: 0 };
            const walker = document.createTreeWalker(b, NodeFilter.SHOW_TEXT);
            for (let t = walker.nextNode(); t; t = walker.nextNode()) {
              if (column <= t.length) return { node: t, offset: column };
              column -= t.length;
            }
          }
          return null;
        };
        // The character offset in promptText(ed) of a DOM position, or -1 if it isn't inside a line.
        const offsetOf = (ed, node, nodeOffset) => {
          let offset = 0;
          for (const b of ed.children) {
            if (b.contains(node)) {
              const range = document.createRange();
              range.selectNodeContents(b);
              range.setEnd(node, nodeOffset);
              return offset + range.toString().length;
            }
            offset += b.textContent.length + 1;
          }
          return -1;
        };
        // Selects from start to end (positions from positionOf) in the prompt box.
        const select = async (ed, start, end) => {
          ed.focus();
          const range = document.createRange();
          range.setStart(start.node, start.offset);
          range.setEnd(end.node, end.offset);
          const selection = getSelection();
          selection.removeAllRanges();
          selection.addRange(range);
          await sleep(0); // let ProseMirror pick up the new selection
        };
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
        const at = positionOf(ed, offset);
        if (!at) return { error: 'offset ' + offset + ' is past the end of the prompt' };
        await select(ed, at, at);
        text.split('\n').forEach((line, i) => {
          if (i > 0) document.execCommand('insertParagraph');
          if (line) document.execCommand('insertText', false, line);
        });
        await sleep(0);
        return { ok: true, value: promptText(ed) };
        """,
    )

    /**
     * Deletes [expected] from the prompt at character [offset] of the prompt's text, if that is what is
     * there, and returns whether it was deleted.
     */
    internal fun deletePromptTextScript(offset: Int, expected: String): String = script(
        """
        const ed = editor();
        if (!ed) return { missing: ${js(PART_PROMPT_BOX)} };
        const offset = $offset, expected = ${js(expected)};
        if (promptText(ed).substr(offset, expected.length) !== expected) return { ok: true, value: false };
        const start = positionOf(ed, offset), end = positionOf(ed, offset + expected.length);
        if (!start || !end) return { ok: true, value: false };
        await select(ed, start, end);
        document.execCommand('delete');
        await sleep(0);
        return { ok: true, value: true };
        """,
    )

    /**
     * Whether [text] is shown anywhere on the page, in the prompt or in a sent message, ignoring differences
     * in whitespace. Uses `innerText` as `textContent` includes scripts, which can hold data from other chats.
     */
    internal fun pageShowsTextScript(text: String): String = script(
        """
        const collapse = s => s.replace(/\s+/g, ' ');
        return { ok: true, value: collapse(document.body.innerText).includes(collapse(${js(text)})) };
        """,
    )

    /** Lists the names of the files attached to the prompt, including ones still uploading. */
    internal fun attachmentNamesScript(): String = script(
        """
        const list = document.querySelector(${js(ATTACHMENT_LIST_SELECTOR)});
        if (!list) return { missing: ${js(PART_ATTACHMENT_LIST)} };
        const buttons = [...list.querySelectorAll(${js(ATTACHMENT_REMOVE_BUTTON_SELECTOR)})];
        return { ok: true, value: buttons.map(b => b.getAttribute('aria-label').slice(${ATTACHMENT_REMOVE_LABEL.length})) };
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
