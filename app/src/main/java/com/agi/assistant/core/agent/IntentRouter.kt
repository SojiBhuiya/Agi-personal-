package com.agi.assistant.core.agent

import com.agi.assistant.core.ai.ToolCall
import com.agi.assistant.core.tools.ToolIntent
import com.agi.assistant.core.tools.ToolSpec
import java.util.Locale

/** What the user wants from this request. */
enum class RequestIntent { CONVERSATION, INFORMATION, ACTION, UI }

/**
 * A declarative routing rule: when the request matches, it is an [intent] served by [tool]
 * (null = the model answers itself). New tools add a rule instead of editing an if/else chain.
 */
data class IntentRule(
    val id: String,
    val intent: RequestIntent,
    val tool: String?,
    val keywords: List<String>,
    /** Arguments derived from the text (e.g. the city for weather). */
    val args: (String) -> Map<String, Any?> = { emptyMap() },
    /** Lower number wins when several rules match. */
    val priority: Int = 50,
)

/** Result of routing one request. */
data class Route(val intent: RequestIntent, val tool: String?, val args: Map<String, Any?>, val rule: String) {
    /** Tools that must not be used for this request unless the model has an explicit reason. */
    fun discouraged(specs: List<ToolSpec>): List<String> = when (intent) {
        RequestIntent.INFORMATION, RequestIntent.CONVERSATION -> specs.filter { it.intent == ToolIntent.UI }.map { it.name }
        else -> emptyList()
    }
}

/**
 * Intent-aware tool routing. Sits *before* execution:
 *  - the offline planner uses [route] to pick the direct tool (weather → get_weather, battery → device_info…);
 *  - the online path gets [policyPrompt] (static rules) + [hintFor] (per-request nudge) so the model
 *    prefers direct data tools and only touches the browser / screen when the user asked to see something;
 *  - [ToolIntent]/[ToolSpec.rawOutput] let the UI decide what may be shown verbatim.
 *
 * Rules are data; explicit UI verbs ("open", "show", "search in Google", "দেখাও", "খুলে") outrank
 * information keywords so "Google-এ আবহাওয়া সার্চ করে দেখাও" is UI while "আজ আবহাওয়া কেমন?" is INFORMATION.
 */
class IntentRouter(private val rules: List<IntentRule> = DEFAULT_RULES) {

    fun route(text: String): Route {
        val l = normalize(text)
        val hit = rules.filter { r -> r.keywords.any { k -> containsWord(l, k) } }
            .sortedWith(compareBy<IntentRule> { it.priority }.thenByDescending { r -> r.keywords.filter { containsWord(l, it) }.maxOf { it.length } })
            .firstOrNull()
        return if (hit == null) Route(RequestIntent.CONVERSATION, null, emptyMap(), "default")
        else Route(hit.intent, hit.tool, hit.args(text), hit.id)
    }

    /** Short per-request nudge appended to the prompt (no user text is echoed, only the decision). */
    fun hintFor(text: String, specs: List<ToolSpec>): String? {
        val r = route(text)
        return when (r.intent) {
            RequestIntent.CONVERSATION -> null
            RequestIntent.INFORMATION -> buildString {
                append("Routing: this is an information request.")
                r.tool?.let { if (specs.any { s -> s.name == it }) append(" Call $it directly and answer from its result.") }
                val d = r.discouraged(specs); if (d.isNotEmpty()) append(" Do not use ${d.joinToString(", ")} for it.")
            }
            RequestIntent.ACTION -> "Routing: this is an action request" + (r.tool?.let { " – use $it" } ?: "") + "; perform it, then confirm briefly."
            RequestIntent.UI -> "Routing: the user explicitly asked to open/show/search something on screen" + (r.tool?.let { " – $it is appropriate" } ?: "") + "."
        }
    }

    /** True when [call] is a UI tool used for an information/conversation request (i.e. a misroute). */
    fun isMisrouted(text: String, call: ToolCall, specs: List<ToolSpec>): Boolean =
        route(text).discouraged(specs).contains(call.name)

    companion object {
        /** Static policy block for the system prompt, generated from the tools' declared intents. */
        fun policyPrompt(specs: List<ToolSpec>): String {
            fun names(i: ToolIntent) = specs.filter { it.intent == i }.joinToString(", ") { it.name }.ifBlank { "(none)" }
            return """
                Tool selection policy:
                - INFORMATION requests (weather, time/date, battery, notifications, contacts, files, general knowledge): answer directly.
                  Direct data tools: ${names(ToolIntent.INFORMATION)}. General knowledge: answer yourself. Never open the browser or read the screen just to find information.
                - ACTION requests (volume, brightness, flashlight, alarms, calls, messages, opening apps, playing something): perform it with ${names(ToolIntent.ACTION)}.
                - UI requests (the user says open / show / search in Google / look at the screen / play on YouTube): only then use ${names(ToolIntent.UI)}.
                - Weather with no city means the phone's current location (get_weather without 'city'). If location is unavailable, say so and ask for a city; do not open a browser to find it.
                - Prefer the fewest tool calls; never call read_screen after a direct data tool.
                - Never paste raw tool output (screen dumps, button lists) into your reply; give a short natural-language answer in the user's language.
            """.trimIndent()
        }

        private val CITY_STOP = setOf("আজ", "আজকে", "এখন", "আবহাওয়া", "কেমন", "কি", "কী", "বৃষ্টি", "হবে", "তাপমাত্রা", "temperature", "weather", "today", "now", "the", "in", "at", "of", "for", "what", "whats", "what's", "is", "how", "like", "forecast", "rain", "will", "it", "tomorrow", "কত", "কাল", "আগামীকাল")

        /** "ঢাকার আবহাওয়া" → Dhaka-ish token; "weather in Dhaka" → Dhaka; none → empty (= current location). */
        fun extractCity(text: String): String? {
            val cleaned = text.replace(Regex("[?!।,.]"), " ")
            Regex("(?i)(?:weather|forecast|temperature|rain)\\s+(?:in|at|for|of)\\s+([A-Za-z\\u0980-\\u09FF][A-Za-z\\u0980-\\u09FF .'-]{1,40}?)(?:\\s+(?:today|now|tomorrow|tonight)|$)").find(cleaned)?.let { return it.groupValues[1].trim() }
            Regex("([\\u0980-\\u09FF]{2,})(?:র|ের|তে|এ|য়)\\s+(?:আজকের\\s+|আজ\\s+)?(?:আবহাওয়া|তাপমাত্রা|বৃষ্টি)").find(cleaned)?.let { return it.groupValues[1] }
            val words = cleaned.split(Regex("\\s+")).filter { it.isNotBlank() }
            val candidates = words.filter { w -> w.lowercase(Locale.ROOT) !in CITY_STOP && w.length > 2 && w.any { it.isLetter() } && (w[0].isUpperCase() || w.any { it in '\u0980'..'\u09FF' }) }
            return candidates.firstOrNull()
        }

        private fun weatherArgs(text: String): Map<String, Any?> {
            val m = mutableMapOf<String, Any?>("language" to if (text.any { it in '\u0980'..'\u09FF' }) "bn" else "en")
            extractCity(text)?.let { m["city"] = it }
            return m
        }

        private fun normalize(t: String) = t.lowercase(Locale.ROOT).replace(Regex("[-‐‑_/]"), " ").replace(Regex("\\s+"), " ").trim()
        private fun containsWord(l: String, k: String): Boolean {
            val kw = k.lowercase(Locale.ROOT)
            return if (kw.any { it > '\u007f' }) l.contains(kw) else Regex("(^|[^a-z])" + Regex.escape(kw) + "([^a-z]|$)").containsMatchIn(l)
        }

        val DEFAULT_RULES: List<IntentRule> = listOf(
            // ---- UI (explicit) – highest priority
            IntentRule("ui.search", RequestIntent.UI, "web_search", listOf("search", "google it", "সার্চ", "গুগলে", "google এ", "google-এ", "গুগল করে", "browse", "ব্রাউজারে", "chrome এ", "chrome-এ"), priority = 5),
            IntentRule("ui.show_site", RequestIntent.UI, "open_url", listOf("website", "ওয়েবসাইট", "url", "link", "লিংক", "site"), priority = 8),
            IntentRule("ui.screen", RequestIntent.UI, "read_screen", listOf("on the screen", "on screen", "the screen", "my screen", "স্ক্রিনে", "স্ক্রিন", "স্ক্রীন", "screen"), priority = 6),
            // ---- ACTION
            IntentRule("action.play_youtube", RequestIntent.ACTION, "open_url", listOf("play", "গান চালাও", "গান", "চালাও", "বাজাও", "music", "song", "video"), priority = 10),
            IntentRule("action.open_app", RequestIntent.ACTION, "open_app", listOf("open", "launch", "start", "খুলে", "খোলো", "খুলো", "চালু করো", "ওপেন"), priority = 15),
            IntentRule("action.volume", RequestIntent.ACTION, "volume", listOf("volume", "ভলিউম", "mute", "মিউট", "আওয়াজ", "শব্দ"), priority = 12),
            IntentRule("action.brightness", RequestIntent.ACTION, "brightness", listOf("brightness", "উজ্জ্বলতা", "ব্রাইটনেস"), priority = 12),
            IntentRule("action.flashlight", RequestIntent.ACTION, "flashlight", listOf("flashlight", "torch", "ফ্ল্যাশলাইট", "টর্চ", "ফ্ল্যাশ"), priority = 12),
            IntentRule("action.alarm", RequestIntent.ACTION, "alarm_timer", listOf("alarm", "timer", "অ্যালার্ম", "এলার্ম", "টাইমার"), priority = 12),
            IntentRule("action.call", RequestIntent.ACTION, "call_contact", listOf("call", "ফোন করো", "কল করো", "কল দাও"), priority = 12),
            IntentRule("action.sms", RequestIntent.ACTION, "send_sms", listOf("sms", "text message", "মেসেজ পাঠাও", "এসএমএস"), priority = 12),
            IntentRule("action.whatsapp", RequestIntent.ACTION, "send_whatsapp", listOf("whatsapp", "হোয়াটসঅ্যাপ"), priority = 11),
            // ---- INFORMATION
            IntentRule("info.weather", RequestIntent.INFORMATION, "get_weather", listOf("weather", "forecast", "temperature", "rain", "আবহাওয়া", "তাপমাত্রা", "বৃষ্টি", "গরম", "ঠান্ডা"), args = ::weatherArgs, priority = 20),
            IntentRule("info.battery", RequestIntent.INFORMATION, "device_info", listOf("battery", "ব্যাটারি", "চার্জ", "charge"), priority = 20),
            IntentRule("info.time", RequestIntent.INFORMATION, "device_info", listOf("what time", "time is it", "কয়টা বাজে", "কটা বাজে", "সময় কত", "what's the date", "what is the date", "today's date", "আজ কত তারিখ", "তারিখ"), priority = 20),
            IntentRule("info.notifications", RequestIntent.INFORMATION, "read_notifications", listOf("notification", "notifications", "নোটিফিকেশন", "নোটিফিকেশনগুলো"), priority = 20),
            IntentRule("info.contact", RequestIntent.INFORMATION, "find_contact", listOf("phone number of", "number of", "নম্বর", "নাম্বার"), priority = 25),
            IntentRule("info.files", RequestIntent.INFORMATION, "find_files", listOf("find file", "find files", "my files", "downloads", "ফাইল"), priority = 25),
        )
    }
}
