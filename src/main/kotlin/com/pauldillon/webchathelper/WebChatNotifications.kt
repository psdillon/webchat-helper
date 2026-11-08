package com.pauldillon.webchathelper

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

/** Balloon notifications from the plugin, in the notification group declared in plugin.xml. */
object WebChatNotifications {
    private const val GROUP_ID = "WebChatHelper"

    /** Tells the user something didn't work, for example a [com.pauldillon.webchathelper.chatgpt.ChatGptPageException]. */
    fun error(project: Project?, message: String) = notify(project, message, NotificationType.ERROR)

    /** Tells the user that something they asked for was not done, and why, e.g. an attachment over ChatGPT's limits. */
    fun warning(project: Project?, message: String) = notify(project, message, NotificationType.WARNING)

    /** Tells the user something that isn't a problem, e.g. that a folder has no changes to attach. */
    fun info(project: Project?, message: String) = notify(project, message, NotificationType.INFORMATION)

    private fun notify(project: Project?, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup(GROUP_ID)
            .createNotification("WebChatHelper", message, type)
            .notify(project)
    }
}
