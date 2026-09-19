package com.agi.assistant

import com.agi.assistant.core.update.*

/** Tests for the Phase 2 UI logic: prompt policy (dedupe / postpone / mandatory) and message formatting. */
object UpdateUiPolicyTest {
    private var fails = 0; private var passed = 0
    private fun check(name: String, cond: Boolean, detail: Any? = "") {
        if (cond) { passed++; println("  ok   $name") } else { fails++; println("  FAIL $name  -> $detail") }
    }

    class MemPrefs : UpdatePreferences { override var postponedTag: String? = null; override var postponedAt: Long = 0; override var lastCheckedAt: Long = 0; override var lastSeenTag: String? = null; override var lastPromptedTag: String? = null; override var lastPromptedAt: Long = 0 }

    private fun info(tag: String, newer: Boolean = true, mandatory: Boolean = false, notes: String = "- Fixed volume controls") = UpdateInfo(
        versionName = tag.removePrefix("v"), versionCode = null, releaseTag = tag, releaseName = "AGI $tag", releaseNotes = notes,
        apkDownloadUrl = "https://x/y.apk", apkAssetName = "y.apk", apkSizeBytes = 1, publishedAt = "2026-09-12T10:00:00Z",
        htmlUrl = "https://github.com/SojiBhuiya/Agi-personal-/releases/tag/$tag", isNewerVersion = newer, isMandatory = mandatory,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        println("UpdatePromptPolicy:")
        var clock = 1_000_000L
        val prefs = MemPrefs()
        val policy = UpdatePromptPolicy(prefs, snoozeMs = 24 * 3600_000L, now = { clock })
        val v2 = info("v0.2.0")

        check("not newer -> no prompt", !policy.shouldPrompt(info("v0.1.0", newer = false)))
        check("newer -> prompt", policy.shouldPrompt(v2))
        policy.markShown(v2)
        check("duplicate prevention: same tag not prompted twice in a session", !policy.shouldPrompt(v2))
        policy.resetSession()
        check("manual check resets session -> prompt again", policy.shouldPrompt(v2))

        check("postpone succeeds for optional release", policy.postpone(v2))
        check("postponed tag persisted", prefs.postponedTag == "v0.2.0" && prefs.postponedAt == clock)
        policy.resetSession()
        check("postponed release is quiet within 24h", !policy.shouldPrompt(v2) && policy.isPostponed(v2))
        clock += 23 * 3600_000L
        check("still quiet at 23h", !policy.shouldPrompt(v2))
        clock += 2 * 3600_000L
        check("prompts again after snooze expires", policy.shouldPrompt(v2) && !policy.isPostponed(v2))

        policy.postpone(v2); policy.resetSession()
        val v3 = info("v0.3.0")
        check("a newer tag ignores the old postponement", policy.shouldPrompt(v3))

        val must = info("v0.4.0", mandatory = true)
        check("mandatory cannot be postponed", !policy.postpone(must) && prefs.postponedTag != "v0.4.0")
        prefs.postponedTag = "v0.4.0"; prefs.postponedAt = clock
        check("mandatory prompts even if a stale postponement exists", policy.shouldPrompt(must))
        policy.markShown(must)
        check("mandatory still only once per session", !policy.shouldPrompt(must))

        println("Release notes markers:")
        check("mandatory: true parsed", GitHubReleaseParser.extractMandatory("Notes\nmandatory: true"))
        check("[mandatory] tag parsed", GitHubReleaseParser.extractMandatory("Security fix [MANDATORY]"))
        check("plain notes not mandatory", !GitHubReleaseParser.extractMandatory("- fixes\n- the word mandatory in prose"))
        val rel = GitHubReleaseParser.parse("""{"tag_name":"v0.2.0","body":"- fix\nmandatory: yes","assets":[{"name":"a.apk","browser_download_url":"https://h/a.apk","state":"uploaded"}]}""")
        check("parser exposes mandatory", rel.mandatory)

        println("UpdateMessages:")
        check("checking text", UpdateMessages.statusLine(UpdateState.Checking, "0.1.0") == "Checking for updates...")
        check("up to date text", UpdateMessages.statusLine(UpdateState.UpToDate("0.1.0", null), "0.1.0").startsWith("You’re using the latest version."))
        check("network error is friendly", UpdateMessages.statusLine(UpdateState.Error(UpdateError.NETWORK, "UnknownHostException: api.github.com"), "0.1.0") == "Unable to check for updates. Please try again later.")
        check("malformed error is friendly (classified)", UpdateMessages.statusLine(UpdateState.Error(UpdateError.MALFORMED_RESPONSE, "x"), "0.1.0") == UpdateMessages.ERROR_RESPONSE)
        check("no releases yet reads as up to date", UpdateMessages.statusLine(UpdateState.Error(UpdateError.HTTP, "No releases have been published yet."), "0.1.0").startsWith("You’re using the latest version."))
        check("update available line has both versions", UpdateMessages.statusLine(UpdateState.UpdateAvailable(v2), "0.1.0") == "New version available: 0.2.0")
        check("mandatory flagged in status", UpdateMessages.statusLine(UpdateState.UpdateAvailable(must), "0.1.0").endsWith("• required"))

        val notes = UpdateMessages.whatsNew("## What's New\n- Fixed **volume** controls\n* Improved `voice` commands\n\nmandatory: true\nversionCode: 3\n- Bug fixes [mandatory]")
        check("what's new: bullets normalised, markers stripped", notes == "What's New\n• Fixed volume controls\n• Improved voice commands\n• Bug fixes", notes)
        val long = UpdateMessages.whatsNew((1..20).joinToString("\n") { "- item $it" }, maxLines = 3)
        check("what's new: truncated with ellipsis", long == "• item 1\n• item 2\n• item 3\n…", long)
        check("what's new: empty notes -> empty", UpdateMessages.whatsNew("") == "")

        println("\n$passed passed, $fails failed")
        if (fails > 0) System.exit(1)
    }
}
