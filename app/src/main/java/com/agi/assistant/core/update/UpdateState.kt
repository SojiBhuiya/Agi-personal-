package com.agi.assistant.core.update

/**
 * Observable state of the update pipeline:
 * Idle → Checking → UpdateAvailable → Downloading → ReadyToInstall → InstallerLaunched → (system installs; app restarts as new version)
 *                                   ↘ DownloadFailed (retry → Downloading)      ↘ InstallationError (retry → ReadyToInstall)
 */
sealed class UpdateState {
    /** No check has run yet in this process. */
    object Idle : UpdateState()
    object Checking : UpdateState()
    data class UpdateAvailable(val info: UpdateInfo) : UpdateState()
    /** [latest] is the release that was inspected (may be null if it had no APK but is not newer). */
    data class UpToDate(val installedVersion: String, val latest: UpdateInfo?) : UpdateState()
    data class Error(val reason: UpdateError, val message: String, val cause: Throwable? = null) : UpdateState()

    /** APK download in flight. [percent] is -1 while the total size is unknown. */
    data class Downloading(val info: UpdateInfo, val bytesDownloaded: Long, val totalBytes: Long) : UpdateState() {
        val percent: Int get() = if (totalBytes > 0) ((bytesDownloaded * 100) / totalBytes).toInt().coerceIn(0, 100) else -1
    }
    /** File is complete (and checksum-verified when a checksum was published). Nothing is installed yet. */
    data class ReadyToInstall(val info: UpdateInfo, val file: java.io.File, val sha256: String, val verified: Boolean) : UpdateState()
    data class DownloadFailed(val info: UpdateInfo, val reason: DownloadError, val message: String, val cause: Throwable? = null) : UpdateState()

    /**
     * The Android package installer UI has been opened for [file]. The user still has to confirm;
     * the app never knows the outcome for sure until it restarts as the new version (or the
     * installer returns a failure). Nothing may present this as "installed".
     */
    data class InstallerLaunched(val info: UpdateInfo, val file: java.io.File, val launchedAt: Long = System.currentTimeMillis()) : UpdateState()
    /** Install could not start or the installer reported a failure. The staged file is kept unless [fileDiscarded]. */
    data class InstallationError(val info: UpdateInfo, val file: java.io.File, val reason: InstallError, val message: String, val fileDiscarded: Boolean = false) : UpdateState()

    val isTerminal: Boolean get() = this !is Idle && this !is Checking && this !is Downloading && this !is InstallerLaunched
}

enum class InstallError {
    /** "Install unknown apps" is not allowed for this app; the settings screen was/should be opened. */
    PERMISSION_REQUIRED,
    /** Staged APK disappeared or is empty. */
    FILE_MISSING,
    /** The package inside the APK is not this app (applicationId mismatch). */
    PACKAGE_MISMATCH,
    /** APK versionCode is not higher than the installed one – Android would refuse the update. */
    NOT_NEWER,
    /** APK could not be parsed by the platform. */
    INVALID_APK,
    /** No activity can handle the install intent / launch threw. */
    NO_INSTALLER,
    /** User dismissed the installer. */
    USER_CANCELLED,
    /** Installer reported INSTALL_FAILED_UPDATE_INCOMPATIBLE / inconsistent certificates (different signing key). */
    SIGNATURE_MISMATCH,
    /** The staged file was never checksum-verified; policy refuses to launch the installer for it. */
    UNVERIFIED,
    /** Installer reported a conflicting package/provider/permission. */
    PACKAGE_CONFLICT,
    /** APK requires a newer Android or an unsupported ABI. */
    INCOMPATIBLE,
    INSUFFICIENT_STORAGE,
    /** Installer said it failed but gave no recognisable code ("App not installed"). */
    INSTALL_FAILED,
    UNKNOWN,
}

enum class DownloadError {
    NETWORK, HTTP, TIMEOUT, INTERRUPTED, INSUFFICIENT_STORAGE, INVALID_RESPONSE, CHECKSUM_MISMATCH,
    /** The release publishes no readable SHA-256; policy refuses to stage an unverifiable APK. */
    CHECKSUM_UNAVAILABLE,
    CANCELLED, STORAGE_IO, UNKNOWN
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
