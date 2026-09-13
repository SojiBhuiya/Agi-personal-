package com.agi.assistant

import com.agi.assistant.core.ai.*
import com.agi.assistant.core.ai.providers.GeminiProvider
import com.agi.assistant.core.ai.providers.OpenAiCompatibleProvider
import com.agi.assistant.core.tools.ParamType
import com.agi.assistant.core.tools.ToolParam
import com.agi.assistant.core.tools.ToolSpec
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * Regression tests for Gemini 3 function calling: the `thoughtSignature` on a functionCall Part
 * must round-trip through ToolCall/ChatMessage and be resent as `thought_signature` on the exact
 * same part in every later request. Reproduces the physical-device failure
 * "HTTP 400 Function call is missing a thought_signature in functionCall parts".
 */
object GeminiSignatureTest {
    private var fails = 0
    private var passed = 0
    private fun check(name: String, cond: Boolean, detail: Any? = "") { if (cond) { passed++; println("  ok   $name") } else { fails++; println("  FAIL $name $detail") } }

    private const val KEY = "AIzaSyFAKE0123456789abcdefghijklmnopqrs"
    private const val SIG1 = "CtQBAVSoXO7_signature_ONE_base64==+/=="
    private const val SIG2 = "CpMCAVSoXO7_signature_TWO_totally_different//=="
    private val cfg = ProviderConfig(ProviderType.GEMINI, "https://generativelanguage.googleapis.com", "gemini-3.1-flash-lite", KEY)
    private val tools = listOf(ToolSpec("volume", "Volume", listOf(ToolParam("level", ParamType.INTEGER, "pct"))), ToolSpec("flashlight", "Torch", listOf(ToolParam("on", ParamType.BOOLEAN, "state"))))

    /** Scripted Gemini server; records every request body for assertions. */
    class FakeGemini(private val script: ArrayDeque<String>, private val status: Int = 200) : HttpTransport {
        val requests = ArrayList<JSONObject>()
        override fun post(url: String, body: String, headers: Map<String, String>, timeoutMs: Int): HttpResponse {
            requests += JSONObject(body)
            return HttpResponse(status, script.removeFirst())
        }
    }

    private fun fcPart(name: String, args: String, sig: String?) =
        """{"functionCall":{"name":"$name","args":$args}${if (sig != null) ""","thoughtSignature":"$sig"""" else ""}}"""
    private fun resp(vararg parts: String) = """{"candidates":[{"content":{"role":"model","parts":[${parts.joinToString(",")}]},"finishReason":"STOP"}],"modelVersion":"gemini-3.1-flash-lite"}"""
    private fun textPart(t: String) = """{"text":"$t"}"""

    private fun fcParts(req: JSONObject): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        val contents = req.getJSONArray("contents")
        for (i in 0 until contents.length()) { val parts = contents.getJSONObject(i).getJSONArray("parts"); for (j in 0 until parts.length()) parts.getJSONObject(j).takeIf { it.has("functionCall") }?.let { out += it } }
        return out
    }

    /** Runs the same loop AssistantAgent uses: complete -> run tools -> feed results -> repeat. */
    private suspend fun agentLoop(provider: AiProvider, user: String, toolOutput: (ToolCall) -> String): Pair<List<ChatMessage>, String?> {
        val history = ArrayList<ChatMessage>(); history += ChatMessage(Role.USER, user)
        var steps = 0
        while (steps++ < 8) {
            val r = provider.complete(AiRequest("sys", history, tools))
            if (!r.hasToolCalls) { history += ChatMessage(Role.ASSISTANT, r.text.orEmpty()); return history to r.text }
            history += ChatMessage(Role.ASSISTANT, r.text.orEmpty(), r.toolCalls)
            for (c in r.toolCalls) history += ChatMessage(Role.TOOL, toolOutput(c), toolCallId = c.id, toolName = c.name)
        }
        return history to null
    }

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        println("Parse functionCall + thoughtSignature:")
        val p = GeminiProvider(cfg)
        val r1 = p.parseResponse(JSONObject(resp(fcPart("volume", """{"level":50}""", SIG1))))
        check("tool call parsed", r1.hasToolCalls && r1.toolCalls[0].name == "volume" && r1.toolCalls[0].arguments["level"] == 50)
        check("signature preserved byte-for-byte", r1.toolCalls[0].providerSignature == SIG1, r1.toolCalls[0].providerSignature)
        check("snake_case response field also accepted", p.parseResponse(JSONObject(resp("""{"functionCall":{"name":"volume","args":{}},"thought_signature":"$SIG2"}"""))).toolCalls[0].providerSignature == SIG2)
        check("no signature -> null (not empty string)", p.parseResponse(JSONObject(resp(fcPart("volume", "{}", null)))).toolCalls[0].providerSignature == null)
        check("text-only response still works", p.parseResponse(JSONObject(resp(textPart("Hello")))).let { it.text == "Hello" && !it.hasToolCalls })
        check("text + call in one response", p.parseResponse(JSONObject(resp(textPart("Sure."), fcPart("volume", "{}", SIG1)))).let { it.text == "Sure." && it.toolCalls[0].providerSignature == SIG1 })

        println("Serialize assistant history with thought_signature:")
        val hist = listOf(
            ChatMessage(Role.USER, "volume 50"),
            ChatMessage(Role.ASSISTANT, "", r1.toolCalls),
            ChatMessage(Role.TOOL, "Media volume is now 50%.", toolCallId = r1.toolCalls[0].id, toolName = "volume"),
        )
        val body = p.buildBody(AiRequest("sys", hist, tools))
        val parts = fcParts(body)
        check("exactly one functionCall part resent", parts.size == 1)
        check("wire field is thought_signature", parts[0].has("thought_signature") && !parts[0].has("thoughtSignature"))
        check("signature identical to the one received", parts[0].getString("thought_signature") == SIG1)
        check("signature sits on the same part as its functionCall", parts[0].getJSONObject("functionCall").getString("name") == "volume")
        val contents = body.getJSONArray("contents")
        check("ordering user -> model(functionCall) -> user(functionResponse)", contents.getJSONObject(0).getString("role") == "user" && contents.getJSONObject(1).getString("role") == "model" && contents.getJSONObject(2).getJSONArray("parts").getJSONObject(0).has("functionResponse"))
        check("functionResponse name matches call", contents.getJSONObject(2).getJSONArray("parts").getJSONObject(0).getJSONObject("functionResponse").getString("name") == "volume")
        check("no signature invented when absent", fcParts(p.buildBody(AiRequest("s", listOf(ChatMessage(Role.ASSISTANT, "", listOf(ToolCall("c", "volume", emptyMap())))), tools)))[0].has("thought_signature").not())

        println("Full loop: Gemini -> tool -> Gemini (second request carries the same signature):")
        val fake = FakeGemini(ArrayDeque(listOf(resp(fcPart("volume", """{"level":50}""", SIG1)), resp(textPart("Volume set to 50%.")))))
        val gp = GeminiProvider(cfg, fake)
        val (h, reply) = agentLoop(gp, "volume 50") { "Media volume is now 50%." }
        check("exactly two requests for one tool call", fake.requests.size == 2, fake.requests.size)
        check("final reply returned (no fallback needed)", reply == "Volume set to 50%.")
        val second = fcParts(fake.requests[1])
        check("second request resends the functionCall with byte-identical signature", second.size == 1 && second[0].getString("thought_signature") == SIG1)
        check("first request had no functionCall parts", fcParts(fake.requests[0]).isEmpty())
        check("history keeps signature on the ToolCall", h.first { it.role == Role.ASSISTANT }.toolCalls[0].providerSignature == SIG1)

        println("Sequential calls with different signatures:")
        val seq = FakeGemini(ArrayDeque(listOf(
            resp(fcPart("volume", """{"level":50}""", SIG1)),
            resp(fcPart("flashlight", """{"on":true}""", SIG2)),
            resp(textPart("Done both.")),
        )))
        val (_, seqReply) = agentLoop(GeminiProvider(cfg, seq), "volume 50 and torch on") { "ok" }
        check("three requests (2 tools + final)", seq.requests.size == 3 && seqReply == "Done both.")
        val third = fcParts(seq.requests[2])
        check("third request carries both calls, each with its own signature, in order", third.size == 2 && third[0].getString("thought_signature") == SIG1 && third[1].getString("thought_signature") == SIG2 && third[0].getJSONObject("functionCall").getString("name") == "volume" && third[1].getJSONObject("functionCall").getString("name") == "flashlight")
        check("second request carried only the first signature", fcParts(seq.requests[1]).let { it.size == 1 && it[0].getString("thought_signature") == SIG1 })

        println("Parallel calls: only the first part has a signature:")
        val par = FakeGemini(ArrayDeque(listOf(
            resp(fcPart("volume", """{"level":50}""", SIG1), fcPart("flashlight", """{"on":true}""", null)),
            resp(textPart("Both done.")),
        )))
        val (ph, pReply) = agentLoop(GeminiProvider(cfg, par), "do both") { "ok" }
        check("two requests, final reply", par.requests.size == 2 && pReply == "Both done.")
        val pp = fcParts(par.requests[1])
        check("both functionCalls resent in one model turn", pp.size == 2 && par.requests[1].getJSONArray("contents").getJSONObject(1).getJSONArray("parts").length() == 2)
        check("signature only on the first part, none invented for the second", pp[0].getString("thought_signature") == SIG1 && !pp[1].has("thought_signature"))
        val respParts = par.requests[1].getJSONArray("contents").getJSONObject(2).getJSONArray("parts")
        check("both functionResponses grouped in one user content, call order kept", respParts.length() == 2 && respParts.getJSONObject(0).getJSONObject("functionResponse").getString("name") == "volume" && respParts.getJSONObject(1).getJSONObject("functionResponse").getString("name") == "flashlight")
        check("parallel history has null signature on second call", ph.first { it.role == Role.ASSISTANT }.toolCalls[1].providerSignature == null)

        println("Persistence (ConversationStore JSON):")
        val msg = ChatMessage(Role.ASSISTANT, "", listOf(ToolCall("c1", "volume", mapOf("level" to 50), SIG1), ToolCall("c2", "flashlight", mapOf("on" to true))))
        val back = ChatMessage.fromJson(JSONObject(msg.toJson().toString()))
        check("signature survives toJson/fromJson", back.toolCalls[0].providerSignature == SIG1 && back.toolCalls[1].providerSignature == null)
        check("old conversation JSON without providerSignature loads", ChatMessage.fromJson(JSONObject("""{"role":"ASSISTANT","content":"","toolCalls":[{"id":"x","name":"volume","arguments":{"level":1}}]}""")).toolCalls[0].providerSignature == null)
        check("old history resent without signatures (no crash, nothing invented)", fcParts(p.buildBody(AiRequest("s", listOf(ChatMessage.fromJson(JSONObject("""{"role":"ASSISTANT","content":"","toolCalls":[{"id":"x","name":"volume","arguments":{}}]}"""))), tools))).let { it.size == 1 && !it[0].has("thought_signature") })

        println("Secrets / logging:")
        check("signature not in endpoint or headers", !gp.endpoint.contains(SIG1) && gp.headers().values.none { it.contains(SIG1) })
        check("headers contain only x-goog-api-key", gp.headers().keys == setOf("x-goog-api-key"))
        check("redactor leaves signature untouched but strips key", Redactor.redact("sig=$SIG1 key=$KEY", cfg.secrets).let { it.contains(SIG1) && !it.contains(KEY) })
        val err = GeminiProvider(cfg, FakeGemini(ArrayDeque(listOf("""{"error":{"code":400,"message":"Function call is missing a thought_signature in functionCall parts. key=$KEY"}}""")), status = 400)).let { prov ->
            runCatching { prov.complete(AiRequest("s", listOf(ChatMessage(Role.USER, "x")), tools)) }.exceptionOrNull()
        }
        check("400 error surfaced as BAD_REQUEST without the key", err is AiProviderException && err.kind == ProviderErrorKind.BAD_REQUEST && !err.message!!.contains(KEY), err)

        println("OpenAI-compatible provider unchanged:")
        val oa = OpenAiCompatibleProvider(ProviderConfig(ProviderType.OPENAI_COMPATIBLE, "https://api.example.com/v1", "m", "sk-abcdefghijklmnop"))
        val ob = oa.buildBody(AiRequest("s", listOf(ChatMessage(Role.ASSISTANT, "", listOf(ToolCall("c1", "volume", mapOf("level" to 50), SIG1)))), tools))
        val tc = ob.getJSONArray("messages").getJSONObject(1).getJSONArray("tool_calls").getJSONObject(0)
        check("openai tool_call shape unchanged, no signature field leaks", tc.getString("id") == "c1" && tc.getJSONObject("function").getString("name") == "volume" && !ob.toString().contains(SIG1))

        println("$passed passed, $fails failed")
        if (fails > 0) System.exit(1)
    }
}
