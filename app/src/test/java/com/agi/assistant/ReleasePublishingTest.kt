package com.agi.assistant

import com.agi.assistant.core.update.*
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * End-to-end contract between the CI publisher (.github/workflows/release.yml +
 * scripts/release_notes.sh) and the in-app updater: a release published by the workflow must be
 * discovered, its APK and checksum assets selected, the sha256 extracted, and version comparison
 * must let an installed 0.2.0 see a future 0.2.1. Deterministic; no network.
 */
object ReleasePublishingTest {
    private var fails = 0
    private var passed = 0
    private fun check(name: String, cond: Boolean, detail: Any? = "") { if (cond) { passed++; println("  ok   $name") } else { fails++; println("  FAIL $name  -> $detail") } }

    private const val SHA_021 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private const val OWNER = "SojiBhuiya"; private const val REPO = "Agi-personal-"

    /** Same body layout scripts/release_notes.sh emits (kept in sync by the "script matches" check below). */
    private fun notes(vn: String, vc: Int, apk: String, sha: String) = """
        ## AGI Assistant $vn

        ### What's New
        - Something new

        ### Install
        Download **$apk** below and open it on your phone.

        ### Verification
        ```
        $sha  $apk
        ```

        <!-- machine-readable, used by the in-app updater; keep these lines -->
        versionName: $vn
        versionCode: $vc
        sha256: $sha
    """.trimIndent()

    private fun asset(tag: String, name: String, size: Long, type: String) =
        """{"name":"$name","size":$size,"state":"uploaded","content_type":"$type","browser_download_url":"https://github.com/$OWNER/$REPO/releases/download/$tag/$name"}"""

    /** Release JSON exactly as `gh release create v<vn> <apk> <apk>.sha256 --notes-file` produces via the API. */
    private fun publishedRelease(vn: String, vc: Int, sha: String = SHA_021, prerelease: Boolean = false, draft: Boolean = false): String {
        val tag = "v$vn"; val apk = "agi-assistant-$vn-release.apk"
        val body = notes(vn, vc, apk, sha).replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        return """{"tag_name":"$tag","name":"AGI Assistant $vn","draft":$draft,"prerelease":$prerelease,"published_at":"2026-09-13T12:00:00Z",
            "html_url":"https://github.com/$OWNER/$REPO/releases/tag/$tag","body":"$body",
            "assets":[${asset(tag, "$apk.sha256", 99, "text/plain")},${asset(tag, apk, 1189167, "application/vnd.android.package-archive")}]}"""
    }

    private fun checker(code: Int, body: String) = GitHubReleaseUpdateChecker(fetch = { GitHubReleaseUpdateChecker.HttpResponse(code, body) })

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        println("Release asset discovery (workflow-published layout):")
        val rel = GitHubReleaseParser.parse(publishedRelease("0.2.1", 3))
        check("tag v0.2.1 -> version 0.2.1", rel.tag == "v0.2.1" && rel.version.toString() == "0.2.1")
        check("APK asset selected (not the .sha256 asset)", rel.apkName == "agi-assistant-0.2.1-release.apk", rel.apkName)
        check("APK url is the HTTPS release download URL", rel.apkUrl == "https://github.com/$OWNER/$REPO/releases/download/v0.2.1/agi-assistant-0.2.1-release.apk", rel.apkUrl)
        check("APK size from asset", rel.apkSize == 1189167L)
        check("checksum asset <apk>.sha256 selected", rel.checksumAssetUrl == "https://github.com/$OWNER/$REPO/releases/download/v0.2.1/agi-assistant-0.2.1-release.apk.sha256", rel.checksumAssetUrl)
        check("sha256 extracted from notes", rel.sha256 == SHA_021)
        check("versionCode extracted from notes", rel.versionCode == 3L)
        check("not mandatory by default", !rel.mandatory)
        check("checksum-file body (sha256sum format) parses", GitHubReleaseParser.parseChecksumFile("$SHA_021  agi-assistant-0.2.1-release.apk\n", "agi-assistant-0.2.1-release.apk") == SHA_021)
        check("asset order irrelevant (apk first)", GitHubReleaseParser.parse("""{"tag_name":"v0.2.1","body":"","assets":[${asset("v0.2.1", "agi-assistant-0.2.1-release.apk", 5, "application/vnd.android.package-archive")},${asset("v0.2.1", "agi-assistant-0.2.1-release.apk.sha256", 1, "text/plain")}]}""").let { it.apkName == "agi-assistant-0.2.1-release.apk" && it.checksumAssetUrl!!.endsWith(".sha256") })

        println("Version comparison (installed 0.2.0 build 2):")
        suspend fun result(vn: String, vc: Int) = checker(200, publishedRelease(vn, vc)).check("0.2.0", 2)
        check("0.2.1 (3) is an update", (result("0.2.1", 3) as UpdateCheckResult.Success).info.isNewerVersion)
        check("0.3.0 (4) is an update", (result("0.3.0", 4) as UpdateCheckResult.Success).info.isNewerVersion)
        check("1.0.0 (10) is an update", (result("1.0.0", 10) as UpdateCheckResult.Success).info.isNewerVersion)
        check("0.2.0 (2) — same release — is NOT an update", !(result("0.2.0", 2) as UpdateCheckResult.Success).info.isNewerVersion)
        check("0.2.0 (3) — same name, higher code — IS an update", (result("0.2.0", 3) as UpdateCheckResult.Success).info.isNewerVersion)
        check("0.1.9 (1) — older — is NOT an update", !(result("0.1.9", 1) as UpdateCheckResult.Success).info.isNewerVersion)
        check("installed 0.2.1 sees 0.2.1 as up to date", !(checker(200, publishedRelease("0.2.1", 3)).check("0.2.1", 3) as UpdateCheckResult.Success).info.isNewerVersion)
        check("v-prefixed installed name compares", (checker(200, publishedRelease("0.2.1", 3)).check("v0.2.0", 2) as UpdateCheckResult.Success).info.isNewerVersion)
        check("tag must be semantic: 'release-1' rejected", GitHubReleaseUpdateChecker(fetch = { GitHubReleaseUpdateChecker.HttpResponse(200, publishedRelease("0.2.1", 3).replace("\"tag_name\":\"v0.2.1\"", "\"tag_name\":\"release-1\"")) }).check("0.2.0", 2).let { it is UpdateCheckResult.Failure && it.reason == UpdateError.INVALID_VERSION })

        println("UpdateInfo handed to the downloader/dialog:")
        val info = (result("0.2.1", 3) as UpdateCheckResult.Success).info
        check("download url + asset name + sha + checksum url populated", info.apkDownloadUrl.endsWith("/v0.2.1/agi-assistant-0.2.1-release.apk") && info.apkAssetName == "agi-assistant-0.2.1-release.apk" && info.apkSha256 == SHA_021 && info.checksumAssetUrl!!.endsWith(".apk.sha256"))
        check("release tag/name/notes/html url populated", info.releaseTag == "v0.2.1" && info.releaseName == "AGI Assistant 0.2.1" && info.releaseNotes.contains("Something new") && info.htmlUrl.endsWith("/releases/tag/v0.2.1"))
        check("What's New excludes machine markers", UpdateMessages.whatsNew(info.releaseNotes, 10).let { !it.contains("versionCode:") && !it.contains("sha256:") && it.contains("Something new") }, UpdateMessages.whatsNew(info.releaseNotes, 10))

        println("Missing / broken release handling:")
        check("404 (no releases yet) -> HTTP error, friendly text", checker(404, """{"message":"Not Found"}""").check("0.2.0", 2).let { it is UpdateCheckResult.Failure && it.reason == UpdateError.HTTP && it.message.contains("No releases") })
        check("release without apk asset -> NO_APK_ASSET", checker(200, publishedRelease("0.2.1", 3).replace("agi-assistant-0.2.1-release.apk\"", "agi-assistant-0.2.1-release.txt\"").replace("application/vnd.android.package-archive", "text/plain")).check("0.2.0", 2).let { it is UpdateCheckResult.Failure && it.reason == UpdateError.NO_APK_ASSET })
        check("only .sha256 asset -> NO_APK_ASSET", checker(200, """{"tag_name":"v0.2.1","assets":[${asset("v0.2.1", "x.apk.sha256", 1, "text/plain")}]}""").check("0.2.0", 2).let { it is UpdateCheckResult.Failure && it.reason == UpdateError.NO_APK_ASSET })
        check("empty body -> MALFORMED", checker(200, "").check("0.2.0", 2).let { it is UpdateCheckResult.Failure && it.reason == UpdateError.MALFORMED_RESPONSE })
        check("HTML instead of JSON -> MALFORMED", checker(200, "<html></html>").check("0.2.0", 2).let { it is UpdateCheckResult.Failure && it.reason == UpdateError.MALFORMED_RESPONSE })
        check("rate limit 403 -> HTTP with hint", checker(403, "").check("0.2.0", 2).let { it is UpdateCheckResult.Failure && it.message.contains("rate limit") })
        check("500 -> HTTP error", checker(500, "").check("0.2.0", 2).let { it is UpdateCheckResult.Failure && it.reason == UpdateError.HTTP })
        check("http:// asset url rejected", checker(200, publishedRelease("0.2.1", 3).replace("https://github.com/$OWNER/$REPO/releases/download/v0.2.1/agi-assistant-0.2.1-release.apk\"", "http://github.com/x.apk\"")).check("0.2.0", 2) is UpdateCheckResult.Failure)
        check("notes without sha -> sha null, checksum asset still used", GitHubReleaseParser.parse(publishedRelease("0.2.1", 3).replace(Regex("sha256: [0-9a-f]{64}"), "").replace(Regex("[0-9a-f]{64}  agi"), "agi")).let { it.sha256 == null && it.checksumAssetUrl != null })

        println("Publisher script contract (scripts/release_notes.sh):")
        val script = listOf("scripts/release_notes.sh", "../scripts/release_notes.sh", "../../scripts/release_notes.sh").map(::File).firstOrNull { it.exists() }
        check("script exists", script != null)
        script?.readText()?.let { s ->
            check("script emits versionCode marker", s.contains("versionCode: \$VC"))
            check("script emits sha256 marker + sha256sum line", s.contains("sha256: \${SHA,,}") && s.contains("\"\${SHA,,}\" \"\$APK\""))
            check("script rejects bad sha / versionCode", s.contains("[0-9a-fA-F]{64}") && s.contains("^[0-9]+\$"))
        }
        val wf = listOf(".github/workflows/release.yml", "../.github/workflows/release.yml", "../../.github/workflows/release.yml").map(::File).firstOrNull { it.exists() }
        check("workflow exists", wf != null)
        wf?.readText()?.let { w ->
            check("workflow publishes a GitHub Release with gh", w.contains("gh release create") || w.contains("gh release upload"))
            check("workflow tags v<versionName> from the APK, not a hard-coded version", w.contains("TAG=\"v\$VN\"") && !Regex("gh release create v\\d").containsMatchIn(w))
            check("workflow uploads apk + .sha256 assets", w.contains("out/\$APK_NAME") && w.contains("out/\$APK_NAME.sha256"))
            check("workflow uses release_notes.sh", w.contains("scripts/release_notes.sh"))
            check("workflow has contents: write for publishing job", w.contains("contents: write"))
            check("workflow never downloads artifacts for the release", !w.contains("actions/download-artifact"))
        }

        println("\n$passed passed, $fails failed")
        if (fails > 0) System.exit(1)
    }
}
