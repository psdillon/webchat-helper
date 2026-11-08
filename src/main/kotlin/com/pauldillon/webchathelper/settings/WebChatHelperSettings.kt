package com.pauldillon.webchathelper.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil
import com.intellij.util.xmlb.annotations.XCollection

/**
 * The plugin's settings (#X4G). Application-level, so they apply to all projects (#Y0G).
 * Edited on the page provided by [WebChatHelperConfigurable].
 */
@Service(Service.Level.APP)
@State(name = "WebChatHelperSettings", storages = [Storage("webChatHelper.xml")])
class WebChatHelperSettings : PersistentStateComponent<WebChatHelperSettings.SettingsState> {

    class SettingsState {
        /**
         * Exclusion globs in .gitignore format, relative to the project root (#AN7, #8BN, #8LI).
         * Kept as the lines the user typed, so blank lines and `#` comments survive.
         */
        @get:XCollection(style = XCollection.Style.v2)
        var exclusionGlobs: MutableList<String> = DEFAULT_EXCLUSION_GLOBS.toMutableList()

        /** Whether the '@' file/folder dialog allows selecting several items (#T48, #0AJ). */
        var atMultiSelect: Boolean = true

        /**
         * Whether the '@' dialog always shows folders, rather than only when the search text ends
         * with '/' (the default GotoFileModel behaviour) (#N67, #4QH).
         */
        var atAlwaysShowFolders: Boolean = false

        /** Whether the diff of a patch from ChatGPT is read-only (#XIO, #3PS). */
        var patchDiffReadOnly: Boolean = true
    }

    private var state = SettingsState()

    override fun getState(): SettingsState = state

    override fun loadState(state: SettingsState) {
        XmlSerializerUtil.copyBean(state, this.state)
    }

    companion object {
        /** #CI3 */
        val DEFAULT_EXCLUSION_GLOBS = listOf(".git/", ".idea/", ".env", "build/", "out/", "target/")

        fun getInstance(): WebChatHelperSettings =
            ApplicationManager.getApplication().getService(WebChatHelperSettings::class.java)
    }
}
