package com.agi.assistant.core.ai.providers

import com.agi.assistant.core.ai.*
import java.util.Locale
import java.util.UUID

/**
 * Offline, deterministic "planner" that maps natural language to tool calls.
 *
 * It makes the assistant fully usable without any API key and doubles as a
 * fallback when a remote provider fails. It understands single commands and
 * simple multi-step requests joined by "and" / "then" / ",".
 *
 * It is intentionally kept free of Android dependencies so it can be unit
 * tested on the JVM (see app/src/test).
 */
class LocalRuleProvider : AiProvider {
    override val id = "local_rules"
    override val displayName = "Offline rule-based planner"
    override val isRemote = false

    override suspend fun complete(request: AiRequest): AiResponse {
        val last = request.messages.lastOrNull() ?: return reply("Hello! What can I do for you?")

        // After tools ran we summarise their output.
        if (last.role == Role.TOOL) return summariseToolResults(request.messages)

        val text = last.content.trim()
        if (text.isBlank()) return reply("I didn't catch that. Please try again.")

        val plan = plan(text)
        if (plan.isEmpty()) {
            return reply(
                "I couldn't map \"$text\" to a phone action in offline mode. " +
                    "Try things like \"open YouTube\", \"call Rahim\", \"volume up\", \"take a screenshot\" or " +
                    "connect an AI provider in Settings for free-form requests."
            )
        }
        return AiResponse(null, plan, id)
    }

    // ---------------------------------------------------------------------
    // Planning
    // ---------------------------------------------------------------------

    fun plan(input: String): List<ToolCall> {
        val calls = mutableListOf<ToolCall>()
        var wantsReport = false
        for (raw in splitSteps(input)) {
            val step = raw.trim().trimEnd('.', '!', '?').trim()
            if (step.isEmpty()) continue
            if (REPORT_RE.containsMatchIn(step.lowercase(Locale.ROOT))) {
                wantsReport = true
                continue
            }
            parseStep(step)?.let { calls += it }
        }
        if (wantsReport && calls.isNotEmpty()) {
            calls += call("read_screen", mapOf("purpose" to "summarise what is on screen for the user"))
        }
        return calls
    }

    private fun splitSteps(input: String): List<String> {
        // Split on ", then", " and then", " then", " and " (only when followed by a verb-like word), and commas.
        var s = input.replace(Regex("\\s+"), " ")
        s = s.replace(Regex("(?i)\\b(and then|then|after that|afterwards)\\b"), "|")
        s = s.replace(Regex("(?i),\\s*(and\\s+)?(?=(open|go|call|send|search|type|tap|click|press|scroll|turn|set|take|read|find|tell|show|play|launch|start|increase|decrease|mute|unmute|swipe|say|wait|look))"), "|")
        s = s.replace(Regex("(?i)\\s+and\\s+(?=(open|go|call|send|search|type|tap|click|press|scroll|turn|set|take|read|find|tell|show|play|launch|start|increase|decrease|mute|unmute|swipe|say|wait|look))"), "|")
        return s.split('|')
    }

    private fun parseStep(stepRaw: String): ToolCall? {
        val step = stepRaw.trim()
        val lower = step.lowercase(Locale.ROOT)

        // Fixed-phrase commands first (navigation).
        when {
            lower.matches(Regex("(go|navigate)? ?back")) || lower == "back" || lower == "press back" -> return call("global_action", mapOf("action" to "back"))
            lower.matches(Regex("(go|take me)? ?(to )?home( screen)?|press home")) -> return call("global_action", mapOf("action" to "home"))
            lower.contains("recent apps") || lower.contains("recents") || lower == "show recents" -> return call("global_action", mapOf("action" to "recents"))
            lower.matches(Regex("(open|show|pull down|expand) (the )?notification(s| shade| panel| drawer)?")) -> return call("global_action", mapOf("action" to "notifications"))
            lower.contains("quick settings") -> return call("global_action", mapOf("action" to "quick_settings"))
            lower.matches(Regex("lock (the )?(phone|screen|device)")) -> return call("global_action", mapOf("action" to "lock"))
            lower.matches(Regex("(take|capture|grab)( a)? screenshot|screenshot")) -> return call("screenshot", emptyMap())
            lower.matches(Regex("(please )?(read|check|show|tell me)( me)?( my)?( the)? ?notifications?( please)?")) -> return call("read_notifications", emptyMap())
            lower.matches(Regex("(read|describe|what'?s on|what is on)( the| my)? screen.*")) -> return call("read_screen", emptyMap())
            lower.matches(Regex("(what|which) (time|day|date).*|what'?s the (time|date).*|battery( level| status)?|how much battery.*|device info")) -> return call("device_info", emptyMap())
        }

        // Scrolling / swiping
        Regex("(scroll|swipe) ?(down|up|left|right)?").find(lower)?.let { m ->
            if (lower.startsWith("scroll") || lower.startsWith("swipe")) {
                var dir = m.groupValues[2].ifEmpty { "down" }
                if (lower.startsWith("swipe")) dir = when (dir) { "up" -> "down"; "down" -> "up"; "left" -> "right"; "right" -> "left"; else -> dir }
                return call("scroll", mapOf("direction" to dir))
            }
        }

        // Volume – English + Bangla, Bengali numerals, absolute vs relative (see core/tools/VolumeLogic.kt)
        com.agi.assistant.core.tools.VolumeCommand.parse(step)?.let { return call("volume", it.toToolArgs()) }

        // Brightness
        if (lower.contains("brightness") || lower.contains("screen brighter") || lower.contains("screen darker")) {
            val level = Regex("(\\d{1,3})").find(lower)?.groupValues?.get(1)?.toInt()
            val action = when {
                level != null -> "set"
                lower.contains("up") || lower.contains("increase") || lower.contains("brighter") || lower.contains("max") -> "up"
                else -> "down"
            }
            return call("brightness", mapOf("action" to action, "level" to level))
        }

        // Flashlight
        if (lower.contains("flashlight") || lower.contains("torch")) {
            val on = !(lower.contains("off") || lower.contains("stop") || lower.contains("disable"))
            return call("flashlight", mapOf("on" to on))
        }

        // Alarm / timer
        Regex("set (an? )?alarm (for|at) (.+)").find(lower)?.let { m ->
            val (h, min) = parseTime(m.groupValues[3]) ?: return call("alarm_timer", mapOf("type" to "alarm", "label" to m.groupValues[3]))
            return call("alarm_timer", mapOf("type" to "alarm", "hour" to h, "minute" to min))
        }
        Regex("(set|start) (a )?timer (for )?(\\d+) ?(seconds?|secs?|minutes?|mins?|hours?|hrs?)").find(lower)?.let { m ->
            val n = m.groupValues[4].toInt()
            val unit = m.groupValues[5]
            val secs = when { unit.startsWith("h") -> n * 3600; unit.startsWith("m") -> n * 60; else -> n }
            return call("alarm_timer", mapOf("type" to "timer", "seconds" to secs))
        }

        // Web search (possibly "open chrome and search for X" collapsed into one step)
        Regex("(?:search|google|look up|look for|find out about)(?: (?:for|about|on google|on the web|the web for))? (.+)").find(lower)?.let { m ->
            var query = step.substring(m.groups[1]!!.range)
            query = query.replace(Regex("(?i)^(for|about) "), "")
            return call("web_search", mapOf("query" to query))
        }
        Regex("open (chrome|browser|google) and (?:search|look) (?:for )?(.+)").find(lower)?.let { m ->
            return call("web_search", mapOf("query" to step.substring(m.groups[2]!!.range)))
        }
        if (lower.contains("weather")) {
            val q = step.replace(Regex("(?i)^(what'?s|what is|tell me|show me|check)( the)? "), "")
            return call("web_search", mapOf("query" to q))
        }

        // Calls
        Regex("(?:call|phone|dial|ring) (.+)").find(lower)?.let { m ->
            val who = step.substring(m.groups[1]!!.range).replace(Regex("(?i)\\b(please|now)\\b"), "").trim()
            return call("call_contact", mapOf("contact" to who))
        }

        // WhatsApp message
        Regex("(?:send|write) (?:a )?whatsapp(?: message)? to (.+?)(?: (?:saying|that says|:) (.+))?$").find(lower)?.let { m ->
            return call("send_whatsapp", mapOf("contact" to step.substring(m.groups[1]!!.range), "message" to m.groups[2]?.let { step.substring(it.range) }))
        }
        Regex("whatsapp (.+?)(?: (?:saying|that says|:) (.+))?$").find(lower)?.let { m ->
            if (!lower.startsWith("open")) return call("send_whatsapp", mapOf("contact" to step.substring(m.groups[1]!!.range), "message" to m.groups[2]?.let { step.substring(it.range) }))
        }

        // SMS / message
        Regex("(?:send|write|text) (?:a |an )?(?:sms|message|text)(?: message)? to (.+?)(?: (?:saying|that says|that|:) (.+))?$").find(lower)?.let { m ->
            return call("send_sms", mapOf("contact" to step.substring(m.groups[1]!!.range), "message" to m.groups[2]?.let { step.substring(it.range) }))
        }
        Regex("(?:text|message) (.+?)(?: (?:saying|that says|that|:) (.+))?$").find(lower)?.let { m ->
            return call("send_sms", mapOf("contact" to step.substring(m.groups[1]!!.range), "message" to m.groups[2]?.let { step.substring(it.range) }))
        }

        // Contact lookup
        Regex("(?:find|show|look up|what is|what'?s) (?:the )?(?:contact|number|phone number)(?: of| for)? (.+)").find(lower)?.let { m ->
            return call("find_contact", mapOf("name" to step.substring(m.groups[1]!!.range)))
        }

        // Files
        Regex("(?:find|show|open|search|locate|where is|where are)(?: me)?(?: my| the| all)?(?: (downloaded|recent|latest|last))? ?(pdf|pdfs|document|documents|photo|photos|picture|pictures|image|images|video|videos|song|songs|music|audio|download|downloads|file|files)(?: (?:named|called|about|with|containing) (.+))?").find(lower)?.let { m ->
            val kind = m.groupValues[2].trimEnd('s')
            val type = when (kind) {
                "pdf" -> "pdf"; "document" -> "document"
                "photo", "picture", "image" -> "image"
                "video" -> "video"
                "song", "music", "audio" -> "audio"
                else -> "any"
            }
            return call("find_files", mapOf("type" to type, "query" to m.groups[3]?.let { step.substring(it.range) }, "downloads_only" to (m.groupValues[1] == "downloaded" || kind == "download")))
        }

        // Settings
        Regex("(?:open|go to|show|launch) (?:the )?(.*?)settings").find(lower)?.let { m ->
            return call("open_settings", mapOf("screen" to m.groupValues[1].trim().ifEmpty { "main" }))
        }
        if (lower == "settings") return call("open_settings", mapOf("screen" to "main"))

        // Typing
        Regex("^(?:type|enter|write|input)(?: the text| this)?[: ]+(.+)").find(lower)?.let { m ->
            return call("type_text", mapOf("text" to step.substring(m.groups[1]!!.range).trim('"', '\'')))
        }

        // Tap
        Regex("^(?:tap|click|press|select|choose|hit)(?: on)?(?: the)? (.+?)(?: button| option| link| tab)?$").find(lower)?.let { m ->
            return call("tap_text", mapOf("text" to step.substring(m.groups[1]!!.range).trim('"', '\'')))
        }

        // URLs
        Regex("(?:open|go to|visit|browse) ((?:https?://)?[\\w.-]+\\.[a-z]{2,}(?:/\\S*)?)").find(lower)?.let { m ->
            return call("open_url", mapOf("url" to m.groupValues[1]))
        }

        // Play on YouTube / music
        Regex("play (.+?)(?: on youtube)?$").find(lower)?.let { m ->
            val what = step.substring(m.groups[1]!!.range)
            return if (lower.contains("youtube")) call("open_url", mapOf("url" to "https://www.youtube.com/results?search_query=" + what.replace(' ', '+')))
            else call("open_app", mapOf("app" to what))
        }

        // Open app (very common, keep last so specific intents win)
        Regex("^(?:open|launch|start|run|go to|switch to|show)(?: the)?(?: app)? (.+?)(?: app)?$").find(lower)?.let { m ->
            return call("open_app", mapOf("app" to step.substring(m.groups[1]!!.range)))
        }

        // Say
        Regex("^(?:say|speak|tell me) (.+)").find(lower)?.let { m ->
            return call("speak", mapOf("text" to step.substring(m.groups[1]!!.range)))
        }
        Regex("^wait (?:for )?(\\d+) ?(seconds?|secs?)?").find(lower)?.let { m ->
            return call("wait", mapOf("seconds" to m.groupValues[1].toInt()))
        }
        return null
    }

    private fun parseTime(s: String): Pair<Int, Int>? {
        val m = Regex("(\\d{1,2})(?::(\\d{2}))? ?(am|pm|a\\.m\\.|p\\.m\\.)?").find(s.trim()) ?: return null
        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        val ap = m.groupValues[3].replace(".", "")
        if (ap == "pm" && h < 12) h += 12
        if (ap == "am" && h == 12) h = 0
        return if (h in 0..23 && min in 0..59) h to min else null
    }

    private fun summariseToolResults(messages: List<ChatMessage>): AiResponse {
        val results = messages.takeLastWhile { it.role == Role.TOOL }
        val text = results.joinToString("\n") { it.content }.trim()
        return reply(text.ifBlank { "Done." })
    }

    private fun reply(text: String) = AiResponse(text, emptyList(), id)

    private fun call(name: String, args: Map<String, Any?>) =
        ToolCall("call_${UUID.randomUUID().toString().take(8)}", name, args.filterValues { it != null })

    companion object {
        private val REPORT_RE = Regex("^(and )?(tell me|let me know|report|summari[sz]e|read( it)? out|say)( me)? (what|the result|what you (find|see|found)|it)?.*")
    }
}
