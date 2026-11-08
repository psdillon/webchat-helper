package com.pauldillon.webchathelper.toolwindow

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

/** Home button under the browser (#W4R): goes back to the ChatGPT start page. */
class GoHomeAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.getData(WebChatPanel.KEY) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        e.getData(WebChatPanel.KEY)?.goHome()
    }
}
