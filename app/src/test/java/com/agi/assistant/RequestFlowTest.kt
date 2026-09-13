package com.agi.assistant

import com.agi.assistant.core.agent.*
import com.agi.assistant.core.ai.*
import com.agi.assistant.core.ai.providers.GeminiProvider
import com.agi.assistant.core.ai.providers.LocalRuleProvider
import com.agi.assistant.core.tools.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * Request-flow / latency regression tests for the real [AgentLoop] (the class AssistantAgent
 * runs on the phone). Proves: one provider call for plain text, no tool invocation unless the
 * model asks, exact Gemini→tool→Gemini sequence, fallback only on real failure, thought
 * signatures intact through the loop, old tool output compacted.
 */
object RequestFlowTest {
    private var fails = 0
    private var passed = 0
    private fun check(name: String, cond: Boolean, detail: Any? = "") { if (cond) { passed++; println("  ok   $name") } else { fails++; println("  FAIL $name $detail") } }

    class MemHistory(initial: List<ChatMessage> = emptyList()) : History {
        val msgs = ArrayList(initial)
        override fun window() = HistoryWindow.compact(msgs.toList())
        override fun add(message: ChatMessage) { msgs += message }
    }

    /** Scripted provider: each complete() pops one response; throws when the script says so. */
    class ScriptedProvider(private val script: ArrayDeque<Any>, override val isRemote: Boolean = true) : AiProvider {
        override val id = "scripted"; override val displayName = "Scripted"
        val requests = ArrayList<AiRequest>()
        override suspend fun complete(request: AiRequest): AiResponse {
            requests += request
            val next = script.removeFirstOrNull() ?: error("no scripted response left")
            if (next is Throwable) throw next
            return next as AiResponse
        }
    }

    private fun text(t: String) = AiResponse(t, emptyList(), "scripted")
    private fun calls(vararg c: ToolCall) = AiResponse(null, c.toList(), "scripted")
    private val specs = listOf(ToolSpec("volume", "v", listOf(ToolParam("level", ParamType.INTEGER, "p"))), ToolSpec("read_screen", "r"), ToolSpec("device_info", "d"), ToolSpec("web_search", "w", listOf(ToolParam("query", ParamType.STRING, "q"))))

    private suspend fun run(provider: AiProvider, fallback: AiProvider?, user: String, history: MemHistory = MemHistory(), tool: suspend (ToolCall) -> ToolResult = { ToolResult.ok("ok") }): Triple<TurnTrace, List<AgentEvent>, MemHistory> {
        history.add(ChatMessage(Role.USER, user))
        val events = ArrayList<AgentEvent>()
        val toolInvocations = ArrayList<String>()
        val trace = AgentLoop(8).run(provider, fallback, "sys", specs, history, listOf("secret-key-value"), { c -> toolInvocations += c.name; tool(c) }, { events += it }, userText = user)
        return Triple(trace, events, history)
    }

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        println("Simple text-only requests:")
        for (q in listOf("Hi", "How are you?", "What is your name?", "2+2?", "কেমন আছো?")) {
            val p = ScriptedProvider(ArrayDeque(listOf(text("Answer to $q"))))
            val (trace, events, h) = run(p, LocalRuleProvider(), q)
            check("\"$q\": exactly one provider call, zero tools, no fallback", trace.providerCalls == 1 && trace.toolCalls == 0 && trace.fallbackCalls == 0 && !trace.fellBack && trace.steps == 1, trace)
            check("\"$q\": events = Thinking, Reply, Done only", events.map { it::class.simpleName } == listOf("Thinking", "Reply", "Done"), events.map { it::class.simpleName })
            check("\"$q\": reply text verbatim + stored", (events[1] as AgentEvent.Reply).text == "Answer to $q" && h.msgs.last().content == "Answer to $q")
        }

        println("Tool request – only the required provider/tool/provider flow:")
        val tp = ScriptedProvider(ArrayDeque(listOf(calls(ToolCall("c1", "volume", mapOf("level" to 50), "SIG-A")), text("Volume is 50%."))))
        val invoked = ArrayList<String>()
        val (tt, te, th) = run(tp, LocalRuleProvider(), "volume 50") { c -> invoked += c.name; ToolResult.ok("Media volume is now 50%.") }
        check("provider → tool → provider: 2 provider calls, 1 tool", tt.providerCalls == 2 && tt.toolCalls == 1 && tt.steps == 2 && !tt.fellBack, tt)
        check("only the requested tool ran", invoked == listOf("volume"))
        check("second request history: user, assistant(call), tool", tp.requests[1].messages.map { it.role } == listOf(Role.USER, Role.ASSISTANT, Role.TOOL))
        check("thought signature preserved into second request", tp.requests[1].messages[1].toolCalls[0].providerSignature == "SIG-A")
        check("tool result paired by id", tp.requests[1].messages[2].toolCallId == "c1" && tp.requests[1].messages[2].toolName == "volume")
        check("event order", te.map { it::class.simpleName } == listOf("Thinking", "ToolStarted", "ToolFinished", "Thinking", "Reply", "Done"), te.map { it::class.simpleName })
        check("no redundant provider call after final answer", tp.requests.size == 2)

        println("Parallel tool calls in one step:")
        val pp = ScriptedProvider(ArrayDeque(listOf(calls(ToolCall("a", "volume", mapOf("level" to 1), "S1"), ToolCall("b", "device_info", emptyMap(), null)), text("done"))))
        val (ptr, _, _) = run(pp, null, "both")
        check("2 provider calls, 2 tools, one step for both", ptr.providerCalls == 2 && ptr.toolCalls == 2 && ptr.steps == 2)
        check("signatures kept per call (S1, null)", pp.requests[1].messages[1].toolCalls.map { it.providerSignature } == listOf("S1", null))

        println("Sequential tool calls (Gemini → tool → Gemini → tool → Gemini):")
        val sp = ScriptedProvider(ArrayDeque(listOf(calls(ToolCall("a", "volume", emptyMap(), "S1")), calls(ToolCall("b", "read_screen", emptyMap(), "S2")), text("done"))))
        val (str, _, _) = run(sp, null, "x")
        check("3 provider calls, 2 tools", str.providerCalls == 3 && str.toolCalls == 2)
        check("third request carries both signatures in order", sp.requests[2].messages.filter { it.role == Role.ASSISTANT }.flatMap { it.toolCalls }.map { it.providerSignature } == listOf("S1", "S2"))

        println("Fallback only on actual provider failure:")
        val okp = ScriptedProvider(ArrayDeque(listOf(text("fine"))))
        val fb = ScriptedProvider(ArrayDeque(listOf(text("fallback answer"))), isRemote = false)
        val (t1, _, _) = run(okp, fb, "Hi")
        check("success → fallback never called", t1.fallbackCalls == 0 && fb.requests.isEmpty() && !t1.fellBack)
        val badp = ScriptedProvider(ArrayDeque(listOf(AiProviderException("HTTP 401 from provider", kind = ProviderErrorKind.AUTH))))
        val fb2 = ScriptedProvider(ArrayDeque(listOf(text("fallback answer"))), isRemote = false)
        val (t2, e2, _) = run(badp, fb2, "Hi")
        check("failure → exactly one fallback call, reply from fallback", t2.providerCalls == 1 && t2.fallbackCalls == 1 && t2.fellBack && e2.any { it is AgentEvent.Reply && it.text == "fallback answer" }, t2)
        check("fallback error event has no secret", e2.filterIsInstance<AgentEvent.Error>().none { it.message.contains("secret-key-value") })
        val badNoFb = ScriptedProvider(ArrayDeque(listOf(AiProviderException("HTTP 500 from provider", kind = ProviderErrorKind.SERVER))))
        val (t3, e3, h3) = run(badNoFb, null, "Hi")
        check("failure without fallback → error event, no crash, stored", t3.providerCalls == 1 && e3.any { it is AgentEvent.Error } && e3.last() is AgentEvent.Done && h3.msgs.last().role == Role.ASSISTANT)
        val fbFails = ScriptedProvider(ArrayDeque(listOf(RuntimeException("offline broke"))), isRemote = false)
        val (t4, e4, _) = run(ScriptedProvider(ArrayDeque(listOf(AiProviderException("x")))), fbFails, "Hi")
        check("fallback itself failing does not loop", t4.fallbackCalls == 1 && e4.last() is AgentEvent.Done)
        val toolThenFail = ScriptedProvider(ArrayDeque(listOf(calls(ToolCall("a", "volume", emptyMap(), "S1")), AiProviderException("HTTP 400 from provider", kind = ProviderErrorKind.BAD_REQUEST))))
        val (t5, _, _) = run(toolThenFail, LocalRuleProvider(), "volume 50")
        check("failure on 2nd step falls back once, no repeat of remote", t5.providerCalls == 2 && t5.fellBack && t5.fallbackCalls >= 1)

        println("Loop bounds:")
        val endless = ScriptedProvider(ArrayDeque(List(20) { calls(ToolCall("c$it", "volume", emptyMap())) }))
        val (tb, _, _) = run(endless, null, "loop")
        check("step budget caps provider calls at 8", tb.providerCalls == 8 && tb.toolCalls == 8)
        val perm = ScriptedProvider(ArrayDeque(listOf(calls(ToolCall("c", "read_screen", emptyMap())), text("never"))))
        val (tpm, epm, _) = run(perm, null, "screen") { ToolResult.permission(PermissionNeed(PermissionNeed.Kind.ACCESSIBILITY, "enable")) }
        check("permission need stops the loop without a second provider call", tpm.providerCalls == 1 && epm.any { it is AgentEvent.NeedsPermission } && perm.requests.size == 1)

        println("History window compaction:")
        val big = "x".repeat(5000)
        val hist = MemHistory(listOf(
            ChatMessage(Role.USER, "read screen"), ChatMessage(Role.ASSISTANT, "", listOf(ToolCall("a", "read_screen", emptyMap(), "S1"))), ChatMessage(Role.TOOL, big, toolCallId = "a", toolName = "read_screen"), ChatMessage(Role.ASSISTANT, "It shows weather."),
        ))
        val cp = ScriptedProvider(ArrayDeque(listOf(text("hello"))))
        run(cp, null, "Hi", hist)
        val sent = cp.requests[0].messages
        check("old tool output shortened for the next turn", sent[2].content.length < 700 && sent[2].content.endsWith("shortened]"), sent[2].content.length)
        check("old assistant call + signature untouched", sent[1].toolCalls[0].providerSignature == "S1")
        val cur = HistoryWindow.compact(listOf(ChatMessage(Role.USER, "read"), ChatMessage(Role.ASSISTANT, "", listOf(ToolCall("a", "read_screen", emptyMap(), "S1"))), ChatMessage(Role.TOOL, big, toolCallId = "a")))
        check("current turn's tool output never shortened (Gemini strict same-turn)", cur[2].content.length == 5000)
        check("payload for 'Hi' shrinks", ChatMessage(Role.TOOL, big).toJson().toString().length > sent[2].toJson().toString().length)

        println("Gemini provider request-flow details:")
        val g3 = GeminiProvider(ProviderConfig(ProviderType.GEMINI, "https://generativelanguage.googleapis.com", "gemini-3.1-flash-lite", "AIzaSyFAKE0123456789abcdefghijklmnop"))
        val b3 = g3.buildBody(AiRequest("sys", listOf(ChatMessage(Role.USER, "Hi")), specs))
        check("gemini-3: no explicit temperature (model default)", !b3.has("generationConfig"))
        val g2 = GeminiProvider(ProviderConfig(ProviderType.GEMINI, "https://generativelanguage.googleapis.com", "gemini-2.0-flash", "AIzaSyFAKE0123456789abcdefghijklmnop"))
        check("gemini-2.x: temperature still sent", g2.buildBody(AiRequest("sys", listOf(ChatMessage(Role.USER, "Hi")), specs)).getJSONObject("generationConfig").getDouble("temperature") == 0.2)
        val sig = "SIG-bytes==//"
        val body = g3.buildBody(AiRequest("sys", listOf(ChatMessage(Role.USER, "v"), ChatMessage(Role.ASSISTANT, "", listOf(ToolCall("c", "volume", mapOf("level" to 50), sig))), ChatMessage(Role.TOOL, "ok", toolCallId = "c", toolName = "volume")), specs))
        val part = body.getJSONArray("contents").getJSONObject(1).getJSONArray("parts").getJSONObject(0)
        check("thought_signature still emitted byte-for-byte", part.getString("thought_signature") == sig)
        check("one request = one HTTP body (tools declared once)", body.getJSONArray("tools").length() == 1 && body.getJSONArray("tools").getJSONObject(0).getJSONArray("function_declarations").length() == specs.size)

        println("Trace safety:")
        check("trace has no text fields", TurnTrace::class.java.declaredFields.none { it.type == String::class.java })
        check("trace toString numeric only", !TurnTrace(1, 0, 1, 10, 5, false, 2).toString().contains("secret"))

        println("$passed passed, $fails failed")
        if (fails > 0) System.exit(1)
    }
}
