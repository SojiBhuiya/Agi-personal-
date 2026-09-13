package com.agi.assistant

import com.agi.assistant.core.ai.*
import com.agi.assistant.core.ai.providers.GeminiProvider
import com.agi.assistant.core.ai.providers.OpenAiCompatibleProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.net.UnknownHostException

/**
 * Deterministic tests for the simple API setup flow (SimpleSetup + SimpleProvider + persistence
 * contract + Gemini migration). No network: providers get a fake HttpTransport.
 */
object SimpleSetupTest {
    private var fails = 0
    private var passed = 0
    private fun check(name: String, cond: Boolean, detail: Any? = "") { if (cond) { passed++; println("  ok   $name") } else { fails++; println("  FAIL $name $detail") } }

    private const val KEY = "AIzaSyFAKEKEY1234567890abcdefghijklmn"
    private const val G_BASE = "https://generativelanguage.googleapis.com"

    /** In-memory stand-in for SecureSettings. Records writes so "saved only on success" is provable. */
    class FakeStore(
        override var providerType: ProviderType = ProviderType.LOCAL,
        override var baseUrl: String = "",
        override var model: String = "",
        override var apiName: String = "",
    ) : ProviderSettingsStore {
        var encryptedBlob: String? = null; var writes = 0
        override var apiKey: String
            get() = encryptedBlob?.removePrefix("enc:")?.reversed() ?: ""
            set(v) { writes++; encryptedBlob = if (v.isBlank()) null else "enc:" + v.reversed() } // "encryption" stand-in
    }

    class FakeTransport(private val answer: (String) -> HttpResponse) : HttpTransport {
        var lastUrl = ""; var lastBody = ""; var lastHeaders = emptyMap<String, String>(); var calls = 0
        override fun post(url: String, body: String, headers: Map<String, String>, timeoutMs: Int): HttpResponse {
            calls++; lastUrl = url; lastBody = body; lastHeaders = headers; return answer(body)
        }
    }

    private fun geminiOk(text: String = "OK") = HttpResponse(200, """{"candidates":[{"content":{"role":"model","parts":[{"text":"$text"}]}}]}""")
    private fun input(key: String = KEY, name: String = "My Gemini", p: SimpleProvider = SimpleProvider.GEMINI) = SimpleSetupInput(p, name, key)
    private fun factory(t: HttpTransport): (ProviderConfig) -> AiProvider = { c -> if (c.type == ProviderType.GEMINI) GeminiProvider(c, t) else OpenAiCompatibleProvider(c, t) }

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        println("Simple Gemini configuration uses internal defaults:")
        val c = SimpleSetup.toConfig(input())
        check("type GEMINI", c.type == ProviderType.GEMINI)
        check("base url", c.baseUrl == G_BASE)
        check("model gemini-2.5-flash-lite", c.model == "gemini-2.5-flash-lite" && c.model == GeminiModels.DEFAULT)
        check("key trimmed into config", c.apiKey == KEY)
        check("config valid without any technical input", c.validationError() == null)
        check("simple config has no api-name field at all", ProviderConfig::class.java.declaredFields.none { it.name.contains("apiName", true) })
        check("offline choice -> LOCAL without key", SimpleSetup.toConfig(input(key = "", p = SimpleProvider.OFFLINE)).let { it.type == ProviderType.LOCAL && it.validationError() == null })
        check("forConfig maps stored types", SimpleProvider.forConfig(c) == SimpleProvider.GEMINI && SimpleProvider.forConfig(ProviderConfig(ProviderType.LOCAL, "", "", "")) == SimpleProvider.OFFLINE && SimpleProvider.forConfig(ProviderConfig(ProviderType.OPENAI_COMPATIBLE, "https://x/v1", "m", "k")) == null)

        println("Gemini endpoint + auth header:")
        val gp = GeminiProvider(c)
        check("endpoint = /v1beta/models/{model}:generateContent", gp.endpoint == "$G_BASE/v1beta/models/gemini-2.5-flash-lite:generateContent", gp.endpoint)
        check("endpoint normalises models/ prefix", GeminiProvider(c.copy(model = "models/gemini-2.5-flash")).endpoint.endsWith("/models/gemini-2.5-flash:generateContent"))
        check("x-goog-api-key header carries key", gp.headers() == mapOf("x-goog-api-key" to KEY))
        check("no key in endpoint", !gp.endpoint.contains(KEY))

        println("API Name is local-only:")
        val t = FakeTransport { geminiOk() }
        val st = FakeStore()
        val out = SimpleSetup.testAndSave(input(name = "My Secret Label 7731"), st, factory(t))
        check("request happened", t.calls == 1 && t.lastUrl == gp.endpoint, t.lastUrl)
        check("api name not in URL/body/headers", !t.lastUrl.contains("7731") && !t.lastBody.contains("My Secret Label") && t.lastHeaders.values.none { it.contains("7731") })
        check("only x-goog-api-key header sent by provider", t.lastHeaders.keys == setOf("x-goog-api-key"))
        check("api name persisted locally", st.apiName == "My Secret Label 7731")
        check("outcome success message uses label", out.saved && out.message.contains("My Secret Label 7731") && out.message.startsWith("✓"), out.message)

        println("Blank / bad key validation (no network):")
        val t0 = FakeTransport { geminiOk() }
        val s0 = FakeStore()
        val blank = SimpleSetup.testAndSave(input(key = "   "), s0, factory(t0))
        check("blank key rejected with friendly text", !blank.saved && blank.message.contains("paste your Google Gemini API key"), blank.message)
        check("no network call, nothing saved", t0.calls == 0 && s0.writes == 0 && s0.providerType == ProviderType.LOCAL)
        check("short key rejected", SimpleSetup.validate(input(key = "abc")) != null)
        check("key with spaces rejected", SimpleSetup.validate(input(key = "AIza abcdefghijklmnop")) != null)
        check("offline needs no key", SimpleSetup.validate(input(key = "", p = SimpleProvider.OFFLINE)) == null)

        println("Test & Save success path:")
        val t1 = FakeTransport { geminiOk("OK") }
        val s1 = FakeStore()
        val ok = SimpleSetup.testAndSave(input(), s1, factory(t1))
        check("saved after real request", ok.saved && t1.calls == 1)
        check("store holds gemini defaults", s1.providerType == ProviderType.GEMINI && s1.baseUrl == G_BASE && s1.model == "gemini-2.5-flash-lite")
        check("key stored through encrypted setter (not plain)", s1.encryptedBlob != null && !s1.encryptedBlob!!.contains(KEY) && s1.apiKey == KEY)
        check("api name persisted", s1.apiName == "My Gemini")
        check("request body is a real Gemini generateContent body", JSONObject(t1.lastBody).has("contents") && JSONObject(t1.lastBody).has("system_instruction"))
        check("success message never contains key", !ok.message.contains(KEY))

        println("Test & Save failure paths (nothing saved, friendly text, no key):")
        suspend fun fail(resp: (String) -> HttpResponse): Pair<SimpleSetup.Outcome, FakeStore> {
            val s = FakeStore(); val tr = FakeTransport(resp); return SimpleSetup.testAndSave(input(), s, factory(tr)) to s
        }
        val (auth, sa) = fail { HttpResponse(400, """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key. key=$KEY","status":"INVALID_ARGUMENT"}}""") }
        check("invalid key (400 from Gemini) -> not saved", !auth.saved && sa.writes == 0 && sa.providerType == ProviderType.LOCAL)
        check("invalid key message friendly + no key", auth.message.startsWith("✗") && !auth.message.contains(KEY), auth.message)
        val (a401, _) = fail { HttpResponse(401, "{}") }
        check("401 -> 'key was not accepted'", a401.message.contains("key was not accepted") && a401.kind == ProviderErrorKind.AUTH)
        val (a403, _) = fail { HttpResponse(403, """{"error":{"message":"PERMISSION_DENIED"}}""") }
        check("403 -> unauthorized wording", a403.message.contains("key was not accepted"))
        val (nf, _) = fail { HttpResponse(404, """{"error":{"message":"models/gemini-2.5-flash-lite is not found"}}""") }
        check("404 -> model unavailable, shows model in use", nf.message.contains("gemini-2.5-flash-lite") && nf.message.contains("not available"), nf.message)
        val (rl, _) = fail { HttpResponse(429, "{}") }
        check("429 -> rate limit wording", rl.message.contains("rate-limiting"))
        val (sv, _) = fail { HttpResponse(503, "<html>oops</html>") }
        check("5xx -> server trouble wording", sv.message.contains("servers are having trouble"))
        val (net, sn) = fail { throw UnknownHostException("generativelanguage.googleapis.com") }
        check("no internet -> network wording, not saved", net.message.contains("No internet") && sn.writes == 0)
        val (mal, _) = fail { HttpResponse(200, "not json") }
        check("malformed -> unexpected reply wording", mal.message.contains("unexpected reply"))
        val (emp, _) = fail { HttpResponse(200, """{"candidates":[]}""") }
        check("empty candidates -> friendly", !emp.saved && emp.message.startsWith("✗"))
        check("all failure messages are key-free", listOf(auth, a401, a403, nf, rl, sv, net, mal, emp).none { it.message.contains(KEY) || it.message.contains(KEY.reversed()) })

        println("Existing configurations preserved:")
        val custom = FakeStore(ProviderType.GEMINI, G_BASE, "gemini-2.0-flash", "Old label").also { it.apiKey = KEY }
        check("existing custom Gemini model kept by toConfig", SimpleSetup.toConfig(input(), ProviderConfig(custom.providerType, custom.baseUrl, custom.model, custom.apiKey)).model == "gemini-2.0-flash")
        val t2 = FakeTransport { geminiOk() }
        SimpleSetup.testAndSave(input(name = "Renamed"), custom, factory(t2))
        check("Test & Save with custom model requests that model", t2.lastUrl.endsWith("/models/gemini-2.0-flash:generateContent") && custom.model == "gemini-2.0-flash")
        check("label updated, key rewritten via encrypted setter", custom.apiName == "Renamed" && custom.apiKey == KEY)
        check("invalid stored model replaced by default in toConfig", SimpleSetup.toConfig(input(), ProviderConfig(ProviderType.GEMINI, G_BASE, "gemini", KEY)).model == GeminiModels.DEFAULT)
        check("openai-compatible existing config not used as gemini source", SimpleSetup.toConfig(input(), ProviderConfig(ProviderType.OPENAI_COMPATIBLE, "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile", "k")).let { it.baseUrl == G_BASE && it.model == GeminiModels.DEFAULT })

        println("Migration of stored 'gemini' model:")
        val m1 = FakeStore(ProviderType.GEMINI, G_BASE, "gemini", "x").also { it.apiKey = KEY }
        check("gemini -> gemini-2.5-flash-lite", SimpleSetup.migrate(m1) && m1.model == "gemini-2.5-flash-lite")
        check("migration leaves key + label + base url intact", m1.apiKey == KEY && m1.apiName == "x" && m1.baseUrl == G_BASE && m1.writes == 1)
        check("idempotent", !SimpleSetup.migrate(m1) && m1.model == "gemini-2.5-flash-lite")
        val m2 = FakeStore(ProviderType.GEMINI, G_BASE, "gemini-2.0-flash")
        check("valid custom model preserved", !SimpleSetup.migrate(m2) && m2.model == "gemini-2.0-flash")
        val m3 = FakeStore(ProviderType.GEMINI, G_BASE, "models/gemini-1.5-pro")
        check("models/ prefix normalised, id kept", SimpleSetup.migrate(m3) && m3.model == "gemini-1.5-pro")
        val m4 = FakeStore(ProviderType.GEMINI, "", "")
        check("blank gemini model/base filled with defaults", SimpleSetup.migrate(m4) && m4.model == GeminiModels.DEFAULT && m4.baseUrl == G_BASE)
        val m5 = FakeStore(ProviderType.OPENAI_COMPATIBLE, "https://openrouter.ai/api/v1", "gemini").also { it.apiKey = "sk-or-abcdefghijklmnop" }
        check("OpenAI-compatible config untouched (even model named 'gemini')", !SimpleSetup.migrate(m5) && m5.model == "gemini" && m5.baseUrl == "https://openrouter.ai/api/v1" && m5.apiKey == "sk-or-abcdefghijklmnop")
        val m6 = FakeStore(ProviderType.LOCAL, "", "gemini")
        check("LOCAL config untouched", !SimpleSetup.migrate(m6) && m6.model == "gemini")

        println("OpenAI-compatible path unaffected:")
        val to = FakeTransport { HttpResponse(200, """{"choices":[{"message":{"content":"OK"}}]}""") }
        val oc = ProviderConfig(ProviderType.OPENAI_COMPATIBLE, "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile", "gsk_abcdefghijklmnopqrstuvwxyz")
        val r = ConnectionTester.test(oc, factory(to))
        check("openai-compatible test still works", r.ok && to.lastUrl == "https://api.groq.com/openai/v1/chat/completions" && to.lastHeaders["Authorization"] == "Bearer gsk_abcdefghijklmnopqrstuvwxyz")
        check("friendlyError never leaks key for openai config", !SimpleSetup.friendlyError(ProviderErrorKind.UNKNOWN, oc, "boom gsk_abcdefghijklmnopqrstuvwxyz").contains("gsk_abcdefghijklmnopqrstuvwxyz"))

        println("Offline choice:")
        val so = FakeStore(ProviderType.GEMINI, G_BASE, "gemini-2.5-flash-lite", "My Gemini").also { it.apiKey = KEY }
        val to2 = FakeTransport { geminiOk() }
        val off = SimpleSetup.testAndSave(input(key = "", name = "", p = SimpleProvider.OFFLINE), so, factory(to2))
        check("offline saved without request", off.saved && to2.calls == 0 && so.providerType == ProviderType.LOCAL)

        println("$passed passed, $fails failed")
        if (fails > 0) System.exit(1)
    }
}
