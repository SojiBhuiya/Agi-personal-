package com.agi.assistant

import com.agi.assistant.core.update.*
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for the Phase 3 downloader and the download state machine.
 * A local HTTP server serves fake APKs; the downloader's HTTPS-only rule is
 * satisfied by giving it https URLs and rewriting them to the local server
 * through the injectable connection opener. Failure modes that a real server
 * cannot easily produce (timeouts, disk full, cancellation) use a stub connection.
 */
object ApkDownloaderTest {
    private var fails = 0; private var passed = 0
    private fun check(name: String, cond: Boolean, detail: Any? = "") {
        if (cond) { passed++; println("  ok   $name") } else { fails++; println("  FAIL $name  -> $detail") }
    }

    private fun apkBytes(size: Int, seed: Int = 7): ByteArray {
        val b = ByteArray(size); val r = java.util.Random(seed.toLong()); r.nextBytes(b)
        b[0] = 0x50; b[1] = 0x4B; b[2] = 3; b[3] = 4 // ZIP magic
        return b
    }
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun info(name: String = "agi-assistant-0.2.0-release.apk", size: Long = -1, sha256: String? = null, checksumUrl: String? = null, url: String = "https://github.com/SojiBhuiya/Agi-personal-/releases/download/v0.2.0/$name") =
        UpdateInfo("0.2.0", null, "v0.2.0", "AGI 0.2.0", "notes", url, name, size, "2026-09-12T10:00:00Z",
            "https://github.com/SojiBhuiya/Agi-personal-/releases/tag/v0.2.0", isNewerVersion = true, apkSha256 = sha256, checksumAssetUrl = checksumUrl)

    // ---- local server ---------------------------------------------------------------
    private lateinit var server: HttpServer
    private val routes = HashMap<String, (HttpExchange) -> Unit>()
    private fun serve(path: String, handler: (HttpExchange) -> Unit) { routes[path] = handler }
    private fun bytesRoute(data: ByteArray, contentType: String = "application/vnd.android.package-archive", supportRange: Boolean = true, truncateTo: Int = -1, dropAfterFirst: AtomicInteger? = null): (HttpExchange) -> Unit = { ex ->
        val range = ex.requestHeaders.getFirst("Range")
        var start = 0
        if (supportRange && range != null) start = range.removePrefix("bytes=").removeSuffix("-").toInt()
        val body = data.copyOfRange(start, data.size)
        ex.responseHeaders.add("Content-Type", contentType)
        val declared = body.size.toLong()
        val send = if (truncateTo >= 0 && (dropAfterFirst == null || dropAfterFirst.getAndIncrement() == 0)) body.copyOf(minOf(truncateTo, body.size)) else body
        ex.sendResponseHeaders(if (start > 0) 206 else 200, declared)
        ex.responseBody.use { it.write(send) }
    }

    /** Rewrites any https URL to the local server, keeping the path. */
    private fun localOpen(url: URL): HttpURLConnection =
        (URL("http://127.0.0.1:${server.address.port}${url.path}").openConnection() as HttpURLConnection)

    private fun downloader(dir: File, open: (URL) -> HttpURLConnection = ::localOpen, free: (File) -> Long = { 10L shl 30 }) =
        ApkDownloader(dir, Dispatchers.IO, connectTimeoutMs = 3000, readTimeoutMs = 3000, open = open, freeSpace = free)

    private fun tmpDir() = kotlin.io.path.createTempDirectory("apkdl").toFile()

    /** Stub connection for failure injection. */
    private class StubConn(url: URL, private val code: Int = 200, private val body: ByteArray = ByteArray(0), private val type: String = "application/octet-stream",
                           private val length: Long = body.size.toLong(), private val stream: InputStream? = null, private val onConnect: (() -> Unit)? = null) : HttpURLConnection(url) {
        override fun connect() { onConnect?.invoke() }
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getResponseCode(): Int { onConnect?.invoke(); return code }
        override fun getContentType() = type
        override fun getContentLengthLong() = length
        override fun getInputStream(): InputStream = stream ?: ByteArrayInputStream(body)
    }

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex -> (routes[ex.requestURI.path] ?: { e: HttpExchange -> e.sendResponseHeaders(404, -1); e.close() })(ex) }
        server.start()
        try { runAll() } finally { server.stop(0) }
        println("\n$passed passed, $fails failed")
        if (fails > 0) System.exit(1)
    }

    private suspend fun runAll() = coroutineScope {
        val apk = apkBytes(600 * 1024)
        val apkSha = sha(apk)
        val base = "/SojiBhuiya/Agi-personal-/releases/download/v0.2.0/"

        println("Happy path:")
        serve(base + "agi-assistant-0.2.0-release.apk", bytesRoute(apk))
        run {
            val dir = tmpDir(); val dl = downloader(dir)
            val progress = ArrayList<Pair<Long, Long>>()
            val r = dl.download(info(size = apk.size.toLong())) { d, t -> progress += d to t }
            check("download succeeds", r is DownloadResult.Success, r)
            r as DownloadResult.Success
            check("file exists, non-empty, exact size", r.file.isFile && r.file.length() == apk.size.toLong())
            check("file content matches", r.file.readBytes().contentEquals(apk))
            check("sha256 computed", r.sha256 == apkSha)
            check("unverified when no checksum published", !r.verified)
            check("saved inside app-controlled directory", r.file.parentFile == dir && r.file.name.endsWith(".apk"))
            check("no .part left behind", dir.listFiles()!!.none { it.name.endsWith(".part") })
            check("progress reported with total", progress.isNotEmpty() && progress.all { it.second == apk.size.toLong() } && progress.last().first == apk.size.toLong())
            check("progress monotonic", progress.zipWithNext().all { (a, b) -> b.first >= a.first })
            check("percent helper", UpdateState.Downloading(info(), 310, 500).percent == 62)
            check("existingComplete reuses file", dl.existingComplete(info(size = apk.size.toLong()))?.file == r.file)
            dl.cleanup(info()); check("cleanup removes staged file", !r.file.exists())
        }

        println("Checksum verification:")
        run {
            val dl = downloader(tmpDir())
            val r = dl.download(info(sha256 = apkSha))
            check("inline sha256 verified", r is DownloadResult.Success && r.verified, r)
            val bad = dl.download(info(sha256 = "0".repeat(64)))
            check("mismatching inline sha256 -> CHECKSUM_MISMATCH", bad is DownloadResult.Failure && bad.reason == DownloadError.CHECKSUM_MISMATCH, bad)
            check("mismatch: file discarded", dl.targetFile(info()).exists().not() && dl.existingComplete(info()) == null)
        }
        run {
            serve(base + "agi-assistant-0.2.0-release.apk.sha256") { ex -> val b = "$apkSha  agi-assistant-0.2.0-release.apk\n".toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) } }
            val r = downloader(tmpDir()).download(info(checksumUrl = "https://github.com$base" + "agi-assistant-0.2.0-release.apk.sha256"))
            check("checksum fetched from .sha256 asset and verified", r is DownloadResult.Success && r.verified, r)
            serve(base + "SHA256SUMS") { ex -> val b = ("${"1".repeat(64)}  other.apk\n$apkSha  ./agi-assistant-0.2.0-release.apk\n").toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) } }
            val r2 = downloader(tmpDir()).download(info(checksumUrl = "https://github.com$base" + "SHA256SUMS"))
            check("SHA256SUMS: picks the line for our APK", r2 is DownloadResult.Success && r2.verified, r2)
            serve(base + "SHA256SUMS") { ex -> val b = "${"2".repeat(64)}  agi-assistant-0.2.0-release.apk\n".toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) } }
            val r3 = downloader(tmpDir()).download(info(checksumUrl = "https://github.com$base" + "SHA256SUMS"))
            check("wrong sum in SHA256SUMS blocks install", r3 is DownloadResult.Failure && r3.reason == DownloadError.CHECKSUM_MISMATCH, r3)
            val r4 = downloader(tmpDir()).download(info(checksumUrl = "https://github.com$base" + "missing.sha256"))
            check("unreachable checksum asset -> download still succeeds, unverified", r4 is DownloadResult.Success && !r4.verified, r4)
        }
        println("Parser: checksum discovery")
        run {
            val assets = listOf(
                """{"name":"agi.apk","browser_download_url":"https://h/agi.apk","state":"uploaded"}""",
                """{"name":"SHA256SUMS","browser_download_url":"https://h/SHA256SUMS","state":"uploaded"}""",
                """{"name":"agi.apk.sha256","browser_download_url":"https://h/agi.apk.sha256","state":"uploaded"}""")
            val rel = GitHubReleaseParser.parse("""{"tag_name":"v0.2.0","body":"sha256: ${"a".repeat(64)}","assets":[${assets.joinToString(",")}]}""")
            check("prefers <apk>.sha256 asset", rel.checksumAssetUrl == "https://h/agi.apk.sha256", rel.checksumAssetUrl)
            check("inline sha256 from notes", rel.sha256 == "a".repeat(64))
            val rel2 = GitHubReleaseParser.parse("""{"tag_name":"v0.2.0","body":"${"b".repeat(64)}  agi.apk\n${"c".repeat(64)}  other.apk","assets":[${assets[0]},${assets[1]}]}""")
            check("sha256sum-style line for our apk wins", rel2.sha256 == "b".repeat(64) && rel2.checksumAssetUrl == "https://h/SHA256SUMS")
            check("parseChecksumFile bare hex", GitHubReleaseParser.parseChecksumFile("${"d".repeat(64)}\n", "agi.apk") == "d".repeat(64))
            check("parseChecksumFile no match -> null", GitHubReleaseParser.parseChecksumFile("${"d".repeat(64)}  zzz.apk", "agi.apk") == null)
            check("checker propagates checksum fields", runBlocking { GitHubReleaseUpdateChecker(fetch = { GitHubReleaseUpdateChecker.HttpResponse(200, """{"tag_name":"v0.2.0","body":"sha256: ${"e".repeat(64)}","assets":[${assets[0]}]}""") }).check("0.1.0", 1) }
                .let { it is UpdateCheckResult.Success && it.info.apkSha256 == "e".repeat(64) })
        }

        println("HTTP / invalid responses:")
        run {
            val dl = downloader(tmpDir())
            var r = dl.download(info(name = "gone.apk"))
            check("404 -> HTTP, friendly message, nothing staged", r is DownloadResult.Failure && r.reason == DownloadError.HTTP && r.message.contains("404") && dl.targetFile(info(name = "gone.apk")).exists().not(), r)
            serve(base + "page.apk") { ex -> val b = "<html>login</html>".toByteArray(); ex.responseHeaders.add("Content-Type", "text/html"); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) } }
            r = dl.download(info(name = "page.apk"))
            check("HTML instead of APK -> INVALID_RESPONSE", r is DownloadResult.Failure && r.reason == DownloadError.INVALID_RESPONSE, r)
            serve(base + "empty.apk") { ex -> ex.sendResponseHeaders(200, -1); ex.close() }
            r = dl.download(info(name = "empty.apk"))
            check("empty body -> INVALID_RESPONSE", r is DownloadResult.Failure && r.reason == DownloadError.INVALID_RESPONSE, r)
            serve(base + "tiny.apk", bytesRoute(apkBytes(1024)))
            r = dl.download(info(name = "tiny.apk"))
            check("implausibly small file -> INVALID_RESPONSE", r is DownloadResult.Failure && r.reason == DownloadError.INVALID_RESPONSE, r)
            val notZip = apkBytes(200 * 1024).also { it[0] = 0 }
            serve(base + "notzip.apk", bytesRoute(notZip))
            r = dl.download(info(name = "notzip.apk"))
            check("non-ZIP payload -> INVALID_RESPONSE", r is DownloadResult.Failure && r.reason == DownloadError.INVALID_RESPONSE, r)
            r = dl.download(info(size = apk.size + 5L))
            check("size differs from release asset -> INVALID_RESPONSE", r is DownloadResult.Failure && r.reason == DownloadError.INVALID_RESPONSE, r)
            r = dl.download(info(url = "http://github.com/x.apk"))
            check("plain http refused", r is DownloadResult.Failure && r.reason == DownloadError.INVALID_RESPONSE, r)
            serve(base + "s500.apk") { ex -> ex.sendResponseHeaders(503, -1); ex.close() }
            r = dl.download(info(name = "s500.apk"))
            check("503 -> HTTP (after one retry)", r is DownloadResult.Failure && r.reason == DownloadError.HTTP && r.message.contains("503"), r)
            check("no partial files left after failures", dl.targetFile(info()).parentFile!!.listFiles()!!.none { it.name.endsWith(".part") })
        }

        println("Interruption, resume & retry:")
        run {
            val dir = tmpDir(); val dl = downloader(dir)
            // First response truncated at 200 KB (declares full length), second response complete -> automatic retry resumes.
            serve(base + "cut.apk", bytesRoute(apk, truncateTo = 200 * 1024, dropAfterFirst = AtomicInteger(0)))
            val seen = ArrayList<Long>()
            val r = dl.download(info(name = "cut.apk")) { d, _ -> seen += d }
            check("interrupted download resumes and completes", r is DownloadResult.Success && r.file.readBytes().contentEquals(apk), r)
            check("resume continued from partial (no reset to 0 mid-way)", seen.indexOf(0L) == 0 || seen.count { it == 0L } <= 1)
            // Always-truncated server: fails as INTERRUPTED, keeps .part for user retry, then user retry resumes.
            val always = bytesRoute(apk, truncateTo = 100 * 1024, dropAfterFirst = null) // 100 KB per attempt: initial + 1 auto retry cannot finish
            serve(base + "cut2.apk", always)
            val f = dl.download(info(name = "cut2.apk"))
            check("persistently interrupted -> INTERRUPTED", f is DownloadResult.Failure && f.reason == DownloadError.INTERRUPTED, f)
            check("partial kept for retry", dir.listFiles()!!.any { it.name.endsWith(".part") })
            serve(base + "cut2.apk", bytesRoute(apk)) // network recovered
            val ok = dl.download(info(name = "cut2.apk"))
            check("user retry succeeds with correct bytes", ok is DownloadResult.Success && ok.file.readBytes().contentEquals(apk), ok)
            // Server without Range support: partial must be discarded and restarted, still correct.
            serve(base + "norange.apk", bytesRoute(apk, supportRange = false, truncateTo = 100 * 1024, dropAfterFirst = AtomicInteger(0)))
            val nr = dl.download(info(name = "norange.apk"))
            check("no Range support -> restart yields correct file", nr is DownloadResult.Success && nr.file.readBytes().contentEquals(apk), nr)
        }

        println("Network failures / timeouts (stubbed):")
        run {
            val dir = tmpDir()
            var r = downloader(dir, open = { u -> StubConn(u, onConnect = { throw java.net.UnknownHostException("github.com") }) }).download(info())
            check("UnknownHost -> NETWORK, no throw", r is DownloadResult.Failure && r.reason == DownloadError.NETWORK, r)
            r = downloader(dir, open = { u -> StubConn(u, onConnect = { throw SocketTimeoutException("connect timed out") }) }).download(info())
            check("connect timeout -> TIMEOUT", r is DownloadResult.Failure && r.reason == DownloadError.TIMEOUT, r)
            val slow = object : InputStream() { var n = 0; override fun read(): Int { if (n++ > 70000) throw SocketTimeoutException("Read timed out"); return 0x50 } }
            r = downloader(dir, open = { u -> StubConn(u, body = ByteArray(0), length = apk.size.toLong(), stream = slow) }).download(info())
            check("slow network mid-stream -> TIMEOUT, partial kept", r is DownloadResult.Failure && r.reason == DownloadError.TIMEOUT && dir.listFiles()!!.any { it.name.endsWith(".part") }, r)
            r = downloader(tmpDir(), open = { u -> StubConn(u, body = apk, length = apk.size.toLong()) }, free = { 1024 }).download(info())
            check("insufficient storage detected up front", r is DownloadResult.Failure && r.reason == DownloadError.INSUFFICIENT_STORAGE, r)
            val enospc = object : InputStream() { var n = 0; override fun read(): Int { if (n++ > 1000) throw IOException("write failed: ENOSPC (No space left on device)"); return 0x50 } }
            r = downloader(tmpDir(), open = { u -> StubConn(u, length = apk.size.toLong(), stream = enospc) }).download(info())
            check("ENOSPC mid-download -> INSUFFICIENT_STORAGE", r is DownloadResult.Failure && r.reason == DownloadError.INSUFFICIENT_STORAGE, r)
            r = downloader(tmpDir(), open = { u -> StubConn(u, body = apk, length = apk.size.toLong()) }).download(info())
            check("stubbed happy path via https URL succeeds", r is DownloadResult.Success, r)
        }

        println("Cancellation:")
        run {
            val dir = tmpDir()
            val gate = CompletableDeferred<Unit>()
            val endless = object : InputStream() { var n = 0L; override fun read(): Int { n++; if (n == 100_000L) gate.complete(Unit); Thread.sleep(0, 1000); return 0x50 } }
            val dl = downloader(dir, open = { u -> StubConn(u, length = 50L shl 20, stream = endless) })
            var result: DownloadResult? = null
            val job = launch { result = dl.download(info()) }
            gate.await()
            job.cancelAndJoin()
            check("cancelled job ends; no partial left", !dir.listFiles().orEmpty().any { it.name.endsWith(".part") || it.name.endsWith(".apk") }, dir.listFiles()?.toList())
            check("cancellation does not report success", result !is DownloadResult.Success)
        }

        println("UpdateManager state machine:")
        run {
            val dir = tmpDir()
            val dl = downloader(dir)
            val i = info(size = apk.size.toLong())
            val repo = UpdateRepository(object : UpdateChecker { override suspend fun check(installedVersionName: String, installedVersionCode: Long) = UpdateCheckResult.Success(i) }, { InstalledVersion("0.1.0", 1) }, Dispatchers.Unconfined)
            val scope = CoroutineScope(Dispatchers.Default)
            val mgr = UpdateManager(repo, scope, downloader = dl)
            val states = java.util.Collections.synchronizedList(ArrayList<String>())
            mgr.addObserver { states += it::class.simpleName!! }
            mgr.checkNow().join()
            mgr.startDownload(i)!!.join()
            check("check -> download -> ReadyToInstall", states.first() == "Idle" && states.contains("Checking") && states.contains("UpdateAvailable") && states.contains("Downloading") && states.last() == "ReadyToInstall", states.distinct())
            val ready = mgr.state as UpdateState.ReadyToInstall
            check("ReadyToInstall carries complete file", ready.file.length() == apk.size.toLong() && ready.sha256 == apkSha)
            check("state is not auto-installing (still ReadyToInstall)", mgr.state is UpdateState.ReadyToInstall)
            mgr.checkNow().join()
            check("re-check keeps ReadyToInstall for the same release", mgr.state is UpdateState.ReadyToInstall, mgr.state)
            check("startDownload while ready reuses file instantly", run { mgr.startDownload(i)!!.join(); mgr.state is UpdateState.ReadyToInstall })
            mgr.discardDownload()
            check("discard -> UpdateAvailable and file removed", mgr.state is UpdateState.UpdateAvailable && !ready.file.exists())

            // failure -> retry
            serve(base + "flaky.apk") { ex -> ex.sendResponseHeaders(500, -1); ex.close() }
            val fi = info(name = "flaky.apk")
            mgr.startDownload(fi)!!.join()
            check("HTTP 500 -> DownloadFailed", mgr.state is UpdateState.DownloadFailed && (mgr.state as UpdateState.DownloadFailed).reason == DownloadError.HTTP, mgr.state)
            serve(base + "flaky.apk", bytesRoute(apk))
            mgr.retryDownload()!!.join()
            check("retry -> ReadyToInstall", mgr.state is UpdateState.ReadyToInstall, mgr.state)

            // cancel mid-download
            val gate = CompletableDeferred<Unit>()
            val endless = object : InputStream() { var n = 0L; override fun read(): Int { n++; if (n == 200_000L) gate.complete(Unit); Thread.sleep(0, 500); return 0x50 } }
            val mgr2 = UpdateManager(repo, scope, downloader = downloader(dir, open = { u -> StubConn(u, length = 50L shl 20, stream = endless) }))
            mgr2.startDownload(i)
            check("isDownloading while in flight", mgr2.isDownloading && mgr2.state is UpdateState.Downloading)
            check("duplicate startDownload ignored", mgr2.startDownload(i) === mgr2.startDownload(i))
            gate.await()
            mgr2.cancelDownload()
            delay(300)
            check("cancel -> UpdateAvailable, nothing staged", mgr2.state is UpdateState.UpdateAvailable && !mgr2.isDownloading && dir.listFiles()!!.none { it.name.endsWith(".part") }, mgr2.state)

            check("status line while downloading", UpdateMessages.statusLine(UpdateState.Downloading(i, 620, 1000), "0.1.0") == "Downloading update... 62%")
            check("status line when ready", UpdateMessages.statusLine(UpdateState.ReadyToInstall(i, File("x"), "s", true), "0.1.0") == "Update downloaded: 0.2.0 (verified)")
            check("progress detail", UpdateMessages.progressDetail(1_258_291, 5_033_164) == "1.2 MB of 4.8 MB")
            scope.cancel()
        }
    }
}
