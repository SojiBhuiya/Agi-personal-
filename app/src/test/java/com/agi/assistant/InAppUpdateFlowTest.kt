package com.agi.assistant

import com.agi.assistant.core.update.*
import com.agi.assistant.core.update.InstallPolicy.ApkFacts
import com.agi.assistant.core.update.InstallPolicy.Preflight
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest

/**
 * End-to-end rules of the real in-app update flow (Settings → Check for updates → Update →
 * download → verify → Android installer), on the JVM with a fake GitHub + a stub HTTP layer.
 * Covers the 20 required scenarios; installer *launch* itself is Android-only (see docs).
 */
object InAppUpdateFlowTest {
    private var fails = 0; private var passed = 0
    private fun check(name: String, cond: Boolean, detail: Any? = "") { if (cond) { passed++; println("  ok   $name") } else { fails++; println("  FAIL $name $detail") } }

    private const val PKG = "com.agi.assistant"
    private const val RELEASE_CERT = "5F:25:0D:82:3B:07:65:71:67:CF:7F:F9:41:E1:BB:5E:37:0C:D7:A9:5C:0E:E5:A9:D4:55:12:F2:F3:29:8E:8C"

    // ---- fake GitHub release JSON -------------------------------------------------------
    private fun asset(name: String, size: Long = 1_200_000, tag: String = "v0.2.1", type: String = "application/vnd.android.package-archive") =
        """{"name":"$name","size":$size,"browser_download_url":"https://github.com/SojiBhuiya/Agi-personal-/releases/download/$tag/$name","content_type":"$type","state":"uploaded"}"""
    private fun release(tag: String, assets: List<String>, body: String) = """
        {"tag_name":"$tag","name":"AGI Assistant ${tag.removePrefix("v")}","body":${jsonStr(body)},"published_at":"2026-09-20T10:00:00Z",
         "html_url":"https://github.com/SojiBhuiya/Agi-personal-/releases/tag/$tag","draft":false,"prerelease":false,"assets":[${assets.joinToString(",")}]}
    """.trimIndent()
    private fun jsonStr(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
    private fun notes(vc: Long, sha: String? = null, apk: String = "agi-assistant-0.2.1-release.apk") =
        "## AGI Assistant\n\n### What's New\n- Faster replies\n- Weather tool\n\nversionCode: $vc\n" + (sha?.let { "sha256: $it\n$it  $apk\n" } ?: "")

    private fun checker(code: Int = 200, body: String = "", throwing: Throwable? = null) =
        GitHubReleaseUpdateChecker(fetch = { _ -> throwing?.let { throw it }; GitHubReleaseUpdateChecker.HttpResponse(code, body) })
    private fun run(c: UpdateChecker, installedName: String, installedCode: Long) = runBlocking { c.check(installedName, installedCode) }

    // ---- stub HTTP for the downloader ------------------------------------------------------
    private class StubConn(url: URL, private val code: Int = 200, private val body: ByteArray = ByteArray(0), private val type: String = "application/octet-stream",
                           private val stream: InputStream? = null, private val onConnect: (() -> Unit)? = null) : HttpURLConnection(url) {
        override fun connect() { onConnect?.invoke() }
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getResponseCode(): Int { onConnect?.invoke(); return code }
        override fun getContentType() = type
        override fun getContentLengthLong() = body.size.toLong()
        override fun getInputStream(): InputStream = stream ?: ByteArrayInputStream(body)
    }
    private fun apkBytes(n: Int) = ByteArray(n).also { it[0] = 0x50; it[1] = 0x4B; it[2] = 3; it[3] = 4; for (i in 4 until n) it[i] = (i * 31).toByte() }
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun tmpDir() = kotlin.io.path.createTempDirectory("inapp").toFile()
    private fun info(name: String = "agi-assistant-0.2.1-release.apk", size: Long = -1, sha256: String? = null, checksumUrl: String? = null, versionCode: Long? = 3) =
        UpdateInfo("0.2.1", versionCode, "v0.2.1", "AGI Assistant 0.2.1", "notes", "https://github.com/SojiBhuiya/Agi-personal-/releases/download/v0.2.1/$name", name, size,
            "2026-09-20T10:00:00Z", "https://github.com/SojiBhuiya/Agi-personal-/releases/tag/v0.2.1", isNewerVersion = true, apkSha256 = sha256, checksumAssetUrl = checksumUrl)
    private fun downloader(dir: File, routes: Map<String, (URL) -> HttpURLConnection>) =
        ApkDownloader(dir, Dispatchers.IO, connectTimeoutMs = 2000, readTimeoutMs = 2000,
            open = { url -> routes[url.path.substringAfterLast('/')]?.invoke(url) ?: StubConn(url, 404) }, freeSpace = { 10L shl 30 })  // strict checksum default

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val apkName = "agi-assistant-0.2.1-release.apk"
        val apk = asset(apkName); val sum = asset("$apkName.sha256", size = 100, type = "text/plain")

        println("1-3. Version comparison (versionCode authoritative):")
        var r = run(checker(body = release("v0.2.0", listOf(asset("agi-assistant-0.2.0-release.apk", tag = "v0.2.0")), notes(2, apk = "agi-assistant-0.2.0-release.apk"))), "0.2.0", 2)
        check("1. same versionCode (0.2.0/2 vs 0.2.0/2) -> no update", r is UpdateCheckResult.Success && !r.info.isNewerVersion, r)
        r = run(checker(body = release("v0.2.1", listOf(apk, sum), notes(3))), "0.2.0", 2)
        check("2. higher versionCode (3 > 2) -> update available with tag/version/notes/apk/checksum/url", r is UpdateCheckResult.Success && r.info.isNewerVersion &&
            r.info.releaseTag == "v0.2.1" && r.info.versionName == "0.2.1" && r.info.versionCode == 3L && r.info.releaseNotes.contains("Faster replies") &&
            r.info.apkAssetName == apkName && r.info.checksumAssetUrl!!.endsWith("$apkName.sha256") && r.info.apkDownloadUrl.startsWith("https://github.com/SojiBhuiya/Agi-personal-/releases/download/v0.2.1/"), r)
        check("2b. What's new strips machine markers", UpdateMessages.whatsNew((r as UpdateCheckResult.Success).info.releaseNotes).let { it.contains("• Faster replies") && !it.contains("versionCode") && !it.contains("sha256") })
        r = run(checker(body = release("v0.1.9", listOf(asset("agi-assistant-0.1.9-release.apk", tag = "v0.1.9")), notes(1, apk = "agi-assistant-0.1.9-release.apk"))), "0.2.0", 2)
        check("3. older release (versionCode 1) -> no downgrade", r is UpdateCheckResult.Success && !r.info.isNewerVersion, r)
        r = run(checker(body = release("v0.2.1", listOf(apk), notes(2))), "0.2.0", 2)
        check("3b. higher versionName but same versionCode -> NOT an update (versionCode wins)", r is UpdateCheckResult.Success && !r.info.isNewerVersion, r)
        r = run(checker(body = release("v0.3.0", listOf(asset("agi-assistant-0.3.0-release.apk", tag = "v0.3.0")), "- big\n")), "0.2.0", 2)
        check("3c. release without versionCode falls back to semver (0.3.0 > 0.2.0)", r is UpdateCheckResult.Success && r.info.isNewerVersion, r)
        check("3d. future names work: 0.2.2 / 0.3.0 / 1.0.0 all parse", listOf("0.2.2", "0.3.0", "1.0.0").all { UpdatePolicy.isNewer(SemanticVersion.parse(it)!!, null, SemanticVersion.parse("0.2.1"), 3) })
        check("3e. UpdateRepository maps to UpToDate when not newer", run {
            val repo = UpdateRepository(object : UpdateChecker { override suspend fun check(installedVersionName: String, installedVersionCode: Long) = UpdateCheckResult.Success(info(versionCode = 2).copy(isNewerVersion = false)) }, { InstalledVersion("0.2.0", 2) }, Dispatchers.Unconfined)
            repo.toState(repo.checkForUpdate()) is UpdateState.UpToDate
        })

        println("4-7. Asset selection:")
        r = run(checker(body = release("v0.2.1", emptyList(), notes(3))), "0.2.0", 2)
        check("4. release has no APK -> NO_APK_ASSET, safe error text", r is UpdateCheckResult.Failure && r.reason == UpdateError.NO_APK_ASSET &&
            UpdateMessages.statusLine(UpdateState.Error(r.reason, r.message), "0.2.0").contains("no Android package"), r)
        r = run(checker(body = release("v0.2.1", listOf(sum), notes(3))), "0.2.0", 2)
        check("5. release has only the checksum -> NO_APK_ASSET", r is UpdateCheckResult.Failure && r.reason == UpdateError.NO_APK_ASSET, r)
        r = run(checker(body = release("v0.2.1", listOf(sum, asset("agi-assistant-0.2.1-debug.apk"), asset("source.zip", type = "application/zip"), apk, asset("app-universal.apk")), notes(3))), "0.2.0", 2)
        check("6. canonical agi-assistant-<v>-release.apk selected among many assets", r is UpdateCheckResult.Success && r.info.apkAssetName == apkName, r)
        r = run(checker(body = release("v0.2.1", listOf(asset("agi-assistant-0.2.1-debug.apk")), notes(3))), "0.2.0", 2)
        check("7. only a debug APK (wrong file name) -> rejected as NO_APK_ASSET", r is UpdateCheckResult.Failure && r.reason == UpdateError.NO_APK_ASSET, r)
        r = run(checker(body = release("v0.2.1", listOf(asset("evil.apk").replace("https://", "http://")), notes(3))), "0.2.0", 2)
        check("7b. non-HTTPS asset rejected", r is UpdateCheckResult.Failure, r)
        check("7c. expected name is version-derived, not hard-coded", UpdatePolicy.expectedApkName("0.3.0") == "agi-assistant-0.3.0-release.apk")

        println("8-9, 16-17. Download + checksum + atomic promotion:")
        val bytes = apkBytes(300 * 1024); val good = sha(bytes)
        run {
            val dir = tmpDir()
            val dl = downloader(dir, mapOf(apkName to { u -> StubConn(u, body = bytes) }, "$apkName.sha256" to { u -> StubConn(u, body = "$good  $apkName\n".toByteArray(), type = "text/plain") }))
            val progress = ArrayList<Int>()
            val res = dl.download(info(size = bytes.size.toLong(), checksumUrl = "https://github.com/x/$apkName.sha256")) { d, t -> progress += UpdateState.Downloading(info(), d, t).percent }
            check("8. checksum matches -> accepted & verified, staged as .apk", res is DownloadResult.Success && res.verified && res.file.name.endsWith(".apk") && res.sha256 == good, res)
            check("8b. progress 0..100 reported", progress.first() == 0 && progress.last() == 100 && progress.zipWithNext().all { (a, b) -> b >= a }, progress)
            check("17. no .part left after successful promotion", dir.listFiles()!!.none { it.name.endsWith(".part") })
            check("17b. existingComplete re-verifies the promoted file", dl.existingComplete(info(size = bytes.size.toLong(), sha256 = good)) != null)
        }
        run {
            val dir = tmpDir()
            val dl = downloader(dir, mapOf(apkName to { u -> StubConn(u, body = bytes) }, "$apkName.sha256" to { u -> StubConn(u, body = "${"0".repeat(64)}  $apkName\n".toByteArray(), type = "text/plain") }))
            val res = dl.download(info(size = bytes.size.toLong(), checksumUrl = "https://github.com/x/$apkName.sha256"))
            check("9. checksum mismatch -> CHECKSUM_MISMATCH, user-facing text", res is DownloadResult.Failure && res.reason == DownloadError.CHECKSUM_MISMATCH && res.message.contains("discarded"), res)
            check("9b. mismatch: no .apk and no .part left (nothing installable)", dir.listFiles()!!.isEmpty(), dir.listFiles()!!.map { it.name })
            val none = dl.download(info(size = bytes.size.toLong()))
            check("9c. no checksum published -> CHECKSUM_UNAVAILABLE (policy: never install unverified)", none is DownloadResult.Failure && none.reason == DownloadError.CHECKSUM_UNAVAILABLE && dir.listFiles()!!.isEmpty(), none)
        }
        run {
            val dir = tmpDir()
            val cut = object : InputStream() { var i = 0; override fun read(): Int = if (i < 100 * 1024) bytes[i++].toInt() and 0xff else throw IOException("unexpected end of stream") }
            val dl = downloader(dir, mapOf(apkName to { u -> StubConn(u, body = bytes, stream = cut) }, "$apkName.sha256" to { u -> StubConn(u, body = "$good  $apkName\n".toByteArray()) }))
            val res = dl.download(info(size = bytes.size.toLong(), checksumUrl = "https://github.com/x/$apkName.sha256"))
            check("16. interrupted download -> retryable failure (INTERRUPTED/NETWORK), never Success", res is DownloadResult.Failure && res.reason in setOf(DownloadError.INTERRUPTED, DownloadError.NETWORK), res)
            check("16b. incomplete bytes stay in .part, no .apk exists for the installer", dir.listFiles()!!.none { it.name.endsWith(".apk") })
            check("16c. existingComplete ignores the partial", dl.existingComplete(info(size = bytes.size.toLong(), sha256 = good)) == null)
        }

        println("12-15. Network / HTTP failures:")
        run {
            val dir = tmpDir()
            fun dlWith(conn: (URL) -> HttpURLConnection) = downloader(dir, mapOf(apkName to conn, "$apkName.sha256" to { u -> StubConn(u, body = "$good  $apkName\n".toByteArray()) }))
            val i = info(size = bytes.size.toLong(), checksumUrl = "https://github.com/x/$apkName.sha256")
            val net = dlWith { u -> StubConn(u, onConnect = { throw UnknownHostException("github.com") }) }.download(i)
            check("12. network failure -> NETWORK, friendly message, no throw", net is DownloadResult.Failure && net.reason == DownloadError.NETWORK && !net.message.contains("Exception"), net)
            val e404 = dlWith { u -> StubConn(u, 404) }.download(i)
            check("13. HTTP 404 -> HTTP error 'no longer available'", e404 is DownloadResult.Failure && e404.reason == DownloadError.HTTP && e404.message.contains("404"), e404)
            val e403 = dlWith { u -> StubConn(u, 403) }.download(i)
            check("14. HTTP 403 -> HTTP error 'rate-limiting'", e403 is DownloadResult.Failure && e403.reason == DownloadError.HTTP && e403.message.contains("403"), e403)
            val e500 = dlWith { u -> StubConn(u, 500) }.download(i)
            check("15. HTTP 500 -> HTTP error 'try again later'", e500 is DownloadResult.Failure && e500.reason == DownloadError.HTTP && e500.message.contains("500"), e500)
            check("12-15. nothing installable left behind", dir.listFiles()!!.none { it.name.endsWith(".apk") })
            val c = checker(throwing = UnknownHostException("api.github.com")); val cr = run(c, "0.2.0", 2)
            check("12b. check: network failure -> NETWORK + generic UI text", cr is UpdateCheckResult.Failure && cr.reason == UpdateError.DNS && UpdateMessages.statusLine(UpdateState.Error(cr.reason, cr.message), "0.2.0") == UpdateMessages.ERROR_DNS, cr)
            for (code in listOf(403, 404, 500)) { val x = run(checker(code = code, body = "{}"), "0.2.0", 2); check("13-15b. check: HTTP $code -> safe Failure", x is UpdateCheckResult.Failure && x.reason == UpdateError.HTTP, x) }
        }

        println("10-11. APK identity before the installer:")
        val staged = File.createTempFile("agi", ".apk").apply { writeBytes(bytes); deleteOnExit() }
        val ok = ApkFacts(PKG, 3, "0.2.1", parsed = true, signerSha256 = listOf(RELEASE_CERT))
        check("valid 0.2.1/3 over 0.2.0/2, same cert, verified -> Ok", InstallPolicy.preflight(staged, info(), ok, PKG, 2, true, verified = true, installedSignerSha256 = listOf(RELEASE_CERT)) is Preflight.Ok)
        var b = InstallPolicy.preflight(staged, info(), ApkFacts("com.other.app", 9, "9.0", true), PKG, 2, true, verified = true) as? Preflight.Blocked
        check("10. applicationId mismatch -> PACKAGE_MISMATCH, file discarded", b?.reason == InstallError.PACKAGE_MISMATCH && b.discardFile, b)
        b = InstallPolicy.preflight(staged, info(), ApkFacts(PKG, 2, "0.2.0", true), PKG, 2, true, verified = true) as? Preflight.Blocked
        check("11. versionCode == installed -> NOT_NEWER (same-version APK is not an update)", b?.reason == InstallError.NOT_NEWER && b.discardFile, b)
        b = InstallPolicy.preflight(staged, info(), ApkFacts(PKG, 1, "0.1.0", true), PKG, 2, true, verified = true) as? Preflight.Blocked
        check("11b. versionCode < installed -> NOT_NEWER (no downgrade)", b?.reason == InstallError.NOT_NEWER, b)
        b = InstallPolicy.preflight(staged, info(), ok, PKG, 2, true, verified = false) as? Preflight.Blocked
        check("11c. unverified file -> UNVERIFIED, never launched, discarded", b?.reason == InstallError.UNVERIFIED && b.discardFile, b)
        b = InstallPolicy.preflight(staged, info(), ok.copy(signerSha256 = listOf("AA:BB")), PKG, 2, true, verified = true, installedSignerSha256 = listOf(RELEASE_CERT)) as? Preflight.Blocked
        check("11d. different signing certificate -> SIGNATURE_MISMATCH before launching", b?.reason == InstallError.SIGNATURE_MISMATCH && b.discardFile, b)
        check("11e. unknown signer info never blocks a legitimate update", InstallPolicy.preflight(staged, info(), ok.copy(signerSha256 = null), PKG, 2, true, verified = true, installedSignerSha256 = listOf(RELEASE_CERT)) is Preflight.Ok &&
            InstallPolicy.preflight(staged, info(), ok, PKG, 2, true, verified = true, installedSignerSha256 = null) is Preflight.Ok)
        b = InstallPolicy.preflight(staged, info(), ok, PKG, 2, false, verified = true) as? Preflight.Blocked
        check("11f. 'install unknown apps' missing -> PERMISSION_REQUIRED, file kept for retry", b?.reason == InstallError.PERMISSION_REQUIRED && !b.discardFile, b)

        println("18-20. Installer hand-off contract (source-level; Android intents are not constructible on the JVM):")
        val installerSrc = listOf("app/src/main/java/com/agi/assistant/ui/ApkInstaller.kt", "../app/src/main/java/com/agi/assistant/ui/ApkInstaller.kt").map(::File).firstOrNull { it.isFile }?.readText()
        val providerSrc = listOf("app/src/main/java/com/agi/assistant/services/AssistantFileProvider.kt", "../app/src/main/java/com/agi/assistant/services/AssistantFileProvider.kt").map(::File).firstOrNull { it.isFile }?.readText()
        val manifest = listOf("app/src/main/AndroidManifest.xml", "../app/src/main/AndroidManifest.xml").map(::File).firstOrNull { it.isFile }?.readText()
        if (installerSrc != null && providerSrc != null && manifest != null) {
            check("18. content:// URI from the app's FileProvider with a read grant", installerSrc.contains("AssistantFileProvider.updateApkUri") && installerSrc.contains("FLAG_GRANT_READ_URI_PERMISSION") && providerSrc.contains(".scheme(\"content\")"))
            check("19. file:// URI never used (no Uri.fromFile, no file:// scheme)", !installerSrc.contains("Uri.fromFile") && !installerSrc.contains("\"file://") && !providerSrc.contains("Uri.fromFile"))
            check("20. installer intent uses ACTION_INSTALL_PACKAGE + application/vnd.android.package-archive", installerSrc.contains("Intent.ACTION_INSTALL_PACKAGE") && providerSrc.contains("application/vnd.android.package-archive") && installerSrc.contains("AssistantFileProvider.APK_MIME"))
            check("20b. manifest: REQUEST_INSTALL_PACKAGES declared, provider not exported, grantUriPermissions", manifest.contains("android.permission.REQUEST_INSTALL_PACKAGES") && Regex("AssistantFileProvider\"[\\s\\S]*?android:exported=\"false\"[\\s\\S]*?android:grantUriPermissions=\"true\"").containsMatchIn(manifest))
            check("20c. no silent install API (PackageInstaller.Session) used", !installerSrc.contains("PackageInstaller.Session") && !installerSrc.contains("createSession"))
        } else println("  skip 18-20 (sources not on disk)")

        println("Manager: one tap → download → ReadyToInstall → auto-install hook:")
        run {
            val dir = tmpDir()
            val dl = downloader(dir, mapOf(apkName to { u -> StubConn(u, body = bytes) }, "$apkName.sha256" to { u -> StubConn(u, body = "$good  $apkName\n".toByteArray()) }))
            val i = info(size = bytes.size.toLong(), checksumUrl = "https://github.com/x/$apkName.sha256")
            val repo = UpdateRepository(object : UpdateChecker { override suspend fun check(installedVersionName: String, installedVersionCode: Long) = UpdateCheckResult.Success(i) }, { InstalledVersion("0.2.0", 2) }, Dispatchers.Unconfined)
            val mgr = UpdateManager(repo, CoroutineScope(Dispatchers.Default), downloader = dl)
            var handed: UpdateState.ReadyToInstall? = null
            mgr.onReadyToInstall = { handed = it }
            mgr.checkNow().join()
            check("check -> UpdateAvailable 0.2.1 (build 3)", mgr.state is UpdateState.UpdateAvailable && UpdateMessages.statusLine(mgr.state, "0.2.0") == "New version available: 0.2.1 (build 3)", UpdateMessages.statusLine(mgr.state, "0.2.0"))
            mgr.startDownload(i)!!.join()
            check("download -> ReadyToInstall (verified) and installer hook fired once with the verified file", mgr.state is UpdateState.ReadyToInstall && handed != null && handed!!.verified && handed!!.file.isFile)
            check("status: 'Update downloaded: 0.2.1 (verified)'", UpdateMessages.statusLine(mgr.state, "0.2.0") == "Update downloaded: 0.2.1 (verified)")
            handed = null
            mgr.startDownload(i)!!.join()
            check("re-using the staged file does not auto-launch again (user taps Install update)", handed == null && mgr.state is UpdateState.ReadyToInstall)
            mgr.markInstallerLaunched(handed?.file ?: (mgr.state as UpdateState.ReadyToInstall).file)
            check("InstallerLaunched never claims success", UpdateMessages.statusLine(mgr.state, "0.2.0").contains("Waiting for Android"))
            check("reconcile: restart as 0.2.1/3 -> UpToDate + staged file removed", mgr.reconcileInstalled("0.2.1", 3) && mgr.state is UpdateState.UpToDate && dir.listFiles()!!.none { it.name.endsWith(".apk") })
        }

        println("\nInAppUpdateFlowTest: $passed passed, $fails failed")
        if (fails > 0) System.exit(1)
    }
}
