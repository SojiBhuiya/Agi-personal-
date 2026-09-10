package com.agi.assistant

import com.agi.assistant.core.ai.*
import com.agi.assistant.core.ai.providers.GeminiProvider
import com.agi.assistant.core.ai.providers.OpenAiCompatibleProvider
import com.agi.assistant.core.tools.ParamType
import com.agi.assistant.core.tools.ToolParam
import com.agi.assistant.core.tools.ToolSpec
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File

/**
 * Integration test of the remote provider adapters against a local mock HTTP
 * server (scripts/mock_ai_server.py). Verifies request serialisation (tools,
 * roles, tool results) and response parsing (text + tool calls).
 */
object ProviderWireTest {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val base = args.getOrElse(0) { "http://127.0.0.1:8089" }
        val logFile = File(args.getOrElse(1) { "/tmp/mock_ai_last.json" })
        val tools = listOf(
            ToolSpec("open_app", "Open an app", listOf(ToolParam("app", ParamType.STRING, "name"))),
            ToolSpec("read_screen", "Read screen"),
        )
        var fails = 0
        fun check(name: String, cond: Boolean, detail: String = "") { if (cond) println("  ok   $name") else { fails++; println("  FAIL $name $detail") } }

        // ---- OpenAI-compatible: turn 1 -> tool call
        val oa = OpenAiCompatibleProvider(ProviderConfig(ProviderType.OPENAI_COMPATIBLE, "$base/v1", "test-model", "sk-test"))
        val r1 = oa.complete(AiRequest("sys", listOf(ChatMessage(Role.USER, "open youtube")), tools))
        check("openai: parses tool call", r1.hasToolCalls && r1.toolCalls[0].name == "open_app" && r1.toolCalls[0].arguments["app"] == "YouTube", r1.toString())
        var sent = JSONObject(logFile.readText())
        check("openai: sends bearer key", sent.getJSONObject("headers").optString("Authorization") == "Bearer sk-test")
        check("openai: sends tools schema", sent.getJSONObject("body").getJSONArray("tools").length() == 2 &&
            sent.getJSONObject("body").getJSONArray("tools").getJSONObject(0).getJSONObject("function").getJSONObject("parameters").getJSONObject("properties").has("app"))
        check("openai: system prompt first", sent.getJSONObject("body").getJSONArray("messages").getJSONObject(0).getString("role") == "system")

        // turn 2 -> tool result back, expect final text
        val history = listOf(
            ChatMessage(Role.USER, "open youtube"),
            ChatMessage(Role.ASSISTANT, "", r1.toolCalls),
            ChatMessage(Role.TOOL, "Opened YouTube.", toolCallId = r1.toolCalls[0].id, toolName = "open_app"),
        )
        val r2 = oa.complete(AiRequest("sys", history, tools))
        check("openai: final text", r2.text == "Done, YouTube is open." && !r2.hasToolCalls, r2.toString())
        sent = JSONObject(logFile.readText())
        val msgs = sent.getJSONObject("body").getJSONArray("messages")
        check("openai: tool_calls echoed", msgs.getJSONObject(2).has("tool_calls"))
        check("openai: tool message has id", msgs.getJSONObject(3).getString("role") == "tool" && msgs.getJSONObject(3).getString("tool_call_id") == r1.toolCalls[0].id)

        // ---- Gemini
        val gm = GeminiProvider(ProviderConfig(ProviderType.GEMINI, base, "gemini-test", "gkey"))
        val g1 = gm.complete(AiRequest("sys", listOf(ChatMessage(Role.USER, "open youtube")), tools))
        check("gemini: parses functionCall", g1.hasToolCalls && g1.toolCalls[0].name == "open_app" && g1.toolCalls[0].arguments["app"] == "YouTube", g1.toString())
        sent = JSONObject(logFile.readText())
        check("gemini: key in query", sent.getString("path").contains("key=gkey") && sent.getString("path").contains("gemini-test:generateContent"))
        check("gemini: function_declarations", sent.getJSONObject("body").getJSONArray("tools").getJSONObject(0).getJSONArray("function_declarations").length() == 2)
        check("gemini: system_instruction", sent.getJSONObject("body").has("system_instruction"))
        val g2 = gm.complete(AiRequest("sys", listOf(
            ChatMessage(Role.USER, "open youtube"),
            ChatMessage(Role.ASSISTANT, "", g1.toolCalls),
            ChatMessage(Role.TOOL, "Opened YouTube.", toolCallId = g1.toolCalls[0].id, toolName = "open_app"),
        ), tools))
        check("gemini: final text", g2.text == "Done, YouTube is open.", g2.toString())
        sent = JSONObject(logFile.readText())
        val contents = sent.getJSONObject("body").getJSONArray("contents")
        check("gemini: functionResponse grouped", contents.getJSONObject(2).getJSONArray("parts").getJSONObject(0).has("functionResponse"))

        // ---- Error surface
        val bad = OpenAiCompatibleProvider(ProviderConfig(ProviderType.OPENAI_COMPATIBLE, "$base/bad", "m", ""))
        val err = runCatching { bad.complete(AiRequest("s", listOf(ChatMessage(Role.USER, "x")), emptyList())) }.exceptionOrNull()
        check("error: HTTP 401 becomes AiProviderException with message", err is AiProviderException && err.message!!.contains("401") && err.message!!.contains("Invalid API key"), err.toString())

        println(if (fails == 0) "\nall provider wire tests passed" else "\n$fails failed")
        if (fails > 0) System.exit(1)
    }
}
