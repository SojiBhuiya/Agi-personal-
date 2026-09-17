package com.agi.assistant.core.agent

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Formatting helpers for response timing (pure; JVM-tested). */
object Latency {
    /** `420ms` under one second, `3.7s` from one second on. */
    fun format(elapsedMs: Long): String {
        val ms = elapsedMs.coerceAtLeast(0)
        return if (ms < 1000) "${ms}ms" else String.format(Locale.US, "%.1fs", ms / 1000.0)
    }

    /** Indicator shown on the final assistant reply, e.g. `⚡ 3.7s`. */
    fun indicator(elapsedMs: Long) = "⚡ " + format(elapsedMs)

    /** Indicator for a failed request, e.g. `⚠️ Failed • 4.2s`. */
    fun failedIndicator(elapsedMs: Long) = "⚠️ Failed • " + format(elapsedMs)

    /** Wall-clock time of a message as `HH:mm:ss` in [zone] (device local zone by default). */
    fun clock(epochMs: Long, zone: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).apply { timeZone = zone }.format(Date(epochMs))

    /** Elapsed milliseconds since a `System.nanoTime()` mark (monotonic – unaffected by clock changes). */
    fun sinceNanos(startNanos: Long, nowNanos: Long = System.nanoTime()): Long = (nowNanos - startNanos) / 1_000_000
}
