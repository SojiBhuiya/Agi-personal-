package com.agi.assistant.core.agent

import com.agi.assistant.core.ai.*
import com.agi.assistant.core.tools.ToolResult
import com.agi.assistant.core.tools.ToolSpec

/** UI-facing events emitted while a request is being processed. */
sealed class AgentEvent {
    data class Thinking(val detail: String) : AgentEvent()
    data class ToolStarted(val name: String, val args: Map<String, Any?>) : AgentEvent()
    data class ToolFinished(val name: String, val result: ToolResult) : AgentEvent()
    data class Reply(val text: String) : AgentEvent()
    data class NeedsPermission(val need: com.agi.assistant.core.tools.PermissionNeed) : AgentEvent()
    data class Error(val message: String) : AgentEvent()
    object Done : AgentEvent()
}

/** Where the loop reads/writes conversation history (ConversationStore on device, a list in tests). */
interface History {
    fun window(): List<ChatMessage>
    fun add(message: ChatMessage)
}

/**
 * Lightweight per-turn diagnostics. Numbers only – never message text, tool output or secrets –
 * so it is safe to log at debug level. Tests use it to prove request-flow guarantees.
 */
data class TurnTrace(
    var providerCalls: Int = 0,
    var fallbackCalls: Int = 0,
    var toolCalls: Int = 0,
    var providerMs: Long = 0,
    var toolMs: Long = 0,
    var fellBack: Boolean = false,
    var steps: Int = 0,
) {
    override fun toString() = "steps=$steps provider=$providerCalls(${providerMs}ms) fallback=$fallbackCalls tools=$toolCalls(${toolMs}ms) fellBack=$fellBack"
}

/**
 * Bounds what older turns contribute to the prompt: tool outputs from *previous* user turns are
 * truncated (screen dumps can be thousands of characters and are irrelevant to "Hi"). The current
 * turn is always sent complete, so Gemini's strict same-turn validation (thought signatures,
 * functionResponse pairing) is untouched.
 */
object HistoryWindow {
    const val OLD_TOOL_OUTPUT_LIMIT = 600

    fun compact(messages: List<ChatMessage>, limit: Int = OLD_TOOL_OUTPUT_LIMIT): List<ChatMessage> {
        val lastUser = messages.indexOfLast { it.role == Role.USER }
        if (lastUser <= 0) return messages
        return messages.mapIndexed { i, m ->
            if (i < lastUser && m.role == Role.TOOL && m.content.length > limit)
                m.copy(content = m.content.take(limit) + " …[earlier output shortened]")
            else m
        }
    }
}

/**
 * The provider-independent agent loop: ask the model → run the tools it requested → feed results
 * back → repeat until a plain answer (or the step budget ends). Exactly one provider request per
 * step; tools run only when the model asks; fallback only when the remote provider *throws*.
 * No Android dependencies, so the request-flow rules are unit-tested on the JVM.
 */
class AgentLoop(
    private val maxSteps: Int = 8,
    private val log: (String) -> Unit = {},
) {
    suspend fun run(
        provider: AiProvider,
        fallback: AiProvider?,
        systemPrompt: String,
        toolSpecs: List<ToolSpec>,
        history: History,
        secrets: List<String>,
        runTool: suspend (ToolCall) -> ToolResult,
        onEvent: suspend (AgentEvent) -> Unit,
    ): TurnTrace {
        val trace = TurnTrace()
        var active = provider
        try {
            while (trace.steps < maxSteps) {
                trace.steps++
                onEvent(AgentEvent.Thinking(if (active.isRemote) "Asking ${active.displayName}…" else "Planning…"))
                val t0 = System.nanoTime()
                val response = try {
                    val r = active.complete(AiRequest(systemPrompt, history.window(), toolSpecs))
                    if (active === provider) trace.providerCalls++ else trace.fallbackCalls++
                    r
                } catch (e: Exception) {
                    if (active === provider) trace.providerCalls++ else trace.fallbackCalls++
                    val reason = ProviderErrors.describe(e, secrets)
                    log("provider ${active.id} failed: $reason")
                    if (active.isRemote && fallback != null && active !== fallback) {
                        onEvent(AgentEvent.Error("${active.displayName} failed (${reason.take(160)}). Falling back to offline planner."))
                        active = fallback
                        trace.fellBack = true
                        continue
                    }
                    throw AiProviderException(reason, null, (e as? AiProviderException)?.kind ?: ProviderErrorKind.UNKNOWN)
                } finally {
                    trace.providerMs += (System.nanoTime() - t0) / 1_000_000
                }

                if (!response.hasToolCalls) {
                    val text = response.text?.trim().orEmpty().ifBlank { "Done." }
                    history.add(ChatMessage(Role.ASSISTANT, text))
                    onEvent(AgentEvent.Reply(text))
                    break
                }

                history.add(ChatMessage(Role.ASSISTANT, response.text.orEmpty(), response.toolCalls))
                response.text?.takeIf { it.isNotBlank() }?.let { onEvent(AgentEvent.Reply(it)) }

                var blocked = false
                for (call in response.toolCalls) {
                    onEvent(AgentEvent.ToolStarted(call.name, call.arguments))
                    val t1 = System.nanoTime()
                    val result = runTool(call)
                    trace.toolCalls++
                    trace.toolMs += (System.nanoTime() - t1) / 1_000_000
                    onEvent(AgentEvent.ToolFinished(call.name, result))
                    history.add(ChatMessage(Role.TOOL, result.output, toolCallId = call.id, toolName = call.name))
                    if (result.needsPermission != null) {
                        onEvent(AgentEvent.NeedsPermission(result.needsPermission))
                        blocked = true
                        break
                    }
                }
                if (blocked) {
                    val msg = "I need a permission before I can continue. Please grant it and ask me again."
                    history.add(ChatMessage(Role.ASSISTANT, msg))
                    onEvent(AgentEvent.Reply(msg))
                    break
                }
            }
        } catch (e: Exception) {
            val safe = Redactor.redact(e.message ?: e.javaClass.simpleName, secrets)
            log("agent error: $safe")
            val msg = "Something went wrong: $safe"
            history.add(ChatMessage(Role.ASSISTANT, msg))
            onEvent(AgentEvent.Error(msg))
        }
        log("turn $trace")
        onEvent(AgentEvent.Done)
        return trace
    }
}
