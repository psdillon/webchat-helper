package com.pauldillon.webchathelper.toolwindow

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.pauldillon.webchathelper.settings.WebChatHelperConfigurable

/** Settings button under the browser (#7IO): opens the plugin's page in the IDE Settings dialog. */
class OpenSettingsAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        ShowSettingsUtil.getInstance().showSettingsDialog(e.project, WebChatHelperConfigurable::class.java)
    }
}
