package com.agi.assistant

import com.agi.assistant.core.ai.providers.LocalRuleProvider

/**
 * Plain-JVM checks for the offline planner. Written without JUnit so they run
 * with the offline toolchain (`scripts/run_tests.sh`) as well as via Gradle.
 */
object LocalRuleProviderTest {
    private val p = LocalRuleProvider()
    private var failures = 0
    private var passed = 0

    private fun expect(input: String, vararg expected: Pair<String, Map<String, Any?>>) {
        val plan = p.plan(input)
        val got = plan.map { it.name to it.arguments }
        val ok = plan.size == expected.size && expected.withIndex().all { (i, e) ->
            plan[i].name == e.first && e.second.all { (k, v) -> plan[i].arguments[k].toString().equals(v.toString(), ignoreCase = true) }
        }
        if (ok) { passed++; println("  ok   \"$input\" -> ${got.joinToString { it.first + it.second }}") }
        else { failures++; println("  FAIL \"$input\"\n       expected ${expected.toList()}\n       got      $got") }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("LocalRuleProvider plans:")
        expect("Open YouTube", "open_app" to mapOf("app" to "YouTube"))
        expect("open whatsapp", "open_app" to mapOf("app" to "whatsapp"))
        expect("Launch the camera app", "open_app" to mapOf("app" to "camera"))
        expect("Call Rahim", "call_contact" to mapOf("contact" to "Rahim"))
        expect("call 01712345678", "call_contact" to mapOf("contact" to "01712345678"))
        expect("Send a message to Rahim", "send_sms" to mapOf("contact" to "Rahim"))
        expect("Send a message to Rahim saying I am on my way", "send_sms" to mapOf("contact" to "Rahim", "message" to "I am on my way"))
        expect("text Karim that I'll be late", "send_sms" to mapOf("contact" to "Karim", "message" to "I'll be late"))
        expect("send a whatsapp to Rahim saying hello", "send_whatsapp" to mapOf("contact" to "Rahim", "message" to "hello"))
        expect("Open Chrome and search for something", "open_app" to mapOf("app" to "Chrome"), "web_search" to mapOf("query" to "something"))
        expect("search for best phones 2026", "web_search" to mapOf("query" to "best phones 2026"))
        expect("Go to Settings", "open_settings" to mapOf("screen" to "main"))
        expect("open wifi settings", "open_settings" to mapOf("screen" to "wifi"))
        expect("Turn the volume up", "volume" to mapOf("action" to "up"))
        expect("volume down", "volume" to mapOf("action" to "down"))
        expect("set volume to 30%", "volume" to mapOf("action" to "set", "level" to 30))
        expect("mute", "volume" to mapOf("action" to "mute"))
        expect("Take a screenshot", "screenshot" to emptyMap())
        expect("Find my downloaded PDF", "find_files" to mapOf("type" to "pdf", "downloads_only" to true))
        expect("show my photos", "find_files" to mapOf("type" to "image"))
        expect("Read my notifications", "read_notifications" to emptyMap())
        expect("Open WhatsApp", "open_app" to mapOf("app" to "WhatsApp"))
        expect("Go back", "global_action" to mapOf("action" to "back"))
        expect("go home", "global_action" to mapOf("action" to "home"))
        expect("Scroll down", "scroll" to mapOf("direction" to "down"))
        expect("scroll up", "scroll" to mapOf("direction" to "up"))
        expect("Tap the Subscribe button", "tap_text" to mapOf("text" to "Subscribe"))
        expect("click on Sign in", "tap_text" to mapOf("text" to "Sign in"))
        expect("Type hello world", "type_text" to mapOf("text" to "hello world"))
        expect("turn on the flashlight", "flashlight" to mapOf("on" to true))
        expect("flashlight off", "flashlight" to mapOf("on" to false))
        expect("set an alarm for 6:30 am", "alarm_timer" to mapOf("type" to "alarm", "hour" to 6, "minute" to 30))
        expect("set a timer for 5 minutes", "alarm_timer" to mapOf("type" to "timer", "seconds" to 300))
        expect("what time is it", "device_info" to emptyMap())
        expect("open bbc.com", "open_url" to mapOf("url" to "bbc.com"))
        expect("what's the weather in Dhaka", "get_weather" to mapOf("city" to "Dhaka", "language" to "en"))  // information → direct source, never the browser
        expect("play lo-fi beats on youtube", "open_url" to mapOf("url" to "https://www.youtube.com/results?search_query=lo-fi+beats"))
        // Multi-step
        expect("Open Chrome, search for Bangladesh weather, and tell me what you find",
            "open_app" to mapOf("app" to "Chrome"), "web_search" to mapOf("query" to "Bangladesh weather"), "read_screen" to emptyMap())
        expect("open youtube and scroll down", "open_app" to mapOf("app" to "youtube"), "scroll" to mapOf("direction" to "down"))
        expect("go back then open settings", "global_action" to mapOf("action" to "back"), "open_settings" to mapOf("screen" to "main"))
        expect("turn volume up and take a screenshot", "volume" to mapOf("action" to "up"), "screenshot" to emptyMap())
        expect("asdfgh qwerty") // -> no plan
        println("\n$passed passed, $failures failed")
        if (failures > 0) System.exit(1)
    }
}
