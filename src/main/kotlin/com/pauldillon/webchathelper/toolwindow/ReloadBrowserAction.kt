package com.pauldillon.webchathelper.toolwindow

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

/** Reload button under the browser (#K2W): recreates the browser on the page it was showing. */
class ReloadBrowserAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.getData(WebChatPanel.KEY) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        e.getData(WebChatPanel.KEY)?.reload()
    }
}
