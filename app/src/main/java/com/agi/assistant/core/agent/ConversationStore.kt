package com.agi.assistant.core.agent

import android.content.Context
import com.agi.assistant.core.ai.ChatMessage
import com.agi.assistant.core.ai.Role
import org.json.JSONArray
import java.io.File
import java.util.concurrent.Executors

/**
 * Keeps conversation context. The full transcript is persisted to a JSON
 * file in app-private storage; [window] returns the recent slice that is
 * sent to the model so the prompt stays bounded.
 */
class ConversationStore(context: Context, private val maxWindow: Int = 30) : History {
    private val file = File(context.filesDir, "conversation.json")
    private val messages = mutableListOf<ChatMessage>()
    /** Persistence runs off the response path: one writer thread, latest snapshot wins. */
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "conversation-writer").apply { isDaemon = true } }

    init { load() }

    val all: List<ChatMessage> get() = messages.toList()

    @Synchronized
    override fun add(message: ChatMessage) {
        messages += message
        save()
    }

    @Synchronized
    fun clear() {
        messages.clear()
        save()
    }

    /**
     * Recent messages for the prompt. Always starts with a USER turn so every provider accepts it;
     * tool outputs from earlier turns are shortened (see [HistoryWindow]).
     */
    @Synchronized
    override fun window(): List<ChatMessage> {
        var slice = messages.takeLast(maxWindow)
        while (slice.isNotEmpty() && slice.first().role != Role.USER) slice = slice.drop(1)
        return HistoryWindow.compact(slice)
    }

    private fun load() {
        if (!file.exists()) return
        runCatching {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) messages += ChatMessage.fromJson(arr.getJSONObject(i))
        }
    }

    private fun save() {
        val snapshot = messages.takeLast(200).toList()
        writer.execute {
            runCatching {
                val arr = JSONArray()
                snapshot.forEach { arr.put(it.toJson()) }
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(arr.toString())
                if (!tmp.renameTo(file)) file.writeText(arr.toString())
            }
        }
    }
}
