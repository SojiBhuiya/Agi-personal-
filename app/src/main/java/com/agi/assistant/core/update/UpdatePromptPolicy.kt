package com.agi.assistant.core.update

/** Persistence needed by [UpdatePromptPolicy]; backed by SharedPreferences in the app, by a map in tests. */
interface UpdatePreferences {
    /** Release tag the user tapped "Later" on, or null. */
    var postponedTag: String?
    /** Epoch millis of the last postponement. */
    var postponedAt: Long
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
 */
class UpdatePromptPolicy(
    private val prefs: UpdatePreferences,
    private val snoozeMs: Long = 24 * 60 * 60 * 1000L,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val shownThisSession = HashSet<String>()

    fun shouldPrompt(info: UpdateInfo): Boolean {
        if (!info.isNewerVersion) return false
        if (info.releaseTag in shownThisSession) return false
        if (info.isMandatory) return true
        val postponed = prefs.postponedTag
        if (postponed == info.releaseTag && now() - prefs.postponedAt < snoozeMs) return false
        return true
    }

    /** Call when the dialog is actually displayed. */
    fun markShown(info: UpdateInfo) { shownThisSession += info.releaseTag }

    /** Call when the user taps "Later". Mandatory releases cannot be postponed. */
    fun postpone(info: UpdateInfo): Boolean {
        if (info.isMandatory) return false
        prefs.postponedTag = info.releaseTag
        prefs.postponedAt = now()
        return true
    }

    /** Used by an explicit "Check for updates" tap: the user asked, so always allow the dialog again. */
    fun resetSession() { shownThisSession.clear() }

    fun isPostponed(info: UpdateInfo): Boolean =
        !info.isMandatory && prefs.postponedTag == info.releaseTag && now() - prefs.postponedAt < snoozeMs
}

/** Human-readable strings for every [UpdateState]; shared by the Settings card, banner and dialog. */
object UpdateMessages {
    const val CHECKING = "Checking for updates..."
    const val UP_TO_DATE = "You’re using the latest version."
    const val ERROR = "Unable to check for updates. Please try again later."
    const val TITLE = "New Update Available"

    fun statusLine(state: UpdateState, installedVersion: String): String = when (state) {
        UpdateState.Idle -> "Updates are fetched from GitHub Releases."
        UpdateState.Checking -> CHECKING
        is UpdateState.UpToDate -> UP_TO_DATE + " (${state.installedVersion})"
        is UpdateState.UpdateAvailable -> "$TITLE: ${state.info.versionName} (you have $installedVersion)" +
            if (state.info.isMandatory) " • required" else ""
        is UpdateState.Error -> when (state.reason) {
            UpdateError.HTTP -> if (state.message.startsWith("No releases")) UP_TO_DATE + " No releases published yet." else ERROR
            UpdateError.NO_APK_ASSET -> "The latest release has no Android package yet. Please try again later."
            else -> ERROR
        }
    }

    /** Formats the "What's New" section from GitHub Markdown into plain lines for a dialog. */
    fun whatsNew(notes: String, maxLines: Int = 12): String {
        val lines = notes.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { Regex("(?i)^(mandatory|versionCode)\\s*[:=]").containsMatchIn(it) }
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
