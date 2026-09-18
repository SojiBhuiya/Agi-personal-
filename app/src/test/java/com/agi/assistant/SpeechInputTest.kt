package com.agi.assistant

import com.agi.assistant.core.agent.IntentRouter
import com.agi.assistant.core.agent.RequestIntent
import com.agi.assistant.core.ai.providers.LocalRuleProvider
import com.agi.assistant.core.tools.VolumeCommand
import com.agi.assistant.voice.TranscriptProcessor

/**
 * Speech-to-text *handling* (JVM). The Android SpeechRecognizer engine itself cannot be tested
 * deterministically without a device; these tests prove that whatever it returns is passed on
 * faithfully (raw kept, only whitespace tidied), that partial results are never treated as final,
 * that the best alternative is chosen, the language setting resolves correctly, and that the
 * representative Bengali / English / Banglish phrases still reach the right command path unchanged.
 */
object SpeechInputTest {
    private var fails = 0; private var passed = 0
    private fun check(name: String, cond: Boolean, detail: Any? = "") { if (cond) { passed++; println("  ok   $name") } else { fails++; println("  FAIL $name $detail") } }
    private val T = TranscriptProcessor

    @JvmStatic
    fun main(args: Array<String>) {
        println("Raw vs processed:")
        val bn = "  আজ   আবহাওয়া কেমন\u200B "
        val f = T.final(listOf(bn))!!
        check("raw is preserved byte-for-byte", f.raw == bn)
        check("processed = whitespace/zero-width tidied only", f.processed == "আজ আবহাওয়া কেমন", f.processed)
        for (p in listOf("তোমার নাম কী", "ভলিউম ৫০ করো", "ভলিউম এক শতাংশ বাড়াও", "ইউটিউব খোলো", "What time is it?", "What is your name?", "Set volume to 50", "Open YouTube", "Tum hi ho song play koro", "volume 50 koro"))
            check("no rewriting: \"$p\"", T.normalize(p) == p && T.final(listOf(p))!!.processed == p)
        check("case, digits and script untouched (Bengali numerals kept)", T.normalize("Volume ৫০ Koro") == "Volume ৫০ Koro")
        check("punctuation kept", T.normalize("What time is it?") == "What time is it?")

        println("Partial vs final:")
        val part = T.partial("আজ আব")
        check("partial flagged isFinal=false", !part.isFinal && part.processed == "আজ আব")
        check("final flagged isFinal=true", f.isFinal)
        check("empty final -> null (nothing submitted)", T.final(listOf("", "   ")) == null && T.final(null) == null && T.final(emptyList()) == null)
        check("blank partial isEmpty", T.partial("   ").isEmpty)

        println("Alternative selection:")
        val a = T.final(listOf("open youtube", "open you tube", "open utube"), floatArrayOf(0.62f, 0.91f, 0.30f))!!
        check("higher confidence alternative wins over engine order", a.processed == "open you tube" && a.confidence == 0.91f, a)
        check("chosen first in alternatives, others kept for debugging", a.alternatives == listOf("open you tube", "open youtube", "open utube"))
        val b = T.final(listOf("volume 50 koro", "volume fifty koro"), null)!!
        check("no confidence scores -> engine order (first) kept", b.processed == "volume 50 koro" && b.confidence == -1f)
        val c = T.final(listOf("", "ভলিউম ৫০ করো"), floatArrayOf(0.9f, 0.8f))!!
        check("empty top alternative skipped", c.processed == "ভলিউম ৫০ করো")
        val d = T.final(listOf("what time is it", "what time is tea"), floatArrayOf(-1f, -1f))!!
        check("negative (unknown) scores treated as absent", d.processed == "what time is it")

        println("Language resolution:")
        check("auto follows phone locale", T.recognitionLanguage("auto", "bn-BD") == "bn-BD" && T.recognitionLanguage(null, "en-GB") == "en-GB" && T.recognitionLanguage("", "en-US") == "en-US")
        check("explicit Bengali overrides an English phone", T.recognitionLanguage("bn-BD", "en-US") == "bn-BD")
        check("explicit English overrides a Bengali phone", T.recognitionLanguage("en-US", "bn-BD") == "en-US")
        check("auto with blank device tag falls back to en-US", T.recognitionLanguage("auto", "") == "en-US")
        check("choices offered: auto, bn-BD, en-US", T.LANGUAGE_CHOICES.map { it.first } == listOf("auto", "bn-BD", "en-US"))

        println("Script detection (trace only):")
        check("bengali", T.script("আজ আবহাওয়া কেমন") == "bengali")
        check("latin", T.script("Open YouTube") == "latin")
        check("mixed", T.script("ভলিউম 50 koro") == "mixed" || T.script("Tum hi ho গান play koro") == "mixed")
        check("trace line has no newline and mentions FINAL/partial", !f.trace().contains("\n") && f.trace().startsWith("FINAL") && part.trace().startsWith("partial"))

        println("Representative phrases reach the right command path (transcript passed as-is):")
        fun plan(t: String) = LocalRuleProvider().plan(T.final(listOf(t))!!.processed)
        check("\"আজ আবহাওয়া কেমন\" -> get_weather", plan("আজ আবহাওয়া কেমন").map { it.name } == listOf("get_weather"), plan("আজ আবহাওয়া কেমন"))
        check("\"তোমার নাম কী\" -> conversation (no tool)", IntentRouter().route("তোমার নাম কী").intent == RequestIntent.CONVERSATION && plan("তোমার নাম কী").isEmpty())
        val v1 = VolumeCommand.parse("ভলিউম ৫০ করো"); check("\"ভলিউম ৫০ করো\" -> volume absolute 50", v1 != null && v1.toToolArgs()["level"] == 50 && plan("ভলিউম ৫০ করো").map { it.name } == listOf("volume"), v1)
        val v2 = VolumeCommand.parse("ভলিউম এক শতাংশ বাড়াও"); check("\"ভলিউম এক শতাংশ বাড়াও\" -> volume up (relative)", v2 != null && v2.toToolArgs()["action"].toString().contains("up", true), v2?.toToolArgs())
        check("\"ইউটিউব খোলো\" -> open_app", plan("ইউটিউব খোলো").let { it.size == 1 && it[0].name == "open_app" }, plan("ইউটিউব খোলো"))
        check("\"What time is it?\" -> device_info", plan("What time is it?").map { it.name } == listOf("device_info"))
        check("\"What is your name?\" -> conversation", IntentRouter().route("What is your name?").intent == RequestIntent.CONVERSATION && plan("What is your name?").isEmpty())
        check("\"Set volume to 50\" -> volume 50", plan("Set volume to 50").let { it.size == 1 && it[0].name == "volume" && it[0].arguments["level"] == 50 })
        check("\"Open YouTube\" -> open_app YouTube", plan("Open YouTube").let { it.size == 1 && it[0].name == "open_app" && it[0].arguments["app"].toString().equals("YouTube", true) })
        check("\"Tum hi ho song play koro\" -> action intent (play)", IntentRouter().route("Tum hi ho song play koro").intent == RequestIntent.ACTION)
        val v3 = VolumeCommand.parse("volume 50 koro"); check("\"volume 50 koro\" -> volume 50", v3 != null && v3.toToolArgs()["level"] == 50, v3)

        println("\nSpeechInputTest: $passed passed, $fails failed")
        if (fails > 0) System.exit(1)
    }
}
