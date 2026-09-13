package com.agi.assistant.core.agent

import android.content.Context
import android.util.Log
import com.agi.assistant.core.ai.*
import com.agi.assistant.core.settings.SecureSettings
import com.agi.assistant.core.tools.PermissionNeed
import com.agi.assistant.core.tools.ToolContext
import com.agi.assistant.core.tools.ToolRegistry
import com.agi.assistant.core.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** UI-facing events emitted while a request is being processed. */
sealed class AgentEvent {
    data class Thinking(val detail: String) : AgentEvent()
    data class ToolStarted(val name: String, val args: Map<String, Any?>) : AgentEvent()
    data class ToolFinished(val name: String, val result: ToolResult) : AgentEvent()
    data class Reply(val text: String) : AgentEvent()
    data class NeedsPermission(val need: PermissionNeed) : AgentEvent()
    data class Error(val message: String) : AgentEvent()
    object Done : AgentEvent()
}

/**
 * The agent loop: sends the conversation to the active [AiProvider], executes
 * any tool calls through the [ToolRegistry], feeds the results back and
 * repeats until the model produces a final answer (or the step budget ends).
 */
class AssistantAgent(
    private val context: Context,
    private val settings: SecureSettings,
    private val tools: ToolRegistry,
    private val store: ConversationStore,
) {
    private val maxSteps = 8

    val conversation: ConversationStore get() = store

    suspend fun handle(userText: String, onEvent: suspend (AgentEvent) -> Unit) = withContext(Dispatchers.IO) {
        store.add(ChatMessage(Role.USER, userText))
        val config = settings.providerConfig()
        var provider = AiProviderFactory.create(config)
        val toolCtx = ToolContext(context) { }

        try {
            var steps = 0
            while (steps++ < maxSteps) {
                onEvent(AgentEvent.Thinking(if (provider.isRemote) "Asking ${provider.displayName}…" else "Planning…"))
                val response = try {
                    provider.complete(AiRequest(systemPrompt(), store.window(), tools.specs))
                } catch (e: Exception) {
                    // Never log the raw exception: a message could echo request details. Redact first.
                    val reason = ProviderErrors.describe(e, config.secrets)
                    Log.w(TAG, "Provider ${provider.id} failed: $reason")
                    if (provider.isRemote && settings.fallbackToLocal) {
                        onEvent(AgentEvent.Error("${provider.displayName} failed (${reason.take(160)}). Falling back to offline planner."))
                        provider = AiProviderFactory.fallback
                        continue
                    }
                    throw AiProviderException(reason, null, (e as? AiProviderException)?.kind ?: ProviderErrorKind.UNKNOWN)
                }

                if (!response.hasToolCalls) {
                    val text = response.text?.trim().orEmpty().ifBlank { "Done." }
                    store.add(ChatMessage(Role.ASSISTANT, text))
                    onEvent(AgentEvent.Reply(text))
                    break
                }

                store.add(ChatMessage(Role.ASSISTANT, response.text.orEmpty(), response.toolCalls))
                response.text?.takeIf { it.isNotBlank() }?.let { onEvent(AgentEvent.Reply(it)) }

                var blocked = false
                for (call in response.toolCalls) {
                    onEvent(AgentEvent.ToolStarted(call.name, call.arguments))
                    val result = runTool(call, toolCtx)
                    onEvent(AgentEvent.ToolFinished(call.name, result))
                    store.add(ChatMessage(Role.TOOL, result.output, toolCallId = call.id, toolName = call.name))
                    if (result.needsPermission != null) {
                        onEvent(AgentEvent.NeedsPermission(result.needsPermission))
                        blocked = true
                        break
                    }
                }
                if (blocked) {
                    val msg = "I need a permission before I can continue. Please grant it and ask me again."
                    store.add(ChatMessage(Role.ASSISTANT, msg))
                    onEvent(AgentEvent.Reply(msg))
                    break
                }
            }
        } catch (e: Exception) {
            val safe = Redactor.redact(e.message ?: e.javaClass.simpleName, config.secrets)
            Log.e(TAG, "Agent error: $safe")
            val msg = "Something went wrong: $safe"
            store.add(ChatMessage(Role.ASSISTANT, msg))
            onEvent(AgentEvent.Error(msg))
        }
        onEvent(AgentEvent.Done)
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

    private fun systemPrompt(): String {
        val now = SimpleDateFormat("EEEE, d MMMM yyyy HH:mm", Locale.getDefault()).format(Date())
        val toolList = tools.all.groupBy { it.category }.entries.joinToString("\n") { (cat, list) ->
            "$cat: " + list.joinToString { it.spec.name }
        }
        return """
            You are an on-device Android personal assistant. You control the user's phone by calling tools.
            Current date/time: $now.

            Rules:
            - Prefer acting over explaining. When the user asks for a phone action, call the matching tool.
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

    companion object { private const val TAG = "AssistantAgent" }
}
