package com.pauldillon.webchathelper.settings

import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows

/**
 * The plugin's page in the IDE Settings dialog (#X4G, #Y0G), under Tools. Registered in plugin.xml
 * and opened from the settings button under the browser (#7IO).
 */
class WebChatHelperConfigurable : BoundSearchableConfigurable(DISPLAY_NAME, ID, ID) {

    override fun createPanel(): DialogPanel {
        val state = WebChatHelperSettings.getInstance().state
        return panel {
            // #8LI
            group("Attachments") {
                row {
                    label("Files and folders never sent to ChatGPT as part of an attached folder:")
                }
                row {
                    textArea()
                        .rows(8)
                        .align(Align.FILL)
                        .bindText(
                            { state.exclusionGlobs.joinToString("\n") },
                            { text -> state.exclusionGlobs = text.trimEnd().lines().toMutableList() },
                        )
                        .comment(
                            "One pattern per line in .gitignore format, relative to the project root. " +
                                "Files you attach individually are still sent. The project's .gitignore files are not used.",
                        )
                }.resizableRow()
            }
            // #T48, #N67
            group("'@' File Selection") {
                row {
                    checkBox("Allow selecting multiple files and folders")
                        .bindSelected(state::atMultiSelect)
                }
                row {
                    checkBox("Always show folders")
                        .bindSelected(state::atAlwaysShowFolders)
                        .comment("When off, folders are shown only when the search text ends with '/'.")
                }
            }
            // #XIO, #3PS
            group("ChatGPT's Patches") {
                row {
                    checkBox("Make the diff of a patch read-only")
                        .bindSelected(state::patchDiffReadOnly)
                        .comment("Once a project file has been changed, the patch can no longer be shown as a diff.")
                }
            }
        }
    }

    companion object {
        const val ID = "com.pauldillon.webchathelper.settings"
        const val DISPLAY_NAME = "WebChatHelper"
    }
}
