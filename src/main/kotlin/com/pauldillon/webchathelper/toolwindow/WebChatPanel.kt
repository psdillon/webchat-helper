package com.pauldillon.webchathelper.toolwindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.ui.JBUI
import com.pauldillon.webchathelper.chatgpt.ChatGptPage
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefDisplayHandlerAdapter
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * Tool window content: the ChatGPT browser (#R4F) with a button row underneath it.
 * The row shows the actions in [TOOLBAR_GROUP_ID]: reload (#K2W), home (#W4R), settings (#7IO),
 * and later attach (#Z7H).
 */
class WebChatPanel(val project: Project) : JPanel(BorderLayout()), Disposable, UiDataProvider {

    /** Last address the browser showed, tracked so [reload] can reopen it even if the browser has died. */
    @Volatile
    private var lastUrl: String = ChatGptPage.URL

    /** The current browser. Replaced by [reload]. */
    var browser: JBCefBrowser = createBrowser(ChatGptPage.URL)
        private set

    init {
        add(browser.component, BorderLayout.CENTER)
        add(createToolbar(), BorderLayout.SOUTH)
    }

    /**
     * Replaces the browser with a new one showing the page the old one was on (#K2W).
     * A new browser is needed because the old one stops working if the IDE restarts JCEF
     * (for example after GPU process crashes).
     */
    fun reload() {
        val old = browser
        browser = createBrowser(lastUrl)
        remove(old.component)
        add(browser.component, BorderLayout.CENTER)
        Disposer.dispose(old)
        revalidate()
        repaint()
    }

    /** Takes the browser back to the ChatGPT start page (#W4R). */
    fun goHome() {
        browser.loadURL(ChatGptPage.URL)
    }

    private fun createBrowser(url: String): JBCefBrowser {
        val newBrowser = JBCefBrowser.createBuilder().setUrl(url).build()
        Disposer.register(this, newBrowser)
        newBrowser.jbCefClient.addDisplayHandler(object : CefDisplayHandlerAdapter() {
            override fun onAddressChange(cefBrowser: CefBrowser, frame: CefFrame, url: String) {
                if (frame.isMain) lastUrl = url
            }
        }, newBrowser.cefBrowser)
        return newBrowser
    }

    private fun createToolbar() = run {
        val group = ActionManager.getInstance().getAction(TOOLBAR_GROUP_ID) as? ActionGroup
            ?: DefaultActionGroup()
        val toolbar = ActionManager.getInstance().createActionToolbar(TOOLBAR_PLACE, group, true)
        toolbar.targetComponent = this
        toolbar.component.apply { border = JBUI.Borders.customLineTop(JBUI.CurrentTheme.ToolWindow.borderColor()) }
    }

    override fun uiDataSnapshot(sink: DataSink) {
        sink[KEY] = this
    }

    override fun dispose() {}

    companion object {
        /** Gives toolbar actions access to the panel. */
        val KEY: DataKey<WebChatPanel> = DataKey.create("WebChatHelper.WebChatPanel")

        /** Action group (declared in plugin.xml) for the button row under the browser. */
        const val TOOLBAR_GROUP_ID = "WebChatHelper.BrowserToolbar"
        const val TOOLBAR_PLACE = "WebChatHelperBrowserToolbar"
    }
}
