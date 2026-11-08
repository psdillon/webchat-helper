package com.pauldillon.webchathelper.toolwindow

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.guessProjectDir
import com.pauldillon.webchathelper.WebChatNotifications
import com.pauldillon.webchathelper.attach.AttachmentService

/**
 * Attach button under the browser (#Z7H): lets the user choose project files and folders and attaches
 * them to ChatGPT's prompt.
 */
class AttachFilesAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.getData(WebChatPanel.KEY) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val panel = e.getData(WebChatPanel.KEY) ?: return
        val project = panel.project
        val root = project.guessProjectDir() ?: run {
            WebChatNotifications.warning(project, "This project has no root folder, so files can't be attached.")
            return
        }
        // Only project files can be attached, as paths are shown relative to the project root (#VV0).
        val descriptor = FileChooserDescriptorFactory.multiFilesOrDirs()
            .withTitle("Attach to ChatGPT")
            .withDescription("Choose project files and folders to attach to the prompt.")
            .withRoots(root)
            .withTreeRootVisible(true)
        val selected = FileChooser.chooseFiles(descriptor, project, null)
        AttachmentService.getInstance(project).attach(panel.bridge, selected.toList())
    }
}
