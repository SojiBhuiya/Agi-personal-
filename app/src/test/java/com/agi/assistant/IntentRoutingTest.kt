package com.agi.assistant

import com.agi.assistant.core.agent.*
import com.agi.assistant.core.ai.*
import com.agi.assistant.core.ai.providers.LocalRuleProvider
import com.agi.assistant.core.tools.*
import kotlinx.coroutines.runBlocking

/**
 * Intent-aware tool routing: information requests go to direct data tools (weather → get_weather,
 * battery/time → device_info, notifications → read_notifications), actions to action tools, and
 * the browser / read_screen are used only when the user explicitly asks to open/show/search.
 * Also proves the raw-output guard and the AgentLoop misroute block. JVM only (no Android).
 */
object IntentRoutingTest {
    private var fails = 0; private var passed = 0
    private fun check(name: String, cond: Boolean, detail: Any? = "") { if (cond) { passed++; println("  ok   $name") } else { fails++; println("  FAIL $name $detail") } }

    private val router = IntentRouter()
    private val specs = listOf(
        ToolSpec("get_weather", "w", listOf(ToolParam("city", ParamType.STRING, "c", false)), intent = ToolIntent.INFORMATION),
        ToolSpec("device_info", "d", intent = ToolIntent.INFORMATION),
        ToolSpec("read_notifications", "n", intent = ToolIntent.INFORMATION, rawOutput = true),
        ToolSpec("volume", "v", listOf(ToolParam("level", ParamType.INTEGER, "p"))),
        ToolSpec("open_app", "o", listOf(ToolParam("name", ParamType.STRING, "n"))),
        ToolSpec("open_url", "u", listOf(ToolParam("url", ParamType.STRING, "u")), intent = ToolIntent.UI),
        ToolSpec("web_search", "s", listOf(ToolParam("query", ParamType.STRING, "q")), intent = ToolIntent.UI),
        ToolSpec("read_screen", "r", intent = ToolIntent.UI, rawOutput = true),
    )
    private val UI = setOf("web_search", "open_url", "read_screen")

    private fun text(t: String) = AiResponse(t, emptyList(), "scripted")
    private fun calls(vararg c: ToolCall) = AiResponse(null, c.toList(), "scripted")

    private suspend fun run(script: List<Any>, user: String, tool: suspend (ToolCall) -> ToolResult = { ToolResult.ok("ok") }): Triple<TurnTrace, List<AgentEvent>, List<String>> {
        val p = RequestFlowTest.ScriptedProvider(ArrayDeque(script))
        val h = RequestFlowTest.MemHistory(); h.add(ChatMessage(Role.USER, user))
        val events = ArrayList<AgentEvent>(); val executed = ArrayList<String>()
        val trace = AgentLoop(8).run(p, LocalRuleProvider(), "sys", specs, h, emptyList(), { c -> executed += c.name; tool(c) }, { events += it }, userText = user)
        return Triple(trace, events, executed)
    }

    private fun offline(text: String): List<ToolCall> = LocalRuleProvider().plan(text)

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        println("1. Local weather (no city) → get_weather without city, never the browser:")
        for (q in listOf("আজ আবহাওয়া কেমন?", "আজ বৃষ্টি হবে?", "এখন temperature কত?", "What's the weather today?", "Will it rain today?")) {
            val r = router.route(q)
            check("\"$q\" → INFORMATION/get_weather", r.intent == RequestIntent.INFORMATION && r.tool == "get_weather", r)
            check("\"$q\" → no city (device location)", r.args["city"] == null, r.args)
            val plan = offline(q)
            check("\"$q\" offline plan = [get_weather], no UI tool", plan.map { it.name } == listOf("get_weather"), plan)
        }

        println("2. Weather for an explicit city → direct weather with that city:")
        for ((q, city) in listOf("ঢাকার আবহাওয়া কেমন?" to "ঢাকা", "What's the weather in Dhaka?" to "Dhaka", "weather in Chittagong today" to "Chittagong", "Dhaka weather" to "Dhaka")) {
            val r = router.route(q)
            check("\"$q\" → get_weather(city=$city)", r.tool == "get_weather" && r.args["city"] == city, r.args)
            check("\"$q\" offline: no web_search", offline(q).none { it.name in UI }, offline(q))
        }

        println("3. Explicit Google search for weather → UI (web_search):")
        for (q in listOf("Google-এ weather search করো", "Search Google for Dhaka weather", "গুগলে আবহাওয়া সার্চ করে দেখাও")) {
            val r = router.route(q)
            check("\"$q\" → UI/web_search", r.intent == RequestIntent.UI && r.tool == "web_search", r)
            check("\"$q\" offline plan uses web_search", offline(q).map { it.name } == listOf("web_search"), offline(q))
        }

        println("4. Battery / time → device_info:")
        for (q in listOf("ব্যাটারি কত আছে?", "How much battery is left?", "battery level", "কয়টা বাজে?", "What time is it?")) {
            check("\"$q\" → device_info", router.route(q).tool == "device_info", router.route(q))
            check("\"$q\" offline plan = [device_info]", offline(q).map { it.name } == listOf("device_info"), offline(q))
        }

        println("5. Notifications → read_notifications (not the notification shade, not read_screen):")
        for (q in listOf("আমার নোটিফিকেশনগুলো পড়ো", "Read my notifications", "Any new notifications?")) {
            check("\"$q\" → read_notifications", router.route(q).tool == "read_notifications", router.route(q))
            check("\"$q\" offline plan = [read_notifications]", offline(q).map { it.name } == listOf("read_notifications"), offline(q))
        }

        println("6. \"Chrome খুলে দাও\" → app action:")
        for (q in listOf("Chrome খুলে দাও", "Open Chrome", "ক্রোম খোলো")) {
            check("\"$q\" → ACTION/open_app", router.route(q).intent == RequestIntent.ACTION && router.route(q).tool == "open_app", router.route(q))
        }
        check("offline: open chrome → open_app(chrome)", offline("Open Chrome").let { it.size == 1 && it[0].name == "open_app" && it[0].arguments["app"].toString().equals("chrome", true) }, offline("Open Chrome"))

        println("7. YouTube playback → action path (open_url youtube), not web_search/read_screen:")
        for (q in listOf("YouTube-এ Arijit Singh এর গান চালাও", "Play Arijit Singh on YouTube")) {
            check("\"$q\" → ACTION", router.route(q).intent == RequestIntent.ACTION, router.route(q))
            val plan = offline(q)
            check("\"$q\" offline: youtube url, no search/read_screen", plan.isNotEmpty() && plan.all { it.name == "open_url" && it.arguments["url"].toString().contains("youtube") }, plan)
        }

        println("8. \"Hi\" → conversation: 1 provider call, 0 tools, no routing hint:")
        val (ht, he, hx) = run(listOf(text("Hello! How can I help?")), "Hi")
        check("Hi: providerCalls=1 toolCalls=0 blocked=0", ht.providerCalls == 1 && ht.toolCalls == 0 && ht.blockedCalls == 0 && hx.isEmpty(), ht)
        check("Hi: route = CONVERSATION, hint = null", router.route("Hi").intent == RequestIntent.CONVERSATION && router.hintFor("Hi", specs) == null)
        check("Hi: reply verbatim", (he[1] as AgentEvent.Reply).text == "Hello! How can I help?")
        check("offline Hi: no tool plan", offline("Hi").isEmpty())

        println("9. Location unavailable → tell user & ask city; no browser fallback:")
        val ask = "Your current location isn't available. Which city should I check (e.g. Dhaka)?"
        val locScript = listOf(
            calls(ToolCall("c1", "get_weather", emptyMap(), "SIG-1")),
            calls(ToolCall("c2", "web_search", mapOf("query" to "weather near me"))),   // model tries the browser → must be blocked
            text(ask),
        )
        val (lt, le, lx) = run(locScript, "আজ আবহাওয়া কেমন?") { c -> if (c.name == "get_weather") ToolResult.fail(ask) else ToolResult.ok("opened browser") }
        check("web_search never executed", lx == listOf("get_weather"), lx)
        check("misroute counted, no fallback", lt.blockedCalls == 1 && !lt.fellBack, lt)
        check("final reply asks for a city", (le.last { it is AgentEvent.Reply } as AgentEvent.Reply).text == ask)
        check("blocked tool result tells the model what to do", (le.first { it is AgentEvent.ToolFinished && it.name == "web_search" } as AgentEvent.ToolFinished).result.output.contains("Not executed"))

        println("9b. UI tools are still allowed for explicit UI requests and after direct tools succeed:")
        val (ut, _, ux) = run(listOf(calls(ToolCall("c1", "web_search", mapOf("query" to "weather"))), text("Opened.")), "Google-এ weather search করো")
        check("explicit search request executes web_search", ux == listOf("web_search") && ut.blockedCalls == 0, ut)
        val (at, _, ax) = run(listOf(calls(ToolCall("c1", "open_url", mapOf("url" to "https://youtube.com"))), text("Playing.")), "Play Arijit Singh on YouTube")
        check("action request executes open_url", ax == listOf("open_url") && at.blockedCalls == 0, at)

        println("10. Raw tool output is never the final answer:")
        val dump = "Screen content of com.android.chrome:\n[Button] Search\n[Button] Back\n[Text] weather.com\n[EditText] Search or type URL\n[Button] Menu"
        val rawScript = listOf(calls(ToolCall("c1", "read_screen", emptyMap())), text(dump))
        val (_, re, _) = run(rawScript, "What's on my screen?") { ToolResult.ok(dump, "Read the screen of com.android.chrome") }
        val fin = re.filterIsInstance<AgentEvent.ToolFinished>().first()
        check("ToolFinished.display is the short summary, not the dump", fin.display == "Read the screen of com.android.chrome" && !fin.display.contains("[Button]"), fin.display)
        val reply = re.filterIsInstance<AgentEvent.Reply>().last().text
        check("provider echo of the dump replaced by summary", !reply.contains("[Button]") && reply.isNotBlank(), reply)
        val (_, re2, _) = run(listOf(calls(ToolCall("c1", "read_screen", emptyMap())), text("")), "What's on my screen?") { ToolResult.ok(dump, "Read the screen") }
        check("empty provider answer after raw tool → spoken summary, not 'Done.' dump", re2.filterIsInstance<AgentEvent.Reply>().last().text == "Read the screen")
        val notif = "You have 2 notification(s):\n- WhatsApp: Rahim: hello\n- Gmail: invoice"
        val (_, re3, _) = run(listOf(calls(ToolCall("c1", "read_notifications", emptyMap())), text("You have 2 notifications: a WhatsApp from Rahim and a Gmail invoice.")), "Read my notifications") { ToolResult.ok(notif, "You have 2 notifications") }
        check("model's own summary is kept verbatim", re3.filterIsInstance<AgentEvent.Reply>().last().text.startsWith("You have 2 notifications: a WhatsApp"))
        val local = LocalRuleProvider().complete(AiRequest("s", listOf(ChatMessage(Role.USER, "read screen"), ChatMessage(Role.ASSISTANT, "", listOf(ToolCall("c1", "read_screen", emptyMap()))), ChatMessage(Role.TOOL, dump, toolCallId = "c1", toolName = "read_screen")), specs))
        check("offline summariser does not echo the screen dump", !local.text.orEmpty().contains("[Button]"), local.text)
        val local2 = LocalRuleProvider().complete(AiRequest("s", listOf(ChatMessage(Role.USER, "weather"), ChatMessage(Role.ASSISTANT, "", listOf(ToolCall("c1", "get_weather", emptyMap()))), ChatMessage(Role.TOOL, "Weather for Dhaka: 31°C, rain.\nSuggested reply: It's 31°C in Dhaka with rain.", toolCallId = "c1", toolName = "get_weather")), specs))
        check("offline summariser uses the suggested reply", local2.text == "It's 31°C in Dhaka with rain.", local2.text)

        println("11. Prompt policy & hints are generated from declared intents:")
        val policy = IntentRouter.policyPrompt(specs)
        check("policy lists direct tools and UI tools separately", policy.contains("get_weather, device_info, read_notifications") && policy.contains("open_url, web_search, read_screen"))
        check("policy forbids browser for information", policy.contains("Never open the browser"))
        val hint = router.hintFor("আজ আবহাওয়া কেমন?", specs).orEmpty()
        check("weather hint names get_weather and discourages UI tools", hint.contains("get_weather") && hint.contains("web_search") && hint.contains("Do not use"), hint)
        check("UI hint does not discourage web_search", router.hintFor("Search Google for cats", specs).orEmpty().let { it.contains("web_search") && !it.contains("Do not use") })

        println("12. WeatherLogic (Open-Meteo parsing & summaries, offline fixtures):")
        val geo = """{"results":[{"name":"Dhaka","latitude":23.7104,"longitude":90.4074,"country":"Bangladesh","admin1":"Dhaka"}]}"""
        val place = WeatherLogic.parseGeocode(geo)!!
        check("geocode parsed", place.lat == 23.7104 && place.label == "Dhaka, Bangladesh", place)
        check("geocode miss → null", WeatherLogic.parseGeocode("""{"generationtime_ms":0.1}""") == null)
        val fc = """{"current":{"temperature_2m":31.4,"relative_humidity_2m":78,"apparent_temperature":36.9,"is_day":1,"weather_code":80,"wind_speed_10m":12.3},"daily":{"temperature_2m_max":[33.1],"temperature_2m_min":[26.7],"precipitation_probability_max":[70]}}"""
        val w = WeatherLogic.parseForecast(fc)
        check("forecast parsed", w.tempC == 31.4 && w.humidity == 78 && w.code == 80 && w.maxC == 33.1 && w.rainChance == 70, w)
        val bn = WeatherLogic.summary("আপনার বর্তমান এলাকা", w, bangla = true)
        check("Bangla summary is natural language", bn.startsWith("এখন আপনার বর্তমান এলাকা-এ তাপমাত্রা 31°C, বৃষ্টির ঝাপটা") && bn.contains("ছাতা") && !bn.contains("{"), bn)
        val en = WeatherLogic.summary("Dhaka, Bangladesh", w, bangla = false)
        check("English summary", en.startsWith("It's 31°C in Dhaka, Bangladesh right now with rain showers (feels like 37°C).") && en.contains("70% chance of rain"), en)
        check("URLs are HTTPS Open-Meteo, keyless", WeatherLogic.geocodeUrl("Dhaka").startsWith("https://geocoding-api.open-meteo.com/") && WeatherLogic.forecastUrl(23.7, 90.4).let { it.startsWith("https://api.open-meteo.com/") && !it.contains("key") })

        println("\nIntentRoutingTest: $passed passed, $fails failed")
        if (fails > 0) System.exit(1)
    }
}
