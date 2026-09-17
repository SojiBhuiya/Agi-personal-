package com.agi.assistant.core.agent

import com.agi.assistant.core.ai.*
import com.agi.assistant.core.tools.ToolResult
import com.agi.assistant.core.tools.ToolSpec
import com.agi.assistant.core.tools.ToolIntent

/** UI-facing events emitted while a request is being processed. */
sealed class AgentEvent {
    data class Thinking(val detail: String) : AgentEvent()
    data class ToolStarted(val name: String, val args: Map<String, Any?>) : AgentEvent()
    /** [display] is what the UI may show: never the raw dump of a rawOutput tool (screen/notification lists). */
    data class ToolFinished(val name: String, val result: ToolResult, val display: String = result.output) : AgentEvent()
    data class Reply(val text: String) : AgentEvent()
    data class NeedsPermission(val need: com.agi.assistant.core.tools.PermissionNeed) : AgentEvent()
    data class Error(val message: String) : AgentEvent()
    /** Turn finished. [elapsedMs] = monotonic wall time from the start of the request to the final reply/error (tools + fallback included). */
    data class Done(val elapsedMs: Long = -1) : AgentEvent()
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
    /** UI tool calls refused because the request was informational (see IntentRouter). */
    var blockedCalls: Int = 0,
    /** Total measured request time (monotonic), set when the turn ends. */
    var elapsedMs: Long = -1,
) {
    override fun toString() = "steps=$steps provider=$providerCalls(${providerMs}ms) fallback=$fallbackCalls tools=$toolCalls(${toolMs}ms) blocked=$blockedCalls fellBack=$fellBack total=${elapsedMs}ms"
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
    private val router: IntentRouter = IntentRouter(),
) {
    /**
     * @param userText the request being served (last user message); drives intent routing. When
     *   null, routing guards are skipped (legacy callers/tests).
     */
    suspend fun run(
        provider: AiProvider,
        fallback: AiProvider?,
        systemPrompt: String,
        toolSpecs: List<ToolSpec>,
        history: History,
        secrets: List<String>,
        runTool: suspend (ToolCall) -> ToolResult,
        onEvent: suspend (AgentEvent) -> Unit,
        userText: String? = null,
    ): TurnTrace {
        val trace = TurnTrace()
        val turnStart = System.nanoTime()
        var active = provider
        val rawTools = toolSpecs.filter { it.rawOutput }.map { it.name }.toSet()
        val route = userText?.let { router.route(it) }
        val rawOutputsThisTurn = mutableListOf<Pair<String, ToolResult>>()   // (tool, result) for rawOutput tools
        var directToolSucceeded = false
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
                    val text = finalAnswer(response.text, rawOutputsThisTurn)
                    history.add(ChatMessage(Role.ASSISTANT, text, latencyMs = Latency.sinceNanos(turnStart)))
                    onEvent(AgentEvent.Reply(text))
                    break
                }

                history.add(ChatMessage(Role.ASSISTANT, response.text.orEmpty(), response.toolCalls))
                response.text?.takeIf { it.isNotBlank() }?.let { onEvent(AgentEvent.Reply(it)) }

                var blocked = false
                for (call in response.toolCalls) {
                    onEvent(AgentEvent.ToolStarted(call.name, call.arguments))
                    val t1 = System.nanoTime()
                    val result = if (userText != null && route != null && !directToolSucceeded && router.isMisrouted(userText, call, toolSpecs)) {
                        // Information/conversation request → UI tool (browser, read_screen) is a misroute: do not execute.
                        trace.blockedCalls++
                        log("blocked ${call.name} for ${route.intent} request (rule ${route.rule})")
                        ToolResult.fail(
                            "Not executed: '${call.name}' is a UI tool and this is an information request. " +
                                (route.tool?.let { "Use $it instead" } ?: "Answer directly") +
                                "; if the needed data is unavailable, tell the user and ask a clarifying question instead of opening a browser.",
                        )
                    } else runTool(call)
                    if (result.success && toolSpecs.any { it.name == call.name && it.intent == ToolIntent.INFORMATION }) directToolSucceeded = true
                    trace.toolCalls++
                    trace.toolMs += (System.nanoTime() - t1) / 1_000_000
                    val display = if (call.name in rawTools && result.success) {
                        rawOutputsThisTurn += call.name to result
                        result.spoken ?: "Done."
                    } else result.output
                    onEvent(AgentEvent.ToolFinished(call.name, result, display))
                    history.add(ChatMessage(Role.TOOL, result.output, toolCallId = call.id, toolName = call.name))
                    if (result.needsPermission != null) {
                        onEvent(AgentEvent.NeedsPermission(result.needsPermission))
                        blocked = true
                        break
                    }
                }
                if (blocked) {
                    val msg = "I need a permission before I can continue. Please grant it and ask me again."
                    history.add(ChatMessage(Role.ASSISTANT, msg, latencyMs = Latency.sinceNanos(turnStart)))
                    onEvent(AgentEvent.Reply(msg))
                    break
                }
            }
        } catch (e: Exception) {
            val safe = Redactor.redact(e.message ?: e.javaClass.simpleName, secrets)
            log("agent error: $safe")
            val msg = "Something went wrong: $safe"
            history.add(ChatMessage(Role.ASSISTANT, msg, latencyMs = Latency.sinceNanos(turnStart)))
            onEvent(AgentEvent.Error(msg))
        }
        trace.elapsedMs = Latency.sinceNanos(turnStart)
        log("turn $trace")
        onEvent(AgentEvent.Done(trace.elapsedMs))
        return trace
    }

    /**
     * Final-answer guard: a raw dump (screen tree, notification list) must never be the reply.
     * If the provider echoed a rawOutput tool's output verbatim (or answered with nothing), replace
     * it with the tool's spoken summary; otherwise keep the model's own wording.
     */
    internal fun finalAnswer(text: String?, raw: List<Pair<String, ToolResult>>): String {
        var t = text?.trim().orEmpty()
        for ((name, r) in raw) {
            val dump = r.output.trim()
            if (dump.length >= 40 && t.contains(dump)) {
                t = t.replace(dump, r.spoken ?: "I looked at the ${name.replace('_', ' ')} result.").trim()
            }
        }
        return t.ifBlank { raw.lastOrNull()?.second?.spoken ?: "Done." }
    }
}
