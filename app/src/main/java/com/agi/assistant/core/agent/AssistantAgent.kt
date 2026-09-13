package com.agi.assistant.core.agent

import android.content.Context
import android.util.Log
import com.agi.assistant.core.ai.*
import com.agi.assistant.core.settings.SecureSettings
import com.agi.assistant.core.tools.ToolContext
import com.agi.assistant.core.tools.ToolRegistry
import com.agi.assistant.core.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Android binding of [AgentLoop]: resolves the configured provider, runs tools with a real
 * [ToolContext] and keeps everything on [Dispatchers.IO]. The loop semantics live in AgentLoop.
 */
class AssistantAgent(
    private val context: Context,
    private val settings: SecureSettings,
    private val tools: ToolRegistry,
    private val store: ConversationStore,
) {
    private val loop = AgentLoop(maxSteps = 8) { Log.d(TAG, it) }

    val conversation: ConversationStore get() = store

    /** Static part of the system prompt; built once (tool list does not change at runtime). */
    private val promptPrefix: String by lazy {
        val toolList = tools.all.groupBy { it.category }.entries.joinToString("\n") { (cat, list) ->
            "$cat: " + list.joinToString { it.spec.name }
        }
        """
            You are an on-device Android personal assistant. You control the user's phone by calling tools.

            Rules:
            - Prefer acting over explaining. When the user asks for a phone action, call the matching tool.
            - For plain conversation or general questions, just answer; do not call tools you do not need.
            - For multi-step requests, call tools one after another and use the results of earlier tools.
            - After tools run, give a short, natural spoken-style summary of what happened or what you found.
            - If information is on the screen (after opening a page), use read_screen to look at it before answering.
            - Never invent results; report tool errors honestly and suggest what the user can do.
            - Keep replies brief: 1-3 sentences unless the user asks for detail.
            - The user may speak Bangla or English; reply in the language they used.

            Available tools by category:
            $toolList
        """.trimIndent()
    }

    suspend fun handle(userText: String, onEvent: suspend (AgentEvent) -> Unit) = withContext(Dispatchers.IO) {
        store.add(ChatMessage(Role.USER, userText))
        val config = settings.providerConfig()
        val provider = AiProviderFactory.create(config)
        val fallback = if (provider.isRemote && settings.fallbackToLocal) AiProviderFactory.fallback else null
        val toolCtx = ToolContext(context) { }
        loop.run(
            provider = provider,
            fallback = fallback,
            systemPrompt = systemPrompt(),
            toolSpecs = tools.specs,
            history = store,
            secrets = config.secrets,
            runTool = { call -> runTool(call, toolCtx) },
            onEvent = onEvent,
        )
    }

    private suspend fun runTool(call: ToolCall, ctx: ToolContext): ToolResult {
        val tool = tools.get(call.name) ?: return ToolResult.fail("Unknown tool '${call.name}'. Available: ${tools.specs.joinToString { it.name }}")
        return try {
            tool.execute(call.arguments, ctx)
        } catch (e: Exception) {
            Log.e(TAG, "Tool ${call.name} crashed", e)
            ToolResult.fail("Tool '${call.name}' failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * Static prefix first, volatile date/time last: the long, unchanging part of the request stays
     * byte-identical across turns so the provider's implicit prefix caching can apply.
     */
    private fun systemPrompt(): String {
        val now = SimpleDateFormat("EEEE, d MMMM yyyy HH:mm", Locale.getDefault()).format(Date())
        return promptPrefix + "\n\nCurrent date/time: $now."
    }

    companion object { private const val TAG = "AssistantAgent" }
}
