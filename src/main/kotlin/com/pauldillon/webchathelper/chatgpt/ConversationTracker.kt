package com.pauldillon.webchathelper.chatgpt

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.Disposer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** The ChatGPT conversation the browser is showing. */
sealed interface Conversation {
    /** A saved conversation, identified by the ID in its address. */
    data class Existing(val id: String) : Conversation

    /**
     * A chat with no ID yet: the start page, a project's page, or any other page without a conversation ID.
     * ChatGPT assigns an ID when the first message is sent, which shows up as a change to [Existing].
     * Each visit to such a page gets a new [key], so two new chats are never treated as the same one.
     */
    data class New(val key: Int) : Conversation
}

/**
 * Tracks which conversation the browser is showing, from the page address, and tells listeners when
 * it changes. Fed by [ChatGptBridge] with every main-frame address change, including ChatGPT's in-page
 * navigation. Reloading the same page is not a change, so a reload (#K2W) keeps the conversation.
 */
class ConversationTracker {
    fun interface Listener {
        /** Called on a browser thread. */
        fun conversationChanged(previous: Conversation, current: Conversation)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val newChatKeys = AtomicInteger()

    @Volatile
    var current: Conversation = Conversation.New(newChatKeys.incrementAndGet())
        private set

    /**
     * Path of the current page when it has no conversation ID, so staying on it is not a change.
     * Null when the current page has an ID, or before the first page.
     */
    private var newChatPath: String? = null

    fun addListener(parent: Disposable, listener: Listener) {
        listeners += listener
        Disposer.register(parent) { listeners -= listener }
    }

    internal fun urlChanged(url: String) {
        if (!ChatGptPage.isChatGptUrl(url)) return // e.g. the login provider's pages
        val previous: Conversation
        val next: Conversation
        synchronized(this) {
            previous = current
            val id = ChatGptPage.conversationId(url)
            val path = ChatGptPage.pagePath(url)
            next = when {
                id != null -> if (previous == Conversation.Existing(id)) return else Conversation.Existing(id)
                // Still on the same new-chat page, or the first page loaded is a new chat.
                previous is Conversation.New && (newChatPath == null || newChatPath == path) -> {
                    newChatPath = path
                    return
                }
                else -> Conversation.New(newChatKeys.incrementAndGet())
            }
            newChatPath = if (id == null) path else null
            current = next
        }
        thisLogger().info("Conversation changed: $previous -> $next")
        listeners.forEach { it.conversationChanged(previous, next) }
    }
}
