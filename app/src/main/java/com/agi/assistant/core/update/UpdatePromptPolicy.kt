package com.agi.assistant.core.update

import java.util.Locale

/** Persistence needed by [UpdatePromptPolicy]; backed by SharedPreferences in the app, by a map in tests. */
interface UpdatePreferences {
    /** Release tag the user tapped "Later" on, or null. */
    var postponedTag: String?
    /** Epoch millis of the last postponement. */
    var postponedAt: Long
    /** Epoch millis of the last *completed* automatic or manual check (persisted across restarts). */
    var lastCheckedAt: Long
    /** Release tag seen by the last successful check (null = none / no releases). */
    var lastSeenTag: String?
    /** Tag of the release the dialog was last shown for, and when. */
    var lastPromptedTag: String?
    var lastPromptedAt: Long
}

/**
 * Decides whether the non-intrusive update dialog should be shown for an
 * [UpdateInfo]. Pure Kotlin so the rules are unit-testable:
 *
 *  1. Only for genuinely newer releases.
 *  2. At most once per process ("session") per release tag – prevents duplicate dialogs
 *     across Activity recreation and repeated checks.
 *  3. If the user postponed this tag, stay quiet for [snoozeMs] (default 24 h)
 *     unless the release is mandatory (mandatory releases are shown every session,
 *     still only once per session).
 *  4. A different (newer) tag resets the postponement.
 *  5. Across process restarts, the same non-mandatory release is not re-prompted automatically
 *     within [repromptMs] (default 24 h) of the last time its dialog was shown. A manual
 *     "Check for updates" bypasses this via [resetSession].
 */
class UpdatePromptPolicy(
    private val prefs: UpdatePreferences,
    private val snoozeMs: Long = 24 * 60 * 60 * 1000L,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val repromptMs: Long = 24 * 60 * 60 * 1000L,
) {
    private val shownThisSession = HashSet<String>()

    fun shouldPrompt(info: UpdateInfo): Boolean {
        if (!info.isNewerVersion) return false
        if (info.releaseTag in shownThisSession) return false
        if (info.isMandatory) return true
        val postponed = prefs.postponedTag
        if (postponed == info.releaseTag && now() - prefs.postponedAt < snoozeMs) return false
        if (!userRequested && prefs.lastPromptedTag == info.releaseTag && now() - prefs.lastPromptedAt < repromptMs) return false
        return true
    }

    /** Call when the dialog is actually displayed. */
    fun markShown(info: UpdateInfo) {
        shownThisSession += info.releaseTag
        prefs.lastPromptedTag = info.releaseTag; prefs.lastPromptedAt = now()
        userRequested = false
    }

    /** True between an explicit "Check for updates" tap and the next dialog (persisted re-prompt limit is bypassed). */
    @Volatile private var userRequested = false

    /** Call when the user taps "Later". Mandatory releases cannot be postponed. */
    fun postpone(info: UpdateInfo): Boolean {
        if (info.isMandatory) return false
        prefs.postponedTag = info.releaseTag
        prefs.postponedAt = now()
        return true
    }

    /** Used by an explicit "Check for updates" tap: the user asked, so always allow the dialog again. */
    fun resetSession() { shownThisSession.clear(); userRequested = true }

    fun isPostponed(info: UpdateInfo): Boolean =
        !info.isMandatory && prefs.postponedTag == info.releaseTag && now() - prefs.postponedAt < snoozeMs
}

/** Human-readable strings for every [UpdateState]; shared by the Settings card, banner and dialog. */
object UpdateMessages {
    const val CHECKING = "Checking for updates..."
    const val UP_TO_DATE = "You’re using the latest version."
    const val ERROR = "Unable to check for updates. Please try again later."
    const val ERROR_OFFLINE = "No internet connection. Connect to Wi‑Fi or mobile data, then check again."
    const val ERROR_DNS = "Couldn’t find GitHub (DNS). Your network gave no address for api.github.com – check the connection or try another network."
    const val ERROR_TLS = "Secure connection to GitHub failed. Check the phone’s date & time or try another network."
    const val ERROR_TIMEOUT = "GitHub took too long to respond. Check your connection and try again."
    const val ERROR_GITHUB = "GitHub returned an error. Please try again later."
    const val ERROR_RESPONSE = "GitHub’s release information could not be read. Please try again later."
    const val TITLE = "New Update Available"
    const val DOWNLOADING = "Downloading update..."
    const val DOWNLOADED = "Update downloaded"
    const val DOWNLOAD_FAILED = "Download failed"
    const val INSTALLER_LAUNCHED = "Waiting for Android to finish installing"
    const val INSTALL_FAILED = "Installation failed"
    const val INSTALL_PERMISSION = "Allow installing updates"

    fun mb(bytes: Long): String = "%.1f MB".format(Locale.US, bytes / 1024.0 / 1024.0)

    /** "1.2 MB of 4.8 MB" or "1.2 MB" when the total is unknown. */
    fun progressDetail(done: Long, total: Long): String =
        if (total > 0) "${mb(done)} of ${mb(total)}" else mb(done)

    fun statusLine(state: UpdateState, installedVersion: String): String = when (state) {
        UpdateState.Idle -> "Updates are fetched from GitHub Releases."
        UpdateState.Checking -> CHECKING
        is UpdateState.UpToDate -> UP_TO_DATE + " (${state.installedVersion})"
        is UpdateState.UpdateAvailable -> "New version available: ${state.info.versionName}" +
            (state.info.versionCode?.let { " (build $it)" } ?: "") + (if (state.info.isMandatory) " • required" else "")
        is UpdateState.Downloading -> DOWNLOADING + (if (state.percent >= 0) " ${state.percent}%" else "")
        is UpdateState.ReadyToInstall -> "$DOWNLOADED: ${state.info.versionName}" + if (state.verified) " (verified)" else " (unverified – will not be installed)"
        is UpdateState.DownloadFailed -> "$DOWNLOAD_FAILED: ${state.message}"
        is UpdateState.InstallerLaunched -> "$INSTALLER_LAUNCHED ${state.info.versionName}. Confirm the prompt; the app restarts when done."
        is UpdateState.InstallationError -> when (state.reason) {
            InstallError.PERMISSION_REQUIRED -> "$INSTALL_PERMISSION: ${state.message}"
            InstallError.UNVERIFIED -> "Update not installed: ${state.message}"
            else -> "$INSTALL_FAILED: ${state.message}"
        }
        is UpdateState.Error -> when (state.reason) {
            UpdateError.HTTP -> if (state.message.startsWith("No releases")) UP_TO_DATE + " No releases published yet." else ERROR_GITHUB
            UpdateError.NO_APK_ASSET -> "The latest release has no Android package yet. Please try again later."
            UpdateError.NO_INTERNET -> ERROR_OFFLINE
            UpdateError.DNS -> ERROR_DNS
            UpdateError.TLS -> ERROR_TLS
            UpdateError.TIMEOUT -> ERROR_TIMEOUT
            UpdateError.MALFORMED_RESPONSE, UpdateError.INVALID_VERSION -> ERROR_RESPONSE
            else -> ERROR
        }
    }

    /** Formats the "What's New" section from GitHub Markdown into plain lines for a dialog. */
    fun whatsNew(notes: String, maxLines: Int = 12): String {
        val lines = notes.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            // Machine-readable markers, checksum lines, HTML comments and code fences are not "news".
            .filterNot { Regex("(?i)^(mandatory|versionCode|versionName|sha-?256)\\s*[:=]").containsMatchIn(it) }
            .filterNot { Regex("(?i)^[0-9a-f]{64}(\\s|$)").containsMatchIn(it) || it.startsWith("<!--") || it.startsWith("```") }
            .map { line ->
                line.replace(Regex("^#+\\s*"), "")
                    .replace(Regex("^[-*+]\\s+"), "• ")
                    .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
                    .replace(Regex("`(.+?)`"), "$1")
                    .replace(Regex("(?i)\\[mandatory]"), "").trim()
            }.filter { it.isNotEmpty() }
        val shown = lines.take(maxLines)
        return shown.joinToString("\n") + if (lines.size > maxLines) "\n…" else ""
    }
}
