package com.agi.assistant

import com.agi.assistant.core.ai.*
import com.agi.assistant.core.ai.providers.LocalRuleProvider
import com.agi.assistant.core.tools.*
import kotlinx.coroutines.runBlocking

/**
 * Exercises the agent orchestration logic (plan -> execute tools -> feed results
 * back -> final reply) on the JVM using fake tools. The Android AssistantAgent
 * class is a thin wrapper around the same loop; this test replicates the loop
 * with the real LocalRuleProvider and a real-shaped ToolRegistry contract.
 */
object AgentLoopTest {
    class FakeTool(name: String, private val outcome: (Map<String, Any?>) -> ToolResult) : Tool {
        override val spec = ToolSpec(name, "fake $name")
        val calls = ArrayList<Map<String, Any?>>()
        override suspend fun execute(args: Map<String, Any?>, ctx: ToolContext): ToolResult { calls += args; return outcome(args) }
    }

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        var fails = 0
        fun check(name: String, cond: Boolean, detail: Any? = "") { if (cond) println("  ok   $name") else { fails++; println("  FAIL $name $detail") } }

        val openApp = FakeTool("open_app") { ToolResult.ok("Opened ${it["app"]}.", leftApp = true) }
        val search = FakeTool("web_search") { ToolResult.ok("Searched the web for \"${it["query"]}\".", leftApp = true) }
        val readScreen = FakeTool("read_screen") { ToolResult.ok("Screen content:\nDhaka 31°C Partly cloudy\nHumidity 74%") }
        val screenshot = FakeTool("screenshot") { ToolResult.permission(PermissionNeed(PermissionNeed.Kind.ACCESSIBILITY, "Enable accessibility")) }
        val tools = listOf(openApp, search, readScreen, screenshot).associateBy { it.spec.name }
        val provider: AiProvider = LocalRuleProvider()

        suspend fun run(userText: String): Pair<List<ChatMessage>, String?> {
            val history = ArrayList<ChatMessage>()
            history += ChatMessage(Role.USER, userText)
            var reply: String? = null
            var steps = 0
            while (steps++ < 8) {
                val r = provider.complete(AiRequest("sys", history, tools.values.map { it.spec }))
                if (!r.hasToolCalls) { reply = r.text; history += ChatMessage(Role.ASSISTANT, r.text.orEmpty()); break }
                history += ChatMessage(Role.ASSISTANT, "", r.toolCalls)
                var blocked = false
                for (c in r.toolCalls) {
                    val res = tools[c.name]?.execute(c.arguments, ToolContext(null)) ?: ToolResult.fail("unknown")
                    history += ChatMessage(Role.TOOL, res.output, toolCallId = c.id, toolName = c.name)
                    if (res.needsPermission != null) { blocked = true; break }
                }
                if (blocked) { reply = "NEEDS_PERMISSION"; break }
            }
            return history to reply
        }

        // Multi-step: open Chrome, search, read screen, summarise.
        val (h1, r1) = run("Open Chrome, search for Bangladesh weather, and tell me what you find")
        check("multi-step executes 3 tools in order", openApp.calls.size == 1 && search.calls.size == 1 && readScreen.calls.size == 1)
        check("search query extracted", search.calls[0]["query"] == "Bangladesh weather", search.calls)
        check("final reply summarises tool output", r1?.contains("Dhaka 31°C") == true, r1)
        check("history has tool messages with ids", h1.count { it.role == Role.TOOL } == 3 && h1.filter { it.role == Role.TOOL }.all { it.toolCallId != null })

        // Permission gating stops the loop.
        val (_, r2) = run("take a screenshot")
        check("permission need halts loop", r2 == "NEEDS_PERMISSION")

        // Unknown input -> helpful text, no tools.
        val (_, r3) = run("blorp zzz")
        check("unmapped input yields guidance", r3?.contains("couldn't map") == true, r3)

        // Conversation JSON round-trip (persistence format used by ConversationStore).
        val msg = h1[1]
        val back = ChatMessage.fromJson(msg.toJson())
        check("ChatMessage JSON round-trip keeps tool calls", back.toolCalls.map { it.name } == msg.toolCalls.map { it.name } && back.toolCalls[1].arguments["query"] == "Bangladesh weather")

        println(if (fails == 0) "\nall agent loop tests passed" else "\n$fails failed")
        if (fails > 0) System.exit(1)
    }
}
