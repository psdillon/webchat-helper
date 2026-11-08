package com.pauldillon.webchathelper.toolwindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import javax.swing.JComponent

/**
 * Creates the right-anchored tool window (#0E6) that hosts ChatGPT in JCEF (#R4F).
 * The anchor is declared in plugin.xml.
 */
class WebChatToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = if (JBCefApp.isSupported()) WebChatPanel(project) else null
        val content = ContentFactory.getInstance().createContent(panel ?: jcefNotSupportedPanel(), null, false)
        panel?.let { Disposer.register(content, it) }
        toolWindow.contentManager.addContent(content)
    }

    /** Shown instead of the browser when the IDE's runtime has no JCEF support. */
    private fun jcefNotSupportedPanel(): JComponent =
        JBPanelWithEmptyText().withEmptyText("ChatGPT can't be shown here.").apply {
            emptyText.appendLine("This IDE's runtime does not support JCEF (the embedded Chromium browser).")
            emptyText.appendLine("Run the IDE on the bundled JetBrains Runtime with JCEF enabled.")
        }
}
