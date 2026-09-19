package com.agi.assistant

import com.agi.assistant.core.update.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.net.UnknownHostException

/**
 * Unit tests for the GitHub-release update system (Phase 1: check + compare).
 * Uses an injected fetch function; no network is touched.
 */
object UpdateCheckerTest {
    private var fails = 0
    private var passed = 0
    private fun check(name: String, cond: Boolean, detail: Any? = "") {
        if (cond) { passed++; println("  ok   $name") } else { fails++; println("  FAIL $name  -> $detail") }
    }

    private fun asset(name: String, size: Long = 1_000_000, url: String = "https://github.com/SojiBhuiya/Agi-personal-/releases/download/v0.2.0/$name", type: String = "application/vnd.android.package-archive", state: String = "uploaded") =
        """{"name":"$name","size":$size,"browser_download_url":"$url","content_type":"$type","state":"$state"}"""

    private fun release(tag: String, assets: List<String>, name: String = "AGI Assistant $tag", body: String = "Bug fixes", extra: String = "") = """
        {"tag_name":"$tag","name":"$name","body":${jsonStr(body)},"published_at":"2026-09-12T10:00:00Z",
         "html_url":"https://github.com/SojiBhuiya/Agi-personal-/releases/tag/$tag","draft":false,"prerelease":false,
         "assets":[${assets.joinToString(",")}]$extra}
    """.trimIndent()

    private fun jsonStr(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private fun checker(code: Int = 200, body: String = "", throwing: Throwable? = null) =
        GitHubReleaseUpdateChecker(fetch = { url ->
            check("uses HTTPS GitHub latest-release URL", url == "https://api.github.com/repos/SojiBhuiya/Agi-personal-/releases/latest", url)
            throwing?.let { throw it }
            GitHubReleaseUpdateChecker.HttpResponse(code, body)
        })

    private fun run(c: UpdateChecker, installed: String, code: Long = 1) = runBlocking { c.check(installed, code) }

    @JvmStatic
    fun main(args: Array<String>) {
        println("SemanticVersion:")
        check("parses 0.1.0", SemanticVersion.parse("0.1.0") == SemanticVersion(0, 1, 0))
        check("parses v0.3.0 (leading v stripped)", SemanticVersion.parse("v0.3.0") == SemanticVersion(0, 3, 0))
        check("parses V1.0.0", SemanticVersion.parse("V1.0.0") == SemanticVersion(1, 0, 0))
        check("parses 1.0 as 1.0.0", SemanticVersion.parse("1.0") == SemanticVersion(1, 0, 0))
        check("parses pre-release", SemanticVersion.parse("1.0.0-beta.1+build7") == SemanticVersion(1, 0, 0, "beta.1"))
        check("parses release-0.2.0 style tag", SemanticVersion.parse("release-0.2.0") == SemanticVersion(0, 2, 0))
        check("rejects garbage", SemanticVersion.parse("latest") == null && SemanticVersion.parse("") == null && SemanticVersion.parse(null) == null)
        check("0.1.0 < 0.2.0", SemanticVersion.parse("0.1.0")!! < SemanticVersion.parse("0.2.0")!!)
        check("0.2.0 == 0.2.0", SemanticVersion.parse("0.2.0")!!.compareTo(SemanticVersion.parse("v0.2.0")!!) == 0)
        check("0.2.1 > 0.2.0", SemanticVersion.parse("0.2.1")!! > SemanticVersion.parse("0.2.0")!!)
        check("0.9.9 < 1.0.0", SemanticVersion.parse("0.9.9")!! < SemanticVersion.parse("1.0.0")!!)
        check("0.10.0 > 0.9.0 (numeric, not lexical)", SemanticVersion.parse("0.10.0")!! > SemanticVersion.parse("0.9.0")!!)
        check("1.0.0-rc1 < 1.0.0", SemanticVersion.parse("1.0.0-rc1")!! < SemanticVersion.parse("1.0.0")!!)
        check("1.0.0-alpha < 1.0.0-beta", SemanticVersion.parse("1.0.0-alpha")!! < SemanticVersion.parse("1.0.0-beta")!!)

        println("Version comparison through the checker:")
        val apk = asset("agi-assistant-0.2.0-release.apk")
        var r = run(checker(body = release("v0.2.0", listOf(apk))), "0.1.0")
        check("0.1.0 -> 0.2.0 = update", r is UpdateCheckResult.Success && r.info.isNewerVersion, r)
        r = run(checker(body = release("v0.2.0", listOf(apk))), "0.2.0")
        check("0.2.0 -> 0.2.0 = no update", r is UpdateCheckResult.Success && !r.info.isNewerVersion, r)
        r = run(checker(body = release("v0.2.0", listOf(apk))), "0.2.1")
        check("0.2.1 -> 0.2.0 = no downgrade", r is UpdateCheckResult.Success && !r.info.isNewerVersion, r)
        r = run(checker(body = release("v0.3.0", listOf(apk))), "0.2.9")
        check("v0.3.0 tag normalised", r is UpdateCheckResult.Success && r.info.versionName == "0.3.0" && r.info.releaseTag == "v0.3.0" && r.info.isNewerVersion, r)
        r = run(checker(body = release("1.0.0", listOf(apk))), "0.1.0")
        check("0.1.0 -> 1.0.0 = update", r is UpdateCheckResult.Success && r.info.isNewerVersion, r)
        r = run(checker(body = release("v0.2.0", listOf(apk), body = "Hotfix\nversionCode: 5")), "0.2.0", code = 4)
        check("same versionName, higher versionCode in notes = update", r is UpdateCheckResult.Success && r.info.isNewerVersion && r.info.versionCode == 5L, r)
        r = run(checker(body = release("v0.2.0", listOf(apk), body = "versionCode: 5")), "0.2.0", code = 5)
        check("same versionName & versionCode = no update", r is UpdateCheckResult.Success && !r.info.isNewerVersion, r)
        r = run(checker(body = release("v9.9.9", listOf(apk))), "unknown")
        check("unparseable installed version never reports update", r is UpdateCheckResult.Success && !r.info.isNewerVersion, r)

        println("UpdateInfo fields:")
        r = run(checker(body = release("v0.2.0", listOf(apk), name = "Autumn release", body = "## Notes\n- faster")), "0.1.0")
        val info = (r as UpdateCheckResult.Success).info
        check("versionName", info.versionName == "0.2.0")
        check("releaseTag", info.releaseTag == "v0.2.0")
        check("releaseName", info.releaseName == "Autumn release")
        check("releaseNotes", info.releaseNotes == "## Notes\n- faster")
        check("apkDownloadUrl", info.apkDownloadUrl == "https://github.com/SojiBhuiya/Agi-personal-/releases/download/v0.2.0/agi-assistant-0.2.0-release.apk")
        check("publishedAt", info.publishedAt == "2026-09-12T10:00:00Z")
        check("htmlUrl", info.htmlUrl.endsWith("/releases/tag/v0.2.0"))
        check("apkSizeBytes", info.apkSizeBytes == 1_000_000L)
        check("versionCode null when not published", info.versionCode == null)

        println("Asset selection:")
        r = run(checker(body = release("v0.2.0", listOf(
            asset("SHA256SUMS", type = "text/plain"),
            asset("agi-assistant-0.2.0-debug.apk", size = 1_400_000),
            asset("source.zip", type = "application/zip"),
            asset("agi-assistant-0.2.0-release.apk", size = 1_200_000),
            asset("agi-assistant-0.2.0-release.apk.sha256", type = "text/plain"),
        ))), "0.1.0")
        check("multiple assets: picks the release .apk", r is UpdateCheckResult.Success && r.info.apkAssetName == "agi-assistant-0.2.0-release.apk", r)
        r = run(checker(body = release("v0.2.0", listOf(asset("MyApp-Arbitrary_Name.APK")))), "0.1.0")
        check("no hard-coded filename: any *.apk (case-insensitive) accepted", r is UpdateCheckResult.Success && r.info.apkAssetName == "MyApp-Arbitrary_Name.APK", r)
        r = run(checker(body = release("v0.2.0", listOf(asset("app-arm64-v8a.apk", size = 5_000_000), asset("app-universal.apk", size = 9_000_000), asset("app-x86.apk", size = 5_000_000)))), "0.1.0")
        check("prefers universal over ABI splits", r is UpdateCheckResult.Success && r.info.apkAssetName == "app-universal.apk", r)
        r = run(checker(body = release("v0.2.0", listOf(asset("only.apk", state = "starter"), asset("ready.apk")))), "0.1.0")
        check("skips assets still uploading", r is UpdateCheckResult.Success && r.info.apkAssetName == "ready.apk", r)
        r = run(checker(body = release("v0.2.0", listOf(asset("readme.txt", type = "text/plain"), asset("source.zip", type = "application/zip")))), "0.1.0")
        check("missing APK asset -> NO_APK_ASSET", r is UpdateCheckResult.Failure && r.reason == UpdateError.NO_APK_ASSET, r)
        r = run(checker(body = release("v0.2.0", emptyList())), "0.1.0")
        check("empty assets -> NO_APK_ASSET", r is UpdateCheckResult.Failure && r.reason == UpdateError.NO_APK_ASSET, r)
        r = run(checker(body = release("v0.2.0", listOf(asset("evil.apk", url = "http://example.com/evil.apk")))), "0.1.0")
        check("non-HTTPS asset URL rejected", r is UpdateCheckResult.Failure && r.reason == UpdateError.MALFORMED_RESPONSE, r)

        println("Malformed / error responses:")
        r = run(checker(body = "<html>not json</html>"), "0.1.0")
        check("malformed body -> MALFORMED_RESPONSE", r is UpdateCheckResult.Failure && r.reason == UpdateError.MALFORMED_RESPONSE, r)
        r = run(checker(body = """{"message":"Not Found"}"""), "0.1.0")
        check("json without tag_name -> MALFORMED_RESPONSE", r is UpdateCheckResult.Failure && r.reason == UpdateError.MALFORMED_RESPONSE, r)
        r = run(checker(body = release("nightly", listOf(apk))), "0.1.0")
        check("non-semver tag -> INVALID_VERSION", r is UpdateCheckResult.Failure && r.reason == UpdateError.INVALID_VERSION, r)
        r = run(checker(body = """{"tag_name":"v0.2.0","assets":"oops"}"""), "0.1.0")
        check("assets not an array -> NO_APK_ASSET (no crash)", r is UpdateCheckResult.Failure && r.reason == UpdateError.NO_APK_ASSET, r)
        r = run(checker(code = 404, body = """{"message":"Not Found"}"""), "0.1.0")
        check("HTTP 404 -> HTTP error with friendly message", r is UpdateCheckResult.Failure && r.reason == UpdateError.HTTP && r.message.contains("No releases"), r)
        r = run(checker(code = 403, body = ""), "0.1.0")
        check("HTTP 403 -> rate limit message", r is UpdateCheckResult.Failure && r.reason == UpdateError.HTTP && r.message.contains("rate limit"), r)

        println("Network failure:")
        r = run(checker(throwing = UnknownHostException("api.github.com")), "0.1.0")
        check("UnknownHostException -> DNS (connectivity), no throw", r is UpdateCheckResult.Failure && r.reason == UpdateError.DNS && r.reason.isConnectivity, r)
        r = run(checker(throwing = IOException("timeout")), "0.1.0")
        check("IOException -> NETWORK", r is UpdateCheckResult.Failure && r.reason == UpdateError.NETWORK && r.message.contains("timeout"), r)
        r = run(checker(throwing = IllegalStateException("boom")), "0.1.0")
        check("unexpected exception -> UNKNOWN, no throw", r is UpdateCheckResult.Failure && r.reason == UpdateError.UNKNOWN, r)
        check("defaultFetch refuses plain http", runCatching { GitHubReleaseUpdateChecker.defaultFetch("http://api.github.com/x", 100) }.exceptionOrNull() is IllegalArgumentException)

        println("Repository + manager state machine:")
        val repo = UpdateRepository(checker(body = release("v0.2.0", listOf(apk))), { InstalledVersion("0.1.0", 1) }, Dispatchers.Unconfined)
        val states = ArrayList<UpdateState>()
        val mgr = UpdateManager(repo, kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined))
        mgr.addObserver { states += it }
        runBlocking { mgr.checkNow().join() }
        check("states: Idle -> Checking -> UpdateAvailable", states.map { it::class.simpleName } == listOf("Idle", "Checking", "UpdateAvailable"), states)
        check("repository caches last result", repo.lastResult is UpdateCheckResult.Success && repo.lastCheckedAt > 0)
        check("checkIfStale is throttled right after a check", mgr.checkIfStale() == null)

        val repo2 = UpdateRepository(checker(body = release("v0.1.0", listOf(apk))), { InstalledVersion("0.1.0", 1) }, Dispatchers.Unconfined)
        check("UpToDate state carries installed version", repo2.toState(runBlocking { repo2.checkForUpdate() }).let { it is UpdateState.UpToDate && it.installedVersion == "0.1.0" })
        val repo3 = UpdateRepository(checker(throwing = IOException("offline")), { InstalledVersion("0.1.0", 1) }, Dispatchers.Unconfined)
        val err = repo3.toState(runBlocking { repo3.checkForUpdate() })
        check("Error state from network failure", err is UpdateState.Error && err.reason.isConnectivity, err)
        val repo4 = UpdateRepository(object : UpdateChecker {
            override suspend fun check(installedVersionName: String, installedVersionCode: Long) = throw RuntimeException("checker bug")
        }, { InstalledVersion("0.1.0", 1) }, Dispatchers.Unconfined)
        check("repository shields callers from a throwing checker", runBlocking { repo4.checkForUpdate() } is UpdateCheckResult.Failure)

        println("\n$passed passed, $fails failed")
        if (fails > 0) System.exit(1)
    }
}
