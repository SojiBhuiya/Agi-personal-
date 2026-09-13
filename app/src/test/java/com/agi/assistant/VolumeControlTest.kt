package com.agi.assistant

import com.agi.assistant.core.ai.providers.LocalRuleProvider
import com.agi.assistant.core.tools.*

/**
 * Plain-JVM tests for the volume feature: parser (EN + BN, Bengali numerals), percent<->index math,
 * and the controller against a fake AudioManager. Runs via scripts/run_tests.sh and Gradle coreTests.
 */
object VolumeControlTest {
    private var failures = 0
    private var passed = 0

    private fun check(name: String, ok: Boolean, detail: String = "") {
        if (ok) { passed++; println("  ok   $name $detail") } else { failures++; println("  FAIL $name $detail") }
    }

    /** Fake device: 15 media steps (typical Android), 7 ring steps. */
    private class FakeAudio(var media: Int = 0, private val mediaMax: Int = 15) : VolumeBackend {
        var ring = 3; var muted = false
        override fun maxIndex(stream: String) = if (stream == "ring") 7 else mediaMax
        override fun currentIndex(stream: String) = if (stream == "ring") ring else media
        override fun setIndex(stream: String, index: Int) { require(index in 0..maxIndex(stream)); if (stream == "ring") ring = index else media = index; if (index > 0) muted = false }
        override fun setMuted(stream: String, muted: Boolean) { this.muted = muted }
        override fun isMuted(stream: String) = muted
    }

    private fun run(startPercent: Int, text: String, max: Int = 15): VolumeOutcome {
        val dev = FakeAudio(VolumeMath.percentToIndex(startPercent, max), max)
        val cmd = VolumeCommand.parse(text) ?: error("not parsed: $text")
        return VolumeController(dev).apply(cmd)
    }

    private fun parsed(text: String, action: String, vararg kv: Pair<String, Any?>) {
        val args = VolumeCommand.parse(text)?.toToolArgs()
        val ok = args != null && args["action"] == action && kv.all { (k, v) -> args[k].toString() == v.toString() }
        check("parse \"$text\"", ok, "-> $args")
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("Volume parser (absolute):")
        for (t in listOf("Volume 50 করো", "volume 50", "ভলিউম ৫০ করো", "set volume to 50", "volume to 50%", "ভলিউম ৫০ শতাংশ করো", "Volume 50 এ set করো"))
            parsed(t, "set", "level" to 50)
        parsed("Volume 60 করো", "set", "level" to 60); parsed("ভলিউম ৬০", "set", "level" to 60)
        parsed("Volume 100 করো", "set", "level" to 100); parsed("Volume 0 করো", "set", "level" to 0)
        parsed("ভলিউম ১০০ করো", "set", "level" to 100); parsed("volume 250", "set", "level" to 100)
        println("Volume parser (relative):")
        parsed("Volume up", "up"); parsed("volume down", "down"); parsed("Turn the volume up", "up")
        parsed("ভলিউম বাড়াও", "up"); parsed("ভলিউম কমাও", "down"); parsed("ভলিউম একটু কমিয়ে দাও", "down")
        parsed("Volume up 1%", "adjust", "delta" to 1); parsed("Volume down 1%", "adjust", "delta" to -1)
        parsed("volume up by 10", "adjust", "delta" to 10); parsed("ভলিউম ১০ বাড়াও", "adjust", "delta" to 10); parsed("ভলিউম ৫% কমাও", "adjust", "delta" to -5)
        println("Volume parser (other):")
        parsed("mute", "mute"); parsed("unmute", "unmute"); parsed("ভলিউম মিউট করো", "mute"); parsed("volume max", "max"); parsed("ভলিউম সর্বোচ্চ করো", "max")
        parsed("what is the volume", "get"); parsed("ভলিউম কত", "get"); parsed("ring volume 50", "set", "level" to 50, "stream" to "ring"); parsed("alarm volume up", "up", "stream" to "alarm")
        check("non-volume text ignored", VolumeCommand.parse("open youtube") == null && VolumeCommand.parse("brightness 50") == null)
        check("bangla digits", BanglaDigits.toAscii("০১২৩৪৫৬৭৮৯ abc ৫০%") == "0123456789 abc 50%")

        println("LocalRuleProvider routing:")
        val p = LocalRuleProvider()
        val plan = p.plan("ভলিউম ৫০ করো")
        check("planner routes bangla to volume tool", plan.size == 1 && plan[0].name == "volume" && plan[0].arguments["action"] == "set" && plan[0].arguments["level"] == 50, "-> ${plan.map { it.name to it.arguments }}")
        val plan2 = p.plan("Volume up 1%")
        check("planner routes relative", plan2.size == 1 && plan2[0].arguments["action"] == "adjust" && plan2[0].arguments["delta"] == 1, "-> ${plan2.map { it.arguments }}")

        println("VolumeMath:")
        check("50% of 15 -> 8", VolumeMath.percentToIndex(50, 15) == 8); check("50% of 16 -> 8", VolumeMath.percentToIndex(50, 16) == 8)
        check("0% -> 0", VolumeMath.percentToIndex(0, 15) == 0); check("100% -> max", VolumeMath.percentToIndex(100, 15) == 15)
        check("clamp 150% -> max", VolumeMath.percentToIndex(150, 15) == 15); check("clamp -5% -> 0", VolumeMath.percentToIndex(-5, 15) == 0)
        check("index 8/15 -> 53%", VolumeMath.indexToPercent(8, 15) == 53); check("max 0 safe", VolumeMath.percentToIndex(50, 0) == 0 && VolumeMath.indexToPercent(3, 0) == 0)
        for (m in listOf(7, 15, 16, 25, 100)) check("round-trip 100 steps max=$m", (0..100).all { pct -> val i = VolumeMath.percentToIndex(pct, m); i in 0..m && kotlin.math.abs(VolumeMath.indexToPercent(i, m) - pct) <= 50 / m + 1 })

        println("Controller end-to-end (15-step device):")
        check("20% + 'Volume 50 করো' -> 50%", run(20, "Volume 50 করো").let { it.percent == 50 || it.index == 8 }, "-> ${run(20, "Volume 50 করো")}")
        check("80% + 'volume 50' -> index 8", run(80, "volume 50").index == 8)
        check("50% + 'Volume up 1%' -> higher by one step (51% on 100-step)", run(50, "Volume up 1%", 100).percent == 51 && run(50, "Volume up 1%").index == 9)
        check("50% + 'Volume down 1%' -> 49% on 100-step", run(50, "Volume down 1%", 100).percent == 49 && run(50, "Volume down 1%").index == 7)
        check("0% + down -> stays 0", run(0, "volume down").percent == 0 && run(0, "ভলিউম কমাও").index == 0)
        check("100% + up -> stays 100", run(100, "volume up").percent == 100 && run(100, "Volume up 1%").index == 15)
        check("'Volume 0 করো' -> 0", run(60, "Volume 0 করো").index == 0); check("'Volume 100 করো' -> max", run(10, "Volume 100 করো").index == 15)
        check("set is absolute not relative", run(20, "Volume 60 করো").index == run(90, "Volume 60 করো").index)
        check("ভলিউম বাড়াও moves exactly one step from 8", run(53, "ভলিউম বাড়াও").index == 9)
        check("get does not change", run(40, "ভলিউম কত").let { it.index == 6 && !it.changed })
        val dev = FakeAudio(8); val c = VolumeController(dev)
        c.apply(VolumeCommand.parse("mute")!!); check("mute reported", dev.muted && c.apply(VolumeCommand.parse("ভলিউম কত")!!).muted)
        c.apply(VolumeCommand.parse("unmute")!!); check("unmute", !dev.muted)
        c.apply(VolumeCommand.parse("ring volume 100")!!); check("ring stream separate", dev.ring == 7 && dev.media == 8)
        check("reported percent is read-back, not requested", run(0, "volume 51").let { it.index == 8 && it.percent == 53 })
        check("tool args: set level", VolumeController.fromToolArgs(mapOf("action" to "set", "level" to "70")).let { it.action == VolumeCommand.Action.SET && it.value == 70 })
        check("tool args: down delta", VolumeController.fromToolArgs(mapOf("action" to "down", "delta" to 5)).let { it.action == VolumeCommand.Action.ADJUST_PERCENT && it.value == -5 })

        println("$passed passed, $failures failed")
        if (failures > 0) System.exit(1)
    }
}
