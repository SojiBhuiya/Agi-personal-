package com.agi.assistant

import com.agi.assistant.core.agent.*
import com.agi.assistant.core.ai.*
import com.agi.assistant.core.ai.providers.LocalRuleProvider
import com.agi.assistant.core.tools.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.util.TimeZone

/** Response-latency measurement and HH:mm:ss timestamps (pure JVM; the real AgentLoop is driven with scripted providers). */
object LatencyTest {
    private var fails = 0; private var passed = 0
    private fun check(name: String, cond: Boolean, detail: Any? = "") { if (cond) { passed++; println("  ok   $name") } else { fails++; println("  FAIL $name $detail") } }

    private fun text(t: String) = AiResponse(t, emptyList(), "scripted")
    private fun calls(vararg c: ToolCall) = AiResponse(null, c.toList(), "scripted")
    private val specs = listOf(ToolSpec("volume", "v", listOf(ToolParam("level", ParamType.INTEGER, "p"))), ToolSpec("device_info", "d", intent = ToolIntent.INFORMATION))

    private suspend fun turn(provider: AiProvider, fallback: AiProvider?, user: String, tool: suspend (ToolCall) -> ToolResult = { ToolResult.ok("ok") }): Triple<TurnTrace, List<AgentEvent>, RequestFlowTest.MemHistory> {
        val h = RequestFlowTest.MemHistory(); h.add(ChatMessage(Role.USER, user))
        val events = ArrayList<AgentEvent>()
        val trace = AgentLoop(8).run(provider, fallback, "sys", specs, h, emptyList(), tool, { events += it }, userText = user)
        return Triple(trace, events, h)
    }
    private fun done(events: List<AgentEvent>) = events.last() as AgentEvent.Done

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        println("Timestamp format:")
        val t = 1_700_000_000_000L // 2023-11-14T22:13:20Z
        check("HH:mm:ss in UTC", Latency.clock(t, TimeZone.getTimeZone("UTC")) == "22:13:20", Latency.clock(t, TimeZone.getTimeZone("UTC")))
        check("local timezone applied (Asia/Dhaka = UTC+6)", Latency.clock(t, TimeZone.getTimeZone("Asia/Dhaka")) == "04:13:20", Latency.clock(t, TimeZone.getTimeZone("Asia/Dhaka")))
        check("half-hour zone (Asia/Kolkata)", Latency.clock(t, TimeZone.getTimeZone("Asia/Kolkata")) == "03:43:20")
        check("default = device zone", Latency.clock(t) == Latency.clock(t, TimeZone.getDefault()))
        check("always 8 chars with seconds (never HH:mm)", Regex("^\\d{2}:\\d{2}:\\d{2}$").matches(Latency.clock(System.currentTimeMillis())))

        println("Latency formatting:")
        check("0ms", Latency.format(0) == "0ms")
        check("420ms sub-second", Latency.format(420) == "420ms")
        check("999ms still ms", Latency.format(999) == "999ms")
        check("1000ms -> 1.0s", Latency.format(1000) == "1.0s")
        check("3700ms -> 3.7s", Latency.format(3700) == "3.7s")
        check("12345ms -> 12.3s", Latency.format(12345) == "12.3s")
        check("negative clamps to 0ms", Latency.format(-5) == "0ms")
        check("indicator ⚡ 3.7s", Latency.indicator(3700) == "⚡ 3.7s")
        check("failed indicator ⚠️ Failed • 4.2s", Latency.failedIndicator(4200) == "⚠️ Failed • 4.2s")

        println("Monotonic measurement:")
        val n0 = System.nanoTime(); Thread.sleep(30); val ms = Latency.sinceNanos(n0)
        check("sinceNanos measures real elapsed time (>=30ms, <5s)", ms in 30..5000, ms)
        check("sinceNanos is pure nanoTime arithmetic", Latency.sinceNanos(1_000_000_000L, 4_500_000_000L) == 3500L)

        println("AgentLoop attaches measured duration:")
        val slowTool: suspend (ToolCall) -> ToolResult = { delay(120); ToolResult.ok("Volume is 50%.") }
        val p1 = RequestFlowTest.ScriptedProvider(ArrayDeque(listOf(calls(ToolCall("c1", "volume", mapOf("level" to 50))), text("Volume set to 50%."))))
        val (tr1, ev1, h1) = turn(p1, null, "volume 50", slowTool)
        val d1 = done(ev1)
        check("Done carries elapsedMs >= tool time (tool execution included)", d1.elapsedMs >= 120 && d1.elapsedMs >= tr1.toolMs, "elapsed=${d1.elapsedMs} toolMs=${tr1.toolMs}")
        check("TurnTrace.elapsedMs equals Done.elapsedMs", tr1.elapsedMs == d1.elapsedMs)
        check("elapsed covers provider + tool time", d1.elapsedMs >= tr1.providerMs + tr1.toolMs - 1)
        val finals = h1.msgs.filter { it.role == Role.ASSISTANT && it.latencyMs != null }
        check("latency stored only on the final assistant message (not on the tool-call message)", finals.size == 1 && finals[0].content == "Volume set to 50%." && h1.msgs.first { it.toolCalls.isNotEmpty() }.latencyMs == null, h1.msgs.map { it.latencyMs })
        check("stored latency >= tool time, <= total", finals[0].latencyMs!! >= 120 && finals[0].latencyMs!! <= d1.elapsedMs)
        check("Done is the only event carrying elapsed; Reply text is untouched", (ev1.first { it is AgentEvent.Reply } as AgentEvent.Reply).text == "Volume set to 50%." && ev1.count { it is AgentEvent.Done } == 1)
        check("provider request never contains a latency indicator", p1.requests.all { r -> r.messages.none { it.content.contains("⚡") } && !r.systemPrompt.contains("⚡") })

        println("Offline fallback included:")
        val slowFail = object : AiProvider {
            override val id = "slow"; override val displayName = "Slow"; override val isRemote = true
            override suspend fun complete(request: AiRequest): AiResponse { delay(150); throw AiProviderException("timeout", null, ProviderErrorKind.NETWORK) }
        }
        val (tr2, ev2, h2) = turn(slowFail, LocalRuleProvider(), "Hi")
        val d2 = done(ev2)
        check("fell back; elapsed covers failed remote (>=150ms) + fallback", tr2.fellBack && d2.elapsedMs >= 150, "elapsed=${d2.elapsedMs} $tr2")
        check("final fallback reply carries latency", h2.msgs.last().role == Role.ASSISTANT && h2.msgs.last().latencyMs != null && h2.msgs.last().latencyMs!! >= 150)

        println("Error duration:")
        val (_, ev3, h3) = turn(slowFail, null, "Hi")
        val d3 = done(ev3)
        check("error path: Done has elapsed >= 150ms", ev3.any { it is AgentEvent.Error } && d3.elapsedMs >= 150, d3)
        check("stored error message carries latency", h3.msgs.last().latencyMs != null && h3.msgs.last().latencyMs!! >= 150)
        check("failed indicator renders from measured value", Latency.failedIndicator(d3.elapsedMs).startsWith("⚠️ Failed • "))

        println("Persistence / backward compatibility:")
        val old = JSONObject("""{"role":"ASSISTANT","content":"old reply","timestamp":1700000000000}""")
        val m = ChatMessage.fromJson(old)
        check("old message without latencyMs loads with null", m.latencyMs == null && m.content == "old reply" && m.timestamp == 1700000000000L)
        check("old message round-trips without inventing latency", !m.toJson().has("latencyMs"))
        val nm = ChatMessage(Role.ASSISTANT, "new", latencyMs = 3700)
        val back = ChatMessage.fromJson(JSONObject(nm.toJson().toString()))
        check("new message persists latencyMs", back.latencyMs == 3700L)
        check("explicit null latencyMs in JSON -> null", ChatMessage.fromJson(JSONObject("""{"role":"USER","content":"x","latencyMs":null}""")).latencyMs == null)
        check("window compaction keeps latency", HistoryWindow.compact(listOf(ChatMessage(Role.USER, "q"), nm)).last().latencyMs == 3700L)

        println("\nLatencyTest: $passed passed, $fails failed")
        if (fails > 0) System.exit(1)
    }
}
