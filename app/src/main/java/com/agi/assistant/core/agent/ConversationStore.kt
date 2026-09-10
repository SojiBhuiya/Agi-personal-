package com.agi.assistant.core.agent

import android.content.Context
import com.agi.assistant.core.ai.ChatMessage
import com.agi.assistant.core.ai.Role
import org.json.JSONArray
import java.io.File

/**
 * Keeps conversation context. The full transcript is persisted to a JSON
 * file in app-private storage; [window] returns the recent slice that is
 * sent to the model so the prompt stays bounded.
 */
class ConversationStore(context: Context, private val maxWindow: Int = 30) {
    private val file = File(context.filesDir, "conversation.json")
    private val messages = mutableListOf<ChatMessage>()

    init { load() }

    val all: List<ChatMessage> get() = messages.toList()

    fun add(message: ChatMessage) {
        messages += message
        save()
    }

    fun clear() {
        messages.clear()
        save()
    }

    /** Recent messages for the prompt. Always starts with a USER turn so every provider accepts it. */
    fun window(): List<ChatMessage> {
        var slice = messages.takeLast(maxWindow)
        while (slice.isNotEmpty() && slice.first().role != Role.USER) slice = slice.drop(1)
        return slice
    }

    private fun load() {
        if (!file.exists()) return
        runCatching {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) messages += ChatMessage.fromJson(arr.getJSONObject(i))
        }
    }

    private fun save() {
        runCatching {
            val arr = JSONArray()
            messages.takeLast(200).forEach { arr.put(it.toJson()) }
            file.writeText(arr.toString())
        }
    }
}
