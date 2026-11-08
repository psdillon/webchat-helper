package com.pauldillon.webchathelper.toolwindow

import com.google.gson.JsonElement
import com.intellij.ide.util.gotoByName.ChooseByNamePopup
import com.intellij.ide.util.gotoByName.ChooseByNamePopupComponent
import com.intellij.ide.util.gotoByName.GotoFileModel
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileSystemItem
import com.pauldillon.webchathelper.attach.AtTrigger
import com.pauldillon.webchathelper.attach.AttachmentService
import com.pauldillon.webchathelper.chatgpt.ChatGptPage
import com.pauldillon.webchathelper.settings.WebChatHelperSettings

/**
 * Opens the IDE's "go to file" popup when '@' is typed at the start of the prompt or after whitespace,
 * and attaches the chosen files and folders (#B11). The '@' is removed when something is chosen, and
 * kept if the popup is cancelled (#O2S).
 */
class AtFileChooser(private val panel: WebChatPanel) {

    init {
        panel.bridge.addEventListener(ChatGptPage.EVENT_AT_TYPED, panel) { onAtTyped(it) }
    }

    /** Called on a browser thread. */
    private fun onAtTyped(data: JsonElement) {
        val event = data.takeIf { it.isJsonObject }?.asJsonObject ?: return
        val prompt = event.get("prompt")?.asString ?: return
        val offset = event.get("offset")?.asInt ?: return
        if (!AtTrigger.opensDialog(prompt, offset)) return
        ApplicationManager.getApplication().invokeLater({ showPopup(offset) }, panel.project.disposed)
    }

    private fun showPopup(atOffset: Int) {
        val project = panel.project
        val settings = WebChatHelperSettings.getInstance().state
        val model = if (settings.atAlwaysShowFolders) GotoFileOrFolderModel(project) else GotoFileModel(project) // #4QH
        val popup = ChooseByNamePopup.createPopup(project, model, null as PsiElement?)
        Disposer.register(popup, model)
        popup.invoke(object : ChooseByNamePopupComponent.MultiElementsCallback() {
            override fun elementsChosen(elements: List<Any>) {
                val files = elements.mapNotNull { (it as? PsiFileSystemItem)?.virtualFile }
                if (files.isEmpty()) return
                AttachmentService.getInstance(project).attach(panel.bridge, files) {
                    if (!panel.bridge.deletePromptText(atOffset, "@")) {
                        thisLogger().info("'@' at $atOffset was no longer in the prompt, so it was left alone")
                    }
                }
            }
        }, ModalityState.current(), settings.atMultiSelect) // #0AJ
    }
}
