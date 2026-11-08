package com.pauldillon.webchathelper.chatgpt

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.future.await
import org.cef.browser.CefBrowser
import org.cef.browser.CefDevToolsClient
import org.cef.browser.CefFrame
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefLoadHandlerAdapter
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Thrown when the plugin cannot do something in ChatGPT's page. [message] is written for the user.
 */
class ChatGptPageException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The plugin's connection to ChatGPT's page. Runs the scripts in [ChatGptPage] and reports page events.
 *
 * - Plugin to page: the DevTools protocol, through the browser's in-process [CefDevToolsClient]
 *   (no debugging port). `Runtime.evaluate` runs scripts and returns their results, and
 *   `DOM.setFileInputFiles` attaches files.
 * - Page to plugin: a [JBCefJSQuery], called by `window.__webChatHelper.post(type, data)` in the page.
 *
 * One bridge serves the tool window for its lifetime; [bind] moves it to a new browser when the
 * browser is recreated (#K2W), keeping event listeners and the [conversations] tracker.
 */
class ChatGptBridge : Disposable {

    fun interface EventListener {
        /** Called on a browser thread with the event's data (JSON null when there is none). */
        fun onEvent(data: JsonElement)
    }

    /** The conversation the browser is showing. */
    val conversations = ConversationTracker()

    private val listeners = ConcurrentHashMap<String, CopyOnWriteArrayList<EventListener>>()

    @Volatile
    private var binding: Binding? = null

    /** Connects the bridge to [browser], replacing any previous browser. Call before the browser loads. */
    fun bind(browser: JBCefBrowser) {
        binding = Binding(browser).also { Disposer.register(browser, it) }
    }

    /** Listens for events of [type] posted by the page until [parent] is disposed. */
    fun addEventListener(type: String, parent: Disposable, listener: EventListener) {
        val list = listeners.computeIfAbsent(type) { CopyOnWriteArrayList() }
        list += listener
        Disposer.register(parent) { list -= listener }
    }

    /** The prompt's text, with a linebreak between lines. */
    suspend fun readPrompt(): String = evaluateScript(ChatGptPage.readPromptScript()).asString

    /**
     * Inserts [text] at character [offset] of the prompt's text (as returned by [readPrompt]) and returns
     * the new prompt text. Linebreaks in [text] start new lines without submitting the prompt.
     */
    suspend fun insertPromptText(offset: Int, text: String): String =
        evaluateScript(ChatGptPage.insertPromptTextScript(offset, text)).asString

    /**
     * Deletes [expected] at character [offset] of the prompt's text, if it is still there.
     * Returns whether it was deleted.
     */
    suspend fun deletePromptText(offset: Int, expected: String): Boolean =
        evaluateScript(ChatGptPage.deletePromptTextScript(offset, expected)).asBoolean

    /** The number of files attached to the prompt, including ones still uploading. */
    suspend fun countAttachments(): Int = evaluateScript(ChatGptPage.countAttachmentsScript()).asInt

    /**
     * Attaches [files] to the prompt, as if the user had chosen them in ChatGPT's "Add files" dialog.
     * ChatGPT starts uploading them straight away.
     */
    suspend fun attachFiles(files: List<Path>) {
        val devTools = currentBinding().devTools()
        val found = devTools.call("Runtime.evaluate", JsonObject().apply {
            addProperty("expression", ChatGptPage.findFileInputScript())
        })
        val objectId = found.getAsJsonObject("result")?.get("objectId")?.asString
            ?: throw missingPart(ChatGptPage.PART_FILE_UPLOAD)
        try {
            devTools.call("DOM.setFileInputFiles", JsonObject().apply {
                add("files", JsonArray().apply { files.forEach { add(it.toAbsolutePath().toString()) } })
                addProperty("objectId", objectId)
            })
        } finally {
            runCatching { devTools.call("Runtime.releaseObject", JsonObject().apply { addProperty("objectId", objectId) }) }
        }
    }

    /** Runs a [ChatGptPage] script and returns its `value`, turning its other results into exceptions. */
    private suspend fun evaluateScript(script: String): JsonElement {
        val response = currentBinding().devTools().call("Runtime.evaluate", JsonObject().apply {
            addProperty("expression", script)
            addProperty("awaitPromise", true)
            addProperty("returnByValue", true)
        })
        response.getAsJsonObject("exceptionDetails")?.let {
            thisLogger().warn("ChatGPT page script failed: $it")
            throw ChatGptPageException("$PROBLEM ChatGPT's page reported an error; the plugin may need updating.")
        }
        val result = response.getAsJsonObject("result")?.get("value")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw ChatGptPageException("$PROBLEM The page script returned nothing.")
        result.get("missing")?.let { throw missingPart(it.asString) }
        result.get("error")?.let { throw ChatGptPageException("$PROBLEM ${it.asString}") }
        return result.get("value")
    }

    private fun missingPart(part: String) = ChatGptPageException(
        "Can't find ChatGPT's $part. Check that ChatGPT has finished loading and you are logged in. " +
            "If it keeps happening, ChatGPT's page has changed and WebChatHelper needs updating.",
    )

    private fun currentBinding() = binding ?: throw ChatGptPageException("ChatGPT isn't open.")

    private fun dispatch(request: String) {
        val event = runCatching { JsonParser.parseString(request).asJsonObject }.getOrNull()
        val type = event?.get("type")?.takeIf { it.isJsonPrimitive }?.asString
        if (type == null) {
            thisLogger().warn("Ignoring malformed page event: ${request.take(200)}")
            return
        }
        val data = event.get("data") ?: JsonNull.INSTANCE
        listeners[type]?.forEach { it.onEvent(data) }
    }

    override fun dispose() {}

    /** The bridge's hooks into one browser. Disposed with the browser. */
    private inner class Binding(private val browser: JBCefBrowser) : Disposable {
        /** Disposed with the browser. */
        private val query = JBCefJSQuery.create(browser as JBCefBrowserBase)

        @Volatile
        private var devToolsClient: CefDevToolsClient? = null

        init {
            query.addHandler { request ->
                dispatch(request)
                null
            }
            val client = browser.jbCefClient
            client.addLoadHandler(object : CefLoadHandlerAdapter() {
                override fun onLoadEnd(cefBrowser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                    if (frame.isMain && ChatGptPage.isChatGptUrl(frame.url)) {
                        cefBrowser.executeJavaScript(ChatGptPage.installBridgeScript(query.inject("request")), frame.url, 0)
                    }
                }
            }, browser.cefBrowser)
            client.addDisplayHandler(object : CefDisplayHandlerAdapter() {
                override fun onAddressChange(cefBrowser: CefBrowser, frame: CefFrame, url: String) {
                    if (frame.isMain) conversations.urlChanged(url)
                }
            }, browser.cefBrowser)
        }

        @Synchronized
        fun devTools(): CefDevToolsClient {
            devToolsClient?.takeUnless { it.isClosed }?.let { return it }
            if (browser.isDisposed) throw ChatGptPageException("ChatGPT isn't open.")
            return (browser.cefBrowser.devToolsClient ?: throw ChatGptPageException("ChatGPT hasn't finished opening."))
                .also { devToolsClient = it }
        }

        @Synchronized
        override fun dispose() {
            devToolsClient?.close()
            devToolsClient = null
            if (binding === this) binding = null
        }
    }

    private companion object {
        const val PROBLEM = "Something went wrong in ChatGPT's page."
    }
}

/** Calls a DevTools protocol method and returns its result, turning failures into [ChatGptPageException]. */
private suspend fun CefDevToolsClient.call(method: String, params: JsonObject): JsonObject {
    val response = try {
        executeDevToolsMethod(method, params.toString()).await()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw ChatGptPageException("Couldn't control ChatGPT's page ($method failed: ${e.message}).", e)
    }
    return JsonParser.parseString(response).asJsonObject
}
