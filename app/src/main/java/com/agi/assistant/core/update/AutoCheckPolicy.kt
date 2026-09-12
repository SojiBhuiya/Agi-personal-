package com.agi.assistant.core.update

/**
 * Decides whether an *automatic* update check may run now. Pure Kotlin, unit-tested.
 *
 *  - Cooldown: at most one automatic request per [cooldownMs] (default 6 h), persisted in
 *    [UpdatePreferences.lastCheckedAt] so restarts do not reset it.
 *  - In-process guard: at most one automatic check per [minGapMs] (default 60 s) regardless of
 *    persistence – protects against Activity recreation / rapid navigation loops.
 *  - Offline: never fires (the app stays usable; nothing is shown).
 *  - Never while a check, download or install is in progress.
 */
class AutoCheckPolicy(
    private val prefs: UpdatePreferences,
    private val cooldownMs: Long = 6 * 60 * 60 * 1000L,
    private val minGapMs: Long = 60 * 1000L,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    @Volatile private var lastAttemptAt = 0L

    enum class Decision { CHECK, OFFLINE, COOLDOWN, BUSY, THROTTLED }

    fun decide(online: Boolean, state: UpdateState): Decision {
        if (!online) return Decision.OFFLINE
        if (state is UpdateState.Checking || state is UpdateState.Downloading || state is UpdateState.InstallerLaunched) return Decision.BUSY
        val t = now()
        if (t - lastAttemptAt < minGapMs) return Decision.THROTTLED
        if (t - prefs.lastCheckedAt < cooldownMs) return Decision.COOLDOWN
        return Decision.CHECK
    }

    /** Call when an automatic check is actually started. */
    fun markAttempt() { lastAttemptAt = now() }

    /** Call when any check (automatic or manual) finished; records time and the tag that was seen. */
    fun recordResult(result: UpdateCheckResult) {
        prefs.lastCheckedAt = now()
        if (result is UpdateCheckResult.Success) prefs.lastSeenTag = result.info.releaseTag
    }

    /** True when [info] is a release we have never seen before (useful for logging/analytics). */
    fun isNewSighting(info: UpdateInfo): Boolean = prefs.lastSeenTag != info.releaseTag
}
