package com.agi.assistant.core.tools

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Pure (Android-free) volume logic shared by the offline planner and [com.agi.assistant.core.tools.impl.VolumeTool].
 *
 *  - [VolumeCommand.parse] turns English/Bangla text into a structured command (absolute %, relative
 *    ± percentage points, up/down step, mute/unmute/max, read).
 *  - [VolumeMath] converts between percent and AudioManager stream indices deterministically:
 *      index   = round(percent / 100 * max)      percent = round(index * 100 / max)
 *    and never uses a hard-coded increment.
 */
object BanglaDigits {
    private const val BN_ZERO = '\u09E6' // ০
    /** Replaces Bengali numerals (০-৯) with ASCII digits; everything else is untouched. */
    fun toAscii(s: String): String = buildString(s.length) {
        for (c in s) append(if (c in BN_ZERO..(BN_ZERO + 9)) ('0' + (c - BN_ZERO)) else c)
    }
}

data class VolumeCommand(
    val action: Action,
    /** Target percent for [Action.SET]; delta in percentage points for [Action.ADJUST_PERCENT]. */
    val value: Int? = null,
    val stream: String = "media",
) {
    enum class Action { SET, UP, DOWN, ADJUST_PERCENT, MUTE, UNMUTE, MAX, GET }

    /** Arguments in the shape the `volume` tool accepts. */
    fun toToolArgs(): Map<String, Any?> {
        val m = mutableMapOf<String, Any?>()
        m["action"] = when (action) {
            Action.SET -> "set"; Action.UP -> "up"; Action.DOWN -> "down"; Action.ADJUST_PERCENT -> "adjust"
            Action.MUTE -> "mute"; Action.UNMUTE -> "unmute"; Action.MAX -> "max"; Action.GET -> "get"
        }
        if (action == Action.SET) m["level"] = value
        if (action == Action.ADJUST_PERCENT) m["delta"] = value
        if (stream != "media") m["stream"] = stream
        return m
    }

    companion object {
        private val VOLUME_WORDS = listOf("volume", "ভলিউম", "ভলুম", "ভলিয়ুম", "সাউন্ড", "আওয়াজ", "শব্দ", "sound")
        private val UP_WORDS = listOf("up", "increase", "louder", "raise", "higher", "boost", "বাড়াও", "বাড়া", "বাড়িয়ে", "বাড়ান", "বেশি", "জোরে", "উপরে")
        private val DOWN_WORDS = listOf("down", "decrease", "quieter", "lower", "reduce", "softer", "কমাও", "কমা", "কমিয়ে", "কমান", "কম", "আস্তে", "নিচে")
        private val MUTE_WORDS = listOf("mute", "silent", "silence", "মিউট", "নিঃশব্দ", "বন্ধ")
        private val UNMUTE_WORDS = listOf("unmute", "আনমিউট", "চালু", "শব্দ ফিরিয়ে")
        private val MAX_WORDS = listOf("max", "maximum", "full", "highest", "loudest", "সর্বোচ্চ", "পুরো", "ফুল", "ম্যাক্স")
        private val GET_WORDS = listOf("what is", "what's", "current", "check", "read", "tell me", "how loud", "কত", "বর্তমান", "দেখাও", "বলো", "বল")
        private val STREAMS = mapOf(
            "ring" to listOf("ring", "ringer", "রিং", "রিংটোন"), "alarm" to listOf("alarm", "অ্যালার্ম", "এলার্ম"),
            "call" to listOf("call volume", "in-call", "in call", "কল ভলিউম"), "notification" to listOf("notification", "নোটিফিকেশন"),
        )
        private val NUMBER = Regex("(\\d{1,3})(?:\\s*(%|percent|পার্সেন্ট|শতাংশ|ভাগ))?")

        /** True when the text is about volume at all (used for routing). */
        fun mentionsVolume(text: String): Boolean {
            val l = text.lowercase(Locale.ROOT)
            return VOLUME_WORDS.any { l.contains(it) } || l.startsWith("mute") || l.startsWith("unmute") || l.contains("louder") || l.contains("quieter") || l.startsWith("মিউট") || l.startsWith("আনমিউট")
        }

        /**
         * Parses a single volume instruction. Returns null when [text] is not a volume command.
         *
         * Semantics (see docs/ARCHITECTURE.md "Volume"):
         *  - a bare number → absolute target percent ("volume 50", "ভলিউম ৫০ করো", "set volume to 60%") – NOT relative
         *  - up/down + number → relative ±N percentage points ("volume up 1%", "ভলিউম ১০ বাড়াও")
         *  - up/down without number → one percentage point (the tool applies ±1 %)
         *  - mute / unmute / max / current
         */
        fun parse(text: String): VolumeCommand? {
            if (!mentionsVolume(text)) return null
            val ascii = BanglaDigits.toAscii(text)
            val l = ascii.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()

            val stream = STREAMS.entries.firstOrNull { (_, words) -> words.any { l.contains(it) } }?.key ?: "media"
            val number = NUMBER.find(l)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 100)
            val hasUp = containsAny(l, UP_WORDS)
            val hasDown = containsAny(l, DOWN_WORDS)

            return when {
                containsAny(l, UNMUTE_WORDS) -> VolumeCommand(Action.UNMUTE, stream = stream)
                containsAny(l, MUTE_WORDS) && number == null -> VolumeCommand(Action.MUTE, stream = stream)
                number != null && (hasUp || hasDown) && !isAbsolutePhrase(l) ->
                    VolumeCommand(Action.ADJUST_PERCENT, if (hasDown && !hasUp) -number else number, stream)
                number != null -> VolumeCommand(Action.SET, number, stream)
                containsAny(l, MAX_WORDS) -> VolumeCommand(Action.MAX, stream = stream)
                hasUp -> VolumeCommand(Action.UP, stream = stream)
                hasDown -> VolumeCommand(Action.DOWN, stream = stream)
                containsAny(l, GET_WORDS) || l.endsWith("?") -> VolumeCommand(Action.GET, stream = stream)
                else -> VolumeCommand(Action.UP, stream = stream) // "volume" alone: keep historical behaviour
            }
        }

        /** "set volume to 60", "volume 60 e uthao" – 'to'/'e' make a number absolute even with up/down words. */
        private fun isAbsolutePhrase(l: String): Boolean =
            Regex("\\b(to|at|set|করো|কর|রাখো|রাখ|দাও|দে|এ|তে)\\b").containsMatchIn(l) && !Regex("\\bby\\b").containsMatchIn(l) &&
                !Regex("(\\d)\\s*(%|percent|পার্সেন্ট|শতাংশ)?\\s*(up|down|বাড়াও|কমাও|বাড়া|কমা|বাড়িয়ে|কমিয়ে)").containsMatchIn(l)

        private fun containsAny(l: String, words: List<String>) = words.any { w ->
            if (w.any { it > '\u007f' }) l.contains(w) else Regex("(^|[^a-z])" + Regex.escape(w) + "([^a-z]|$)").containsMatchIn(l)
        }
    }
}

object VolumeMath {
    /** Deterministic percent → stream index: round(percent / 100 * max), clamped to [0, max]. */
    fun percentToIndex(percent: Int, max: Int): Int {
        if (max <= 0) return 0
        return (percent.coerceIn(0, 100) / 100.0 * max).roundToInt().coerceIn(0, max)
    }

    /** Stream index → percent: round(index * 100 / max), clamped to [0, 100]. */
    fun indexToPercent(index: Int, max: Int): Int {
        if (max <= 0) return 0
        return (index.coerceIn(0, max) * 100.0 / max).roundToInt().coerceIn(0, 100)
    }

    /**
     * Index to use for "current ± delta percentage points". The step is expressed in percent, so on a
     * 15-step device "+1 %" from 50 % (index 8 = 53 %) resolves to 51 % → index round(7.65) = 8; when
     * rounding would leave the index unchanged but the user asked to move, we move by one index in the
     * requested direction so the command always has an audible effect (unless already at a bound).
     */
    fun adjustIndex(currentIndex: Int, deltaPercent: Int, max: Int): Int {
        if (max <= 0 || deltaPercent == 0) return currentIndex.coerceIn(0, max.coerceAtLeast(0))
        val currentPercent = indexToPercent(currentIndex, max)
        val target = (currentPercent + deltaPercent).coerceIn(0, 100)
        var idx = percentToIndex(target, max)
        if (idx == currentIndex && target != currentPercent) idx = (currentIndex + if (deltaPercent > 0) 1 else -1).coerceIn(0, max)
        return idx
    }
}

/** Minimal abstraction over AudioManager so the control logic is unit-testable on the JVM. */
interface VolumeBackend {
    fun maxIndex(stream: String): Int
    fun currentIndex(stream: String): Int
    fun setIndex(stream: String, index: Int)
    fun setMuted(stream: String, muted: Boolean)
    fun isMuted(stream: String): Boolean
}

/** Outcome of a volume operation, read back from the backend after acting. */
data class VolumeOutcome(val stream: String, val percent: Int, val index: Int, val max: Int, val muted: Boolean, val changed: Boolean) {
    val message: String get() = if (muted) "${streamLabel(stream)} volume is muted." else "${streamLabel(stream)} volume is now $percent%."
    val spoken: String get() = if (muted) "${streamLabel(stream)} muted" else "Volume $percent percent"
    private fun streamLabel(s: String) = when (s) { "ring" -> "Ringer"; "alarm" -> "Alarm"; "call" -> "Call"; "notification" -> "Notification"; else -> "Media" }
}

/** Executes a [VolumeCommand] against a [VolumeBackend]; single source of truth for volume semantics. */
class VolumeController(private val backend: VolumeBackend) {
    fun apply(cmd: VolumeCommand): VolumeOutcome {
        val stream = cmd.stream
        val max = backend.maxIndex(stream)
        val before = backend.currentIndex(stream)
        when (cmd.action) {
            VolumeCommand.Action.SET -> backend.setIndex(stream, VolumeMath.percentToIndex(cmd.value ?: 50, max))
            VolumeCommand.Action.MAX -> backend.setIndex(stream, max)
            VolumeCommand.Action.UP -> backend.setIndex(stream, VolumeMath.adjustIndex(before, +1, max))
            VolumeCommand.Action.DOWN -> backend.setIndex(stream, VolumeMath.adjustIndex(before, -1, max))
            VolumeCommand.Action.ADJUST_PERCENT -> backend.setIndex(stream, VolumeMath.adjustIndex(before, cmd.value ?: 0, max))
            VolumeCommand.Action.MUTE -> backend.setMuted(stream, true)
            VolumeCommand.Action.UNMUTE -> backend.setMuted(stream, false)
            VolumeCommand.Action.GET -> {}
        }
        // Always report what the device actually did, never the requested value.
        val after = backend.currentIndex(stream)
        val muted = backend.isMuted(stream)
        return VolumeOutcome(stream, VolumeMath.indexToPercent(after, max), after, max, muted, after != before || cmd.action == VolumeCommand.Action.MUTE || cmd.action == VolumeCommand.Action.UNMUTE)
    }

    companion object {
        /** Builds a command from tool arguments (LLM providers pass these directly). */
        fun fromToolArgs(args: Map<String, Any?>): VolumeCommand {
            val stream = args.str("stream", "media").ifBlank { "media" }
            return when (args.str("action", "up").lowercase(Locale.ROOT)) {
                "set" -> VolumeCommand(VolumeCommand.Action.SET, args.int("level", 50).coerceIn(0, 100), stream)
                "adjust" -> VolumeCommand(VolumeCommand.Action.ADJUST_PERCENT, args.int("delta", 1).coerceIn(-100, 100), stream)
                "down" -> if (args.containsKey("delta")) VolumeCommand(VolumeCommand.Action.ADJUST_PERCENT, -kotlin.math.abs(args.int("delta", 1)), stream) else VolumeCommand(VolumeCommand.Action.DOWN, stream = stream)
                "up" -> if (args.containsKey("delta")) VolumeCommand(VolumeCommand.Action.ADJUST_PERCENT, kotlin.math.abs(args.int("delta", 1)), stream) else VolumeCommand(VolumeCommand.Action.UP, stream = stream)
                "mute" -> VolumeCommand(VolumeCommand.Action.MUTE, stream = stream)
                "unmute" -> VolumeCommand(VolumeCommand.Action.UNMUTE, stream = stream)
                "max" -> VolumeCommand(VolumeCommand.Action.MAX, stream = stream)
                "get", "read", "current" -> VolumeCommand(VolumeCommand.Action.GET, stream = stream)
                else -> VolumeCommand(VolumeCommand.Action.UP, stream = stream)
            }
        }
    }
}
