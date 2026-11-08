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
 * - Each ChatGPT response is inside an element whose `data-content-search-unit-key` ends in `:assistant`.
 * - A code block in a response is `[data-code-block='true']`. Its header has a row of buttons,
 *   `[data-code-block-controls='true']`, and its text is in a `code` element.
 * - While ChatGPT is writing a response, an element inside it has a `data-d-streaming-reveal` attribute.
 * - A file to download is shown as a `[data-d-component='pressable'][role='link']` span with no address:
 *   ChatGPT's script handles it when it is clicked. It ignores `element.click()`, so the link has to be
 *   clicked with real mouse input. A zip then downloads, but a text file opens in a side panel,
 *   `[data-app-shell-focus-area='right-panel']`, with buttons labelled "Download" and "Close viewer", which
 *   assumes ChatGPT's English UI. Either way ChatGPT downloads the file by creating a `blob:` address and
 *   clicking a temporary `<a download>`.
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
    private const val RESPONSE_SELECTOR = "[data-content-search-unit-key\$=':assistant']"
    private const val CODE_BLOCK_SELECTOR = "[data-code-block='true']"
    private const val CODE_BLOCK_CONTROLS_SELECTOR = "[data-code-block-controls='true']"
    private const val DOWNLOAD_LINK_SELECTOR = "[data-d-component='pressable'][role='link']"
    private const val STREAMING_ATTRIBUTE = "data-d-streaming-reveal"
    private const val FILE_VIEWER_SELECTOR = "[data-app-shell-focus-area='right-panel']"
    private const val FILE_VIEWER_DOWNLOAD_SELECTOR = "$FILE_VIEWER_SELECTOR button[aria-label='Download']"
    private const val FILE_VIEWER_CLOSE_SELECTOR = "$FILE_VIEWER_SELECTOR button[aria-label='Close viewer']"
    /** Marks the group of buttons the plugin adds next to a file in the page. */
    private const val ACTIONS_ATTRIBUTE = "data-webchathelper-actions"
    /** Identifies a download link that has buttons, so the plugin can click it. */
    private const val DOWNLOAD_LINK_ATTRIBUTE = "data-webchathelper-link"

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
    const val PART_DOWNLOAD_LINK = "download link"

    /**
     * Page event posted when '@' is typed into the prompt box: `{ prompt, offset }`, the prompt's text and
     * the '@''s character offset in it. The plugin decides whether to open the file dialog (#B11).
     */
    const val EVENT_AT_TYPED = "atTyped"

    /** Page event posted when the bridge has been installed in a newly loaded page. */
    const val EVENT_BRIDGE_READY = "bridgeReady"

    /** Page event posted when a code block's button is clicked: `{ action, kind, name, content }` (#XIO). */
    const val EVENT_CODE_ACTION = "codeAction"

    /** Page event posted when a download link's button is clicked: `{ action, kind, name, link }` (#XIO). */
    const val EVENT_DOWNLOAD_ACTION = "downloadAction"

    /** The buttons added next to a file in a response (#XIO). */
    const val ACTION_DIFF = "diff"
    const val ACTION_APPLY = "apply"

    /** The kinds of file a response can hold (#XIO). */
    const val KIND_FILE = "file"
    const val KIND_PATCH = "patch"
    const val KIND_ZIP = "zip"

    /** Page event posted, once per page load, when the page has code that the selectors don't recognise. */
    const val EVENT_RESPONSES_UNRECOGNISED = "responsesUnrecognised"

    // Declared before the scripts below, which use it while the object initialises.
    private val gson = Gson()

    /** How long the page waits for a clicked download link to produce its file. */
    private const val CAPTURE_TIMEOUT_MS = 20_000

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
          $responseDiffButtons
          window.__webChatHelper.post(${js(EVENT_BRIDGE_READY)}, { url: location.href });
        })();
    """.trimIndent()

    /**
     * Keeps Diff and Apply buttons on the files in ChatGPT's responses (#XIO), rechecking whenever the page
     * changes, as responses stream in and ChatGPT redraws them. Part of [installBridgeScript].
     *
     * Each file's kind and name come from the text before it, as described in the note under #Z98 in the
     * spec. With no name there are no buttons (#8N4). Patches and zips always have them. A code block has
     * none until its response has been written (#XIO).
     */
    private val responseDiffButtons = """
        let attachedNames = new Set(), scanTimer = null, nextLink = 1, warned = false;
        const actionsSelector = ${js("[$ACTIONS_ATTRIBUTE]")};
        const pathComment = /^\s*(?:\/\/|\/\*+|#|--|;|%|<!--)\s*(?:(?:file(?:name)?|path)\s*:\s*)?(\S+?)\s*(?:\*\/|-->)?\s*$/i;
        const baseNames = text => text.split(/[\s`'"“”‘’()\[\]<>,;:!?*]+/)
          .map(word => word.replace(/\.+$/, '').split(/[\/\\]/).pop()).filter(Boolean).reverse();
        const lastAttachedName = text => baseNames(text).find(name => attachedNames.has(name));
        const lastLine = text => text.trim().split('\n').pop().trim();
        const pathIn = line => {
          const path = line.replace(/^[*_`'"“”‘’]+|[*_`'"“”‘’:]+$/g, '').replace(/^(?:file|path)\s*:\s*/i, '').replace(/\\/g, '/').replace(/^\.\//, '');
          return /^[\w.@+\-\/]+$/.test(path) && /\/|\.\w+$/.test(path) && !/^\/|\/$|(?:^|\/)\.\.(?:\/|$)/.test(path) ? path : null;
        };
        const isInline = node => node.nodeType !== 1 || getComputedStyle(node).display.startsWith('inline');
        const nodeText = node => node.nodeType === 3 ? node.textContent : node.nodeType === 1 && !node.matches(actionsSelector) ? node.innerText || '' : '';
        const textBefore = (element, response) => {
          for (let e = element; e && e !== response; e = e.parentElement) {
            let text = '';
            for (let n = e.previousSibling; n; n = n.previousSibling) {
              if (!isInline(n)) { if (!text.trim()) text = nodeText(n); break; }
              text = nodeText(n) + text;
            }
            if (text.trim()) return text;
          }
          return '';
        };
        const codeFile = (block, code, response) => {
          const before = textBefore(block, response);
          if (/^diff:$/i.test(lastLine(before))) return { kind: ${js(KIND_PATCH)}, name: 'patch' };
          const comment = code.textContent.split('\n', 1)[0].match(pathComment);
          const name = pathIn(lastLine(before)) || lastAttachedName(before) || (comment && (pathIn(comment[1]) || lastAttachedName(comment[1])));
          return name ? { kind: ${js(KIND_FILE)}, name } : null;
        };
        const linkFile = (link, response) => {
          const names = baseNames(link.innerText), named = pattern => names.find(name => pattern.test(name));
          const patch = named(/\.(?:diff|patch)$/i), zip = named(/\.zip$/i);
          if (patch) return { kind: ${js(KIND_PATCH)}, name: patch };
          if (zip) return { kind: ${js(KIND_ZIP)}, name: zip };
          const before = textBefore(link, response);
          const name = pathIn(lastLine(before)) || lastAttachedName(link.innerText) || lastAttachedName(before);
          return name ? { kind: ${js(KIND_FILE)}, name } : null;
        };
        const actionButtons = post => {
          const group = document.createElement('span');
          group.setAttribute(${js(ACTIONS_ATTRIBUTE)}, '');
          group.style.cssText = 'display:inline-flex';
          for (const [action, label] of [[${js(ACTION_DIFF)}, 'Diff'], [${js(ACTION_APPLY)}, 'Apply']]) {
            const button = document.createElement('button');
            button.type = 'button';
            button.textContent = label;
            button.style.cssText = 'font-size:12px;line-height:20px;padding:0 8px;margin-inline:4px;border:1px solid currentColor;border-radius:6px;background:transparent;opacity:.75;cursor:pointer';
            button.addEventListener('click', e => {
              e.preventDefault();
              e.stopPropagation();
              post({ action, kind: group.dataset.kind, name: group.dataset.name });
            });
            group.append(button);
          }
          return group;
        };
        const show = (group, file) => {
          group.dataset.kind = file.kind;
          group.dataset.name = file.name;
          group.firstChild.title = 'Compare ' + file.name + ' with the project';
          group.lastChild.title = 'Apply ' + file.name + ' to the project';
        };
        const scanCodeBlock = (block, response) => {
          const code = block.querySelector('code'), controls = block.querySelector(${js(CODE_BLOCK_CONTROLS_SELECTOR)});
          if (!code || !controls) return;
          const file = block.closest(${js("[$STREAMING_ATTRIBUTE]")}) ? null : codeFile(block, code, response);
          let group = controls.querySelector(actionsSelector);
          if (!file) { if (group) group.remove(); return; }
          if (!group) {
            group = actionButtons(event => window.__webChatHelper.post(${js(EVENT_CODE_ACTION)}, { ...event, content: block.querySelector('code').textContent }));
            controls.append(group);
          }
          show(group, file);
        };
        const scanLink = (link, response) => {
          const file = linkFile(link, response);
          const next = link.nextElementSibling;
          let group = next && next.matches(actionsSelector) ? next : null;
          if (!file) { if (group) group.remove(); return; }
          if (!group) {
            if (!link.hasAttribute(${js(DOWNLOAD_LINK_ATTRIBUTE)})) link.setAttribute(${js(DOWNLOAD_LINK_ATTRIBUTE)}, String(nextLink++));
            group = actionButtons(event => window.__webChatHelper.post(${js(EVENT_DOWNLOAD_ACTION)}, { ...event, link: link.getAttribute(${js(DOWNLOAD_LINK_ATTRIBUTE)}) }));
            link.after(group);
          }
          show(group, file);
        };
        const scan = () => {
          for (const response of document.querySelectorAll(${js(RESPONSE_SELECTOR)})) {
            response.querySelectorAll(${js(CODE_BLOCK_SELECTOR)}).forEach(block => scanCodeBlock(block, response));
            response.querySelectorAll(${js(DOWNLOAD_LINK_SELECTOR)}).forEach(link => scanLink(link, response));
          }
          if (warned) return;
          const recognised = e => { const block = e.closest(${js(CODE_BLOCK_SELECTOR)}); return block && block.closest(${js(RESPONSE_SELECTOR)}); };
          if ([...document.querySelectorAll(${js("main pre, main $CODE_BLOCK_SELECTOR")})].some(e => e.offsetParent && !recognised(e))) {
            warned = true;
            window.__webChatHelper.post(${js(EVENT_RESPONSES_UNRECOGNISED)});
          }
        };
        window.__webChatHelper.setAttachedNames = names => { attachedNames = new Set(names); scan(); };
        new MutationObserver(() => {
          if (!scanTimer) scanTimer = setTimeout(() => { scanTimer = null; scan(); }, 300);
        }).observe(document.documentElement, { childList: true, subtree: true, characterData: true, attributes: true, attributeFilter: [${js(STREAMING_ATTRIBUTE)}] });
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

    /** Tells the page's diff buttons which file names have been attached (#AD4), and rechecks the page. */
    internal fun setAttachedNamesScript(names: Collection<String>): String = script(
        """
        if (window.__webChatHelper) window.__webChatHelper.setAttachedNames(${gson.toJson(names)});
        return { ok: true, value: true };
        """,
    )

    /**
     * Gets ready to catch the file behind the download link identified by [link] (from
     * [EVENT_DOWNLOAD_ACTION]) and returns `{ x, y }`, the point in the page to click to start it. The
     * plugin then clicks there and runs [finishDownloadScript].
     *
     * Any file viewer already open is closed first, as it would otherwise be mistaken for the link's.
     */
    internal fun startDownloadScript(link: String): String = script(
        """
        const link = document.querySelector(${js("[$DOWNLOAD_LINK_ATTRIBUTE='$link']")});
        if (!link) return { missing: ${js(PART_DOWNLOAD_LINK)} };
        const openViewer = document.querySelector(${js(FILE_VIEWER_CLOSE_SELECTOR)});
        if (openViewer) { openViewer.click(); await sleep(800); }
        window.__webChatHelperDownload = new Promise(resolve => {
          const finish = result => { document.removeEventListener('click', onClick, true); clearTimeout(timer); resolve(result); };
          const timer = setTimeout(() => finish({ error: "ChatGPT didn't provide the file. The link may have expired." }), $CAPTURE_TIMEOUT_MS);
          const onClick = e => {
            const anchor = e.target.closest && e.target.closest('a');
            if (!anchor || !(anchor.hasAttribute('download') || anchor.href.startsWith('blob:'))) return;
            e.preventDefault(); // so the file isn't also saved in the user's Downloads folder
            fetch(anchor.href).then(r => r.blob()).then(blob => new Promise((done, failed) => {
              const reader = new FileReader();
              reader.onload = () => done({ ok: true, value: reader.result.split(',')[1] });
              reader.onerror = () => failed(reader.error);
              reader.readAsDataURL(blob);
            })).then(finish, error => finish({ error: "Couldn't read the file: " + error }));
          };
          document.addEventListener('click', onClick, true);
        });
        link.scrollIntoView({ block: 'center' });
        await sleep(500); // let the scrolling finish
        const box = link.getClientRects()[0];
        return { ok: true, value: { x: box.left + box.width / 2, y: box.top + box.height / 2 } };
        """,
    )

    /**
     * Waits for the file [startDownloadScript] is catching and returns it in base64. If ChatGPT shows the
     * file in its viewer instead of downloading it, uses the viewer's Download button and closes it again.
     */
    internal fun finishDownloadScript(): String = script(
        """
        const download = window.__webChatHelperDownload;
        if (!download) return { error: 'The download was not started.' };
        let result, usedViewer = false;
        while (!(result = await Promise.race([download, sleep(100)]))) {
          const save = document.querySelector(${js(FILE_VIEWER_DOWNLOAD_SELECTOR)});
          if (save && !usedViewer) { save.click(); usedViewer = true; }
        }
        delete window.__webChatHelperDownload;
        if (usedViewer) document.querySelector(${js(FILE_VIEWER_CLOSE_SELECTOR)})?.click();
        return result;
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
