package com.agi.assistant.core.update

/** Source of truth for "what is the latest release?". Implementations must be safe to call off the main thread. */
interface UpdateChecker {
    /**
     * Fetches the latest release and compares it with [installedVersionName].
     * Never throws; every failure mode is a [UpdateCheckResult.Failure].
     */
    suspend fun check(installedVersionName: String, installedVersionCode: Long): UpdateCheckResult
}
