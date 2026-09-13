package com.agi.assistant

import com.agi.assistant.core.ai.*
import com.agi.assistant.core.ai.providers.LocalRuleProvider
import com.agi.assistant.core.ai.providers.OpenAiCompatibleProvider
import com.agi.assistant.core.tools.ParamType
import com.agi.assistant.core.tools.ToolParam
import com.agi.assistant.core.tools.ToolSpec
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Deterministic (no network) tests for the online AI brain: configuration validation, URL and
 * request construction, model handling, response parsing (EN + BN), every failure class,
 * offline fallback decision and API-key redaction. Uses a fake [HttpTransport].
 */
object OnlineProviderTest {
    private var fails = 0
    private var passed = 0
    private fun check(name: String, cond: Boolean, detail: Any? = "") { if (cond) { passed++; println("  ok   $name") } else { fails++; println("  FAIL $name $detail") } }

    private const val KEY = "sk-secret-1234567890abcdef"

    /** Records the request and answers with a scripted response or throws. */
    class FakeTransport(private val answer: (String) -> HttpResponse) : HttpTransport {
        var lastUrl = ""; var lastBody = JSONObject(); var lastHeaders = emptyMap<String, String>(); var lastTimeout = 0; var calls = 0
        override fun post(url: String, body: String, headers: Map<String, String>, timeoutMs: Int): HttpResponse {
            calls++; lastUrl = url; lastBody = JSONObject(body); lastHeaders = headers; lastTimeout = timeoutMs
            return answer(body)
        }
    }

    private fun ok(content: String?, model: String = "served-model") = HttpResponse(200,
        JSONObject().put("model", model).put("choices", org.json.JSONArray().put(JSONObject().put("message",
            JSONObject().put("role", "assistant").put("content", content ?: JSONObject.NULL)))).toString())

    private fun cfg(base: String = "https://api.example.com/v1", model: String = "test-model", key: String = KEY, type: ProviderType = ProviderType.OPENAI_COMPATIBLE) =
        ProviderConfig(type, base, model, key, timeoutMs = 12_345)

    private fun provider(t: HttpTransport, c: ProviderConfig = cfg()) = OpenAiCompatibleProvider(c, t)
    private fun req(text: String, tools: List<ToolSpec> = emptyList()) = AiRequest("You are a test.", listOf(ChatMessage(Role.USER, text)), tools)

    private suspend fun failWith(t: HttpTransport, c: ProviderConfig = cfg()): AiProviderException? =
        runCatching { provider(t, c).complete(req("hi")) }.exceptionOrNull() as? AiProviderException

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        println("Provider configuration:")
        check("valid config passes", cfg().validationError() == null)
        check("blank base url rejected", cfg(base = "").validationError() == "Base URL is required.")
        check("bad scheme rejected", cfg(base = "ftp://x").validationError()!!.contains("https://"))
        check("blank model rejected", cfg(model = "").validationError() == "Model name is required.")
        check("openai-compatible without key allowed (Ollama)", cfg(key = "").validationError() == null)
        check("gemini requires key", cfg(key = "", type = ProviderType.GEMINI).validationError()!!.contains("API key"))
        check("local never invalid", ProviderConfig(ProviderType.LOCAL, "", "", "").validationError() == null)
        check("secrets list holds key only when set", cfg().secrets == listOf(KEY) && cfg(key = "").secrets.isEmpty())
        check("factory picks OpenAI-compatible", AiProviderFactory.create(cfg()) is OpenAiCompatibleProvider && AiProviderFactory.create(cfg()).isRemote)
        check("factory picks local", AiProviderFactory.create(ProviderConfig(ProviderType.LOCAL, "", "", "")) is LocalRuleProvider && !AiProviderFactory.fallback.isRemote)
        check("validation error text never contains key", listOf(cfg(base = ""), cfg(model = "")).none { it.validationError()!!.contains(KEY) })

        println("URL construction:")
        check("base + /chat/completions", OpenAiEndpoint.chatCompletions("https://api.groq.com/openai/v1") == "https://api.groq.com/openai/v1/chat/completions")
        check("trailing slash tolerated", OpenAiEndpoint.chatCompletions("https://api.openai.com/v1/") == "https://api.openai.com/v1/chat/completions")
        check("full endpoint not doubled", OpenAiEndpoint.chatCompletions("http://192.168.1.10:11434/v1/chat/completions") == "http://192.168.1.10:11434/v1/chat/completions")
        check("no /v1 auto-added (base used as configured)", OpenAiEndpoint.chatCompletions("http://localhost:8080") == "http://localhost:8080/chat/completions")
        check("whitespace trimmed", OpenAiEndpoint.chatCompletions("  https://x.y/v1 ") == "https://x.y/v1/chat/completions")

        println("Request construction:")
        val t = FakeTransport { ok("Hello!") }
        val tools = listOf(ToolSpec("volume", "Volume", listOf(ToolParam("level", ParamType.INTEGER, "pct"))))
        val r = provider(t).complete(req("hello", tools))
        check("configured URL actually used", t.lastUrl == "https://api.example.com/v1/chat/completions", t.lastUrl)
        check("configured model actually sent", t.lastBody.getString("model") == "test-model")
        check("configured timeout used", t.lastTimeout == 12_345)
        check("bearer header carries key", t.lastHeaders["Authorization"] == "Bearer $KEY")
        check("no key when blank", FakeTransport { ok("x") }.also { provider(it, cfg(key = "")).complete(req("a")) }.lastHeaders["Authorization"] == null)
        check("system prompt first, user second", t.lastBody.getJSONArray("messages").let { it.getJSONObject(0).getString("role") == "system" && it.getJSONObject(1).getString("content") == "hello" })
        check("stream=false", t.lastBody.getBoolean("stream") == false)
        check("tools + tool_choice sent", t.lastBody.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getString("name") == "volume" && t.lastBody.getString("tool_choice") == "auto")
        check("no tools -> no tools field", FakeTransport { ok("x") }.also { provider(it).complete(req("a")) }.lastBody.has("tools").not())
        check("temperature forwarded", t.lastBody.getDouble("temperature") == 0.2)
        check("response text + served model", r.text == "Hello!" && r.model == "served-model" && r.providerId == "openai_compatible")
        check("model falls back to configured when absent", provider(FakeTransport { HttpResponse(200, """{"choices":[{"message":{"content":"a"}}]}""") }).complete(req("a")).model == "test-model")

        println("Bengali + English:")
        val bn = "ভলিউম ৫০ করো এবং ফ্ল্যাশলাইট চালু করো"
        val tb = FakeTransport { ok("ঠিক আছে, ভলিউম ৫০% করা হয়েছে। Done!") }
        val rb = provider(tb).complete(AiRequest("তুমি একজন সহকারী।", listOf(ChatMessage(Role.USER, bn)), emptyList()))
        check("bengali user text sent intact", tb.lastBody.getJSONArray("messages").getJSONObject(1).getString("content") == bn)
        check("bengali system prompt intact", tb.lastBody.getJSONArray("messages").getJSONObject(0).getString("content") == "তুমি একজন সহকারী।")
        check("bengali reply parsed intact", rb.text == "ঠিক আছে, ভলিউম ৫০% করা হয়েছে। Done!")
        check("utf-8 round trip bytes", String(bn.toByteArray(Charsets.UTF_8), Charsets.UTF_8) == bn)
        check("content as array of parts", provider(FakeTransport { HttpResponse(200, """{"choices":[{"message":{"content":[{"type":"text","text":"হ্যালো "},{"type":"text","text":"world"}]}}]}""") }).complete(req("a")).text == "হ্যালো world")

        println("Tool-call parsing:")
        val tc = provider(FakeTransport { HttpResponse(200, """{"choices":[{"message":{"content":null,"tool_calls":[{"id":"c1","type":"function","function":{"name":"volume","arguments":"{\"action\":\"set\",\"level\":50}"}}]}}]}""") }).complete(req("volume 50"))
        check("tool call parsed", tc.hasToolCalls && tc.toolCalls[0].name == "volume" && tc.toolCalls[0].arguments["level"] == 50 && tc.toolCalls[0].id == "c1" && tc.text == null)
        check("bad tool args -> empty map, no crash", provider(FakeTransport { HttpResponse(200, """{"choices":[{"message":{"tool_calls":[{"function":{"name":"x","arguments":"not json"}}]}}]}""") }).complete(req("a")).toolCalls[0].arguments.isEmpty())

        println("HTTP failures:")
        suspend fun http(code: Int, body: String = """{"error":{"message":"detail for $code"}}""") = failWith(FakeTransport { HttpResponse(code, body) })
        check("400 -> BAD_REQUEST", http(400)?.kind == ProviderErrorKind.BAD_REQUEST && http(400)!!.httpStatus == 400)
        check("401 -> AUTH", http(401)?.kind == ProviderErrorKind.AUTH)
        check("403 -> AUTH", http(403)?.kind == ProviderErrorKind.AUTH)
        check("404 -> NOT_FOUND", http(404)?.kind == ProviderErrorKind.NOT_FOUND)
        check("429 -> RATE_LIMIT", http(429)?.kind == ProviderErrorKind.RATE_LIMIT)
        check("500 -> SERVER", http(500)?.kind == ProviderErrorKind.SERVER && http(503)?.kind == ProviderErrorKind.SERVER)
        check("error detail extracted", http(401)!!.message!!.contains("detail for 401"))
        check("non-json error body tolerated", http(502, "<html>Bad gateway</html>")!!.message!!.contains("Bad gateway"))
        check("string error field", http(400, """{"error":"model not found"}""")!!.message!!.contains("model not found"))
        check("user message for each kind non-empty", ProviderErrorKind.values().all { it.userMessage.isNotBlank() })

        println("Transport / body failures:")
        check("timeout -> TIMEOUT", failWith(FakeTransport { throw SocketTimeoutException("Read timed out") })?.kind == ProviderErrorKind.TIMEOUT)
        check("no internet -> NETWORK", failWith(FakeTransport { throw UnknownHostException("api.example.com") })?.kind == ProviderErrorKind.NETWORK)
        check("invalid JSON -> MALFORMED", failWith(FakeTransport { HttpResponse(200, "<html>not json") })?.kind == ProviderErrorKind.MALFORMED)
        check("empty body -> EMPTY", failWith(FakeTransport { HttpResponse(200, "") })?.kind == ProviderErrorKind.EMPTY)
        check("no choices -> MALFORMED", failWith(FakeTransport { HttpResponse(200, """{"id":"x"}""") })?.kind == ProviderErrorKind.MALFORMED)
        check("empty message -> EMPTY", failWith(FakeTransport { HttpResponse(200, """{"choices":[{"message":{"content":""}}]}""") })?.kind == ProviderErrorKind.EMPTY)
        check("invalid config -> CONFIG before any network call", FakeTransport { ok("x") }.let { ft -> failWith(ft, cfg(base = ""))?.kind == ProviderErrorKind.CONFIG && ft.calls == 0 })
        check("all failures are AiProviderException (no crash)", listOf(http(500), failWith(FakeTransport { throw RuntimeException("boom") })).all { it != null })

        println("Offline fallback (agent decision):")
        // Same decision as AssistantAgent: remote provider failed + fallbackToLocal -> LocalRuleProvider answers.
        suspend fun runWithFallback(fallbackEnabled: Boolean, remote: AiProvider): Pair<AiResponse?, String?> {
            var p: AiProvider = remote
            var err: String? = null
            repeat(2) {
                try { return p.complete(req("volume up")) to err } catch (e: Exception) {
                    err = ProviderErrors.describe(e, listOf(KEY))
                    if (p.isRemote && fallbackEnabled) p = AiProviderFactory.fallback else return null to err
                }
            }
            return null to err
        }
        val (fbResp, fbErr) = runWithFallback(true, provider(FakeTransport { HttpResponse(401, """{"error":{"message":"Invalid API key $KEY"}}""") }))
        check("invalid key + fallback on -> offline planner handles request", fbResp != null && fbResp.providerId == "local_rules" && fbResp.hasToolCalls, fbResp)
        check("fallback reason has no key", fbErr != null && !fbErr.contains(KEY) && fbErr.contains("401"), fbErr)
        check("network failure + fallback on -> offline", runWithFallback(true, provider(FakeTransport { throw UnknownHostException("x") })).first?.providerId == "local_rules")
        check("timeout + fallback on -> offline", runWithFallback(true, provider(FakeTransport { throw SocketTimeoutException() })).first?.providerId == "local_rules")
        check("malformed + fallback on -> offline", runWithFallback(true, provider(FakeTransport { HttpResponse(200, "garbage") })).first?.providerId == "local_rules")
        check("fallback off -> error surfaced, no crash", runWithFallback(false, provider(FakeTransport { HttpResponse(500, "") })).let { it.first == null && it.second!!.contains("500") })
        check("offline provider never falls back (isRemote=false)", !LocalRuleProvider().isRemote)

        println("Test connection:")
        val okT = ConnectionTester.test(cfg()) { OpenAiCompatibleProvider(it, FakeTransport { ok("OK") }) }
        check("success message", okT.ok && okT.message.startsWith("✓") && okT.message.contains("OK") && okT.message.contains("served-model"), okT)
        val badT = ConnectionTester.test(cfg()) { OpenAiCompatibleProvider(it, FakeTransport { HttpResponse(401, """{"error":{"message":"bad key $KEY"}}""") }) }
        check("failure message classified and redacted", !badT.ok && badT.kind == ProviderErrorKind.AUTH && badT.message.startsWith("✗") && !badT.message.contains(KEY), badT)
        check("config error without network", ConnectionTester.test(cfg(model = "")) { error("must not be called") }.let { !it.ok && it.kind == ProviderErrorKind.CONFIG })
        check("local short-circuits", ConnectionTester.test(ProviderConfig(ProviderType.LOCAL, "", "", "")) { error("no") }.ok)
        check("timeout reported", ConnectionTester.test(cfg()) { OpenAiCompatibleProvider(it, FakeTransport { throw SocketTimeoutException("t") }) }.let { !it.ok && it.kind == ProviderErrorKind.TIMEOUT })

        println("Redaction / security:")
        check("exact key removed", !Redactor.redact("Authorization: Bearer $KEY failed", listOf(KEY)).contains(KEY))
        check("bearer pattern removed without secrets list", Redactor.redact("Bearer sk-abcdefghijklmnop").let { !it.contains("abcdefghijk") && it.contains("[REDACTED]") })
        check("query key removed", !Redactor.redact("https://g/api?key=AIzaSyXXXXXXXXXXXXXXXXXXXXXXXX").contains("AIza"))
        check("key-looking token removed", !Redactor.redact("used gsk_ABCDEFGHIJKLMNOPQRSTUV").contains("ABCDEFGHIJKL"))
        check("plain text untouched", Redactor.redact("ভলিউম ৫০ করা হয়েছে, HTTP 401") == "ভলিউম ৫০ করা হয়েছে, HTTP 401")
        val leakT = FakeTransport { HttpResponse(500, """{"error":{"message":"upstream rejected key $KEY"}}""") }
        val leak = failWith(leakT)!!
        check("http error message never contains key", !leak.message!!.contains(KEY) && !ProviderErrors.describe(leak, listOf(KEY)).contains(KEY))
        check("transport exception message redacted", !failWith(FakeTransport { throw RuntimeException("dial $KEY") })!!.message!!.contains(KEY))
        check("malformed body snippet redacted", !failWith(FakeTransport { HttpResponse(200, "oops $KEY") })!!.message!!.contains(KEY))
        check("key never in URL", !leakT.lastUrl.contains(KEY))
        check("describe() wraps unknown throwables safely", ProviderErrors.describe(IllegalStateException("x $KEY"), listOf(KEY)).let { it.isNotBlank() && !it.contains(KEY) })

        println("$passed passed, $fails failed")
        if (fails > 0) System.exit(1)
    }
}
