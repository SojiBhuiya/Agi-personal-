package com.agi.assistant.core.update

/** Observable state of the update check. Phase 1 stops at UPDATE_AVAILABLE / UP_TO_DATE. */
sealed class UpdateState {
    /** No check has run yet in this process. */
    object Idle : UpdateState()
    object Checking : UpdateState()
    data class UpdateAvailable(val info: UpdateInfo) : UpdateState()
    /** [latest] is the release that was inspected (may be null if it had no APK but is not newer). */
    data class UpToDate(val installedVersion: String, val latest: UpdateInfo?) : UpdateState()
    data class Error(val reason: UpdateError, val message: String, val cause: Throwable? = null) : UpdateState()

    val isTerminal: Boolean get() = this !is Idle && this !is Checking
}

enum class UpdateError {
    /** No connectivity, DNS failure, timeout, TLS problem. */
    NETWORK,
    /** GitHub answered with a non-2xx status (404 = no releases yet, 403 = rate limited). */
    HTTP,
    /** Body was not the JSON shape we expect. */
    MALFORMED_RESPONSE,
    /** Release exists but has no `.apk` asset. */
    NO_APK_ASSET,
    /** Tag could not be parsed as a version. */
    INVALID_VERSION,
    UNKNOWN,
}

/** Result of a single check, independent of any UI state holder. */
sealed class UpdateCheckResult {
    data class Success(val info: UpdateInfo) : UpdateCheckResult()
    data class Failure(val reason: UpdateError, val message: String, val cause: Throwable? = null) : UpdateCheckResult()
}
