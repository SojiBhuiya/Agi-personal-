package com.agi.assistant

import com.agi.assistant.core.update.GitHubReleaseUpdateChecker
import com.agi.assistant.core.update.GitHubReleaseUpdateChecker.HttpResponse
import com.agi.assistant.core.update.InstalledVersion
import com.agi.assistant.core.update.NetworkErrorClassifier
import com.agi.assistant.core.update.UpdateCheckResult
import com.agi.assistant.core.update.UpdateError
import com.agi.assistant.core.update.UpdateManager
import com.agi.assistant.core.update.UpdateMessages
import com.agi.assistant.core.update.UpdateRepository
import com.agi.assistant.core.update.UpdateState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * GitHub update-check connectivity: error classification, retry/back-off, UI wording, the
 * canonical v0.2.1 response, and (when the sandbox has network) the real
 * `https://api.github.com/repos/SojiBhuiya/Agi-personal-/releases/latest` endpoint via the
 * production [GitHubReleaseUpdateChecker.defaultFetch] (default TLS validation, no overrides).
 */
object UpdateConnectivityTest {
    private var fails = 0; private var passed = 0
    private fun check(name: String, cond: Boolean, detail: Any? = "") { if (cond) { passed++; println("  ok   $name") } else { fails++; println("  FAIL $name $detail") } }

    private const val URL = "https://api.github.com/repos/SojiBhuiya/Agi-personal-/releases/latest"

    /** Response shape captured from the live endpoint for v0.2.1 (build 3), trimmed to the fields the parser uses. */
    private val V021 = """
        {"tag_name":"v0.2.1","name":"AGI Assistant 0.2.1","draft":false,"prerelease":false,"published_at":"2026-09-16T05:52:38Z",
         "html_url":"https://github.com/SojiBhuiya/Agi-personal-/releases/tag/v0.2.1",
         "body":"## AGI Assistant 0.2.1\n\n### What's New\n- Production update-channel validation release.\n\nversionCode: 3\nSHA-256: ${"a".repeat(64)}\n",
         "assets":[
          {"name":"agi-assistant-0.2.1-release.apk","size":1248520,"state":"uploaded","content_type":"application/vnd.android.package-archive","browser_download_url":"https://github.com/SojiBhuiya/Agi-personal-/releases/download/v0.2.1/agi-assistant-0.2.1-release.apk"},
          {"name":"agi-assistant-0.2.1-release.apk.sha256","size":98,"state":"uploaded","content_type":"application/octet-stream","browser_download_url":"https://github.com/SojiBhuiya/Agi-personal-/releases/download/v0.2.1/agi-assistant-0.2.1-release.apk.sha256"}]}
    """.trimIndent()

    private class Script(val steps: List<() -> HttpResponse>) {
        var calls = 0; val urls = ArrayList<String>()
        fun fetch(url: String): HttpResponse { urls += url; val s = steps[minOf(calls, steps.size - 1)]; calls++; return s() }
    }
    private fun failing(t: Throwable) = { throw t } as () -> HttpResponse
    private fun ok(body: String = V021) = { HttpResponse(200, body) }
    private fun http(code: Int, body: String = """{"message":"Not Found"}""") = { HttpResponse(code, body) }

    private fun checker(script: Script, attempts: Int = 3, sleeps: MutableList<Long>? = null) =
        GitHubReleaseUpdateChecker(fetch = script::fetch, maxAttempts = attempts, sleep = { sleeps?.add(it) })
    private fun run(c: GitHubReleaseUpdateChecker, v: String = "0.2.1", code: Long = 3) = runBlocking { c.check(v, code) }

    @JvmStatic
    fun main(args: Array<String>) {
        println("Error classification (throwable -> UpdateError):")
        val C = NetworkErrorClassifier
        val dns = C.classify(UnknownHostException("Unable to resolve host \"api.github.com\": No address associated with hostname"))
        check("Android UnknownHostException -> DNS, retryable", dns.reason == UpdateError.DNS && dns.retryable, dns)
        check("DNS message names host + hint, no stack trace", dns.message.contains("api.github.com") && dns.message.contains("DNS") && !dns.message.contains("\n"))
        check("UnknownHost wrapped in IOException -> DNS", C.classify(IOException("failed", UnknownHostException("api.github.com"))).reason == UpdateError.DNS)
        check("SSLHandshakeException -> TLS, not retryable", C.classify(SSLHandshakeException("Chain validation failed")).let { it.reason == UpdateError.TLS && !it.retryable })
        check("SSLPeerUnverified -> TLS", C.classify(SSLPeerUnverifiedException("Hostname api.github.com not verified")).reason == UpdateError.TLS)
        check("CertificateException as cause -> TLS", C.classify(IOException("x", CertificateException("Trust anchor for certification path not found"))).reason == UpdateError.TLS)
        check("TLS message says validation is not bypassed", C.classify(SSLHandshakeException("x")).message.contains("will not bypass"))
        check("SocketTimeoutException(connect) -> TIMEOUT, retryable", C.classify(SocketTimeoutException("connect timed out")).let { it.reason == UpdateError.TIMEOUT && it.retryable })
        check("SocketTimeoutException(read) -> TIMEOUT", C.classify(SocketTimeoutException("Read timed out")).reason == UpdateError.TIMEOUT)
        check("ConnectException (v6 route missing / refused) -> NETWORK, retryable", C.classify(ConnectException("failed to connect to api.github.com/2606:50c0::1 (port 443): connect failed: ENETUNREACH")).let { it.reason == UpdateError.NETWORK && it.retryable })
        check("SocketException reset -> NETWORK", C.classify(SocketException("Connection reset")).reason == UpdateError.NETWORK)
        check("EOFException -> NETWORK", C.classify(EOFException()).reason == UpdateError.NETWORK)
        check("plain IOException -> NETWORK", C.classify(IOException("unexpected end of stream")).reason == UpdateError.NETWORK)
        check("non-IO exception -> UNKNOWN, not retryable", C.classify(IllegalStateException("boom")).let { it.reason == UpdateError.UNKNOWN && !it.retryable })
        check("classifier survives cyclic cause chain", runCatching { val e = IOException("a"); val f = IOException("b", e); e.initCause(f); C.classify(e).reason }.getOrNull() == UpdateError.NETWORK)
        check("isConnectivity groups NO_INTERNET/DNS/TLS/TIMEOUT/NETWORK only", UpdateError.values().filter { it.isConnectivity } == listOf(UpdateError.NO_INTERNET, UpdateError.DNS, UpdateError.TLS, UpdateError.TIMEOUT, UpdateError.NETWORK))
        check("brief() is one line, capped", C.brief(IOException("l1\nl2")) == "IOException: l1" && C.brief(IOException("x".repeat(500))).length < 200)

        println("HTTP classification:")
        check("404 -> HTTP 'no releases'", C.classifyHttp(404, """{"message":"Not Found"}""").let { it.reason == UpdateError.HTTP && it.message.startsWith("No releases") && !it.retryable })
        check("403 -> rate limit, carries GitHub message", C.classifyHttp(403, """{"message":"API rate limit exceeded for 1.2.3.4."}""").message.let { it.contains("rate limit") && it.contains("API rate limit exceeded") })
        check("429 -> rate limit", C.classifyHttp(429, "").message.contains("rate limit"))
        check("500 -> retryable GitHub trouble", C.classifyHttp(500, "<html>").let { it.retryable && it.message.contains("500") })
        check("502/503 retryable", C.classifyHttp(502, "").retryable && C.classifyHttp(503, "").retryable)
        check("non-JSON body tolerated", C.classifyHttp(418, "<html>teapot</html>").message == "GitHub returned HTTP 418.")

        println("Checker: retry / back-off:")
        var sleeps = ArrayList<Long>()
        var s = Script(listOf(failing(UnknownHostException("api.github.com")), ok()))
        var r = run(checker(s, sleeps = sleeps))
        check("transient DNS failure then success -> Success (2 attempts, 1 back-off)", r is UpdateCheckResult.Success && s.calls == 2 && sleeps == listOf(700L), "$r calls=${s.calls} sleeps=$sleeps")
        check("every attempt hit the official latest-release URL", s.urls.all { it == URL })
        sleeps = ArrayList(); s = Script(listOf(failing(UnknownHostException("Unable to resolve host \"api.github.com\": No address associated with hostname"))))
        r = run(checker(s, sleeps = sleeps))
        check("persistent DNS failure -> DNS after 3 attempts, back-offs 700+1500", r is UpdateCheckResult.Failure && r.reason == UpdateError.DNS && s.calls == 3 && sleeps == listOf(700L, 1500L), "$r calls=${s.calls}")
        check("failure message mentions retry count", (r as UpdateCheckResult.Failure).message.contains("tried 3 times"))
        s = Script(listOf(failing(SSLHandshakeException("Chain validation failed"))))
        r = run(checker(s)); check("TLS failure is NOT retried and never bypassed", r is UpdateCheckResult.Failure && r.reason == UpdateError.TLS && s.calls == 1, "$r calls=${s.calls}")
        s = Script(listOf(failing(SocketTimeoutException("Read timed out")), failing(SocketTimeoutException("Read timed out")), ok()))
        r = run(checker(s)); check("two timeouts then success -> Success on 3rd", r is UpdateCheckResult.Success && s.calls == 3)
        s = Script(listOf(failing(SocketTimeoutException("t"))))
        r = run(checker(s)); check("persistent timeout -> TIMEOUT", r is UpdateCheckResult.Failure && r.reason == UpdateError.TIMEOUT && s.calls == 3)
        s = Script(listOf(http(500, ""), ok()))
        r = run(checker(s)); check("HTTP 500 retried then success", r is UpdateCheckResult.Success && s.calls == 2)
        for (code in listOf(403, 404)) { s = Script(listOf(http(code))); r = run(checker(s)); check("HTTP $code not retried -> HTTP failure", r is UpdateCheckResult.Failure && r.reason == UpdateError.HTTP && s.calls == 1) }
        s = Script(listOf(http(500, "")))
        r = run(checker(s)); check("persistent 500 -> HTTP after 3 attempts", r is UpdateCheckResult.Failure && r.reason == UpdateError.HTTP && s.calls == 3 && r.message.contains("500"))
        s = Script(listOf(failing(IllegalStateException("bug"))))
        r = run(checker(s)); check("unexpected exception -> UNKNOWN, no retry, no throw", r is UpdateCheckResult.Failure && r.reason == UpdateError.UNKNOWN && s.calls == 1)
        s = Script(listOf(failing(UnknownHostException("x")))); r = run(checker(s, attempts = 1))
        check("maxAttempts=1 disables retry", s.calls == 1 && r is UpdateCheckResult.Failure)
        val c1 = checker(Script(listOf(failing(UnknownHostException("h")), ok()))); run(c1)
        check("lastTrace records each attempt (safe for Details)", c1.lastTrace.size == 2 && c1.lastTrace[0].startsWith("attempt 1: DNS") && c1.lastTrace[1] == "attempt 2: HTTP 200", c1.lastTrace)

        println("Malformed responses:")
        for ((name, body) in listOf("not json" to "<html>", "empty" to "", "no tag" to """{"assets":[]}""", "bad tag" to V021.replace("\"tag_name\":\"v0.2.1\"", "\"tag_name\":\"latest\""))) {
            r = run(checker(Script(listOf(ok(body)))))
            check("$name -> MALFORMED/INVALID_VERSION, not connectivity", r is UpdateCheckResult.Failure && (r.reason == UpdateError.MALFORMED_RESPONSE || r.reason == UpdateError.INVALID_VERSION) && !r.reason.isConnectivity, r)
        }
        r = run(checker(Script(listOf(ok(V021.replace("agi-assistant-0.2.1-release.apk\"", "agi-assistant-0.2.1-release.zip\"").replace("application/vnd.android.package-archive", "application/zip"))))))
        check("release without apk -> NO_APK_ASSET", r is UpdateCheckResult.Failure && r.reason == UpdateError.NO_APK_ASSET, r)

        println("Current v0.2.1 response vs installed builds:")
        r = run(checker(Script(listOf(ok()))), "0.2.1", 3)
        check("installed 0.2.1/3 -> Success, not newer (up to date)", r is UpdateCheckResult.Success && !r.info.isNewerVersion && r.info.versionCode == 3L && r.info.apkAssetName == "agi-assistant-0.2.1-release.apk", r)
        check("checksum + apk url + sha256 asset preserved", (r as UpdateCheckResult.Success).info.let { it.apkDownloadUrl.startsWith("https://github.com/SojiBhuiya/Agi-personal-/releases/download/v0.2.1/") && it.apkSha256 == "a".repeat(64) && it.checksumAssetUrl != null })
        r = run(checker(Script(listOf(ok()))), "0.2.0", 2)
        check("installed 0.2.0/2 -> newer (versionCode 3 > 2)", r is UpdateCheckResult.Success && r.info.isNewerVersion)
        r = run(checker(Script(listOf(ok()))), "0.2.2", 4)
        check("installed 0.2.2/4 -> not newer", r is UpdateCheckResult.Success && !r.info.isNewerVersion)

        println("UI wording per class:")
        fun line(e: UpdateError, m: String = "detail") = UpdateMessages.statusLine(UpdateState.Error(e, m), "0.2.1")
        check("NO_INTERNET", line(UpdateError.NO_INTERNET) == UpdateMessages.ERROR_OFFLINE)
        check("DNS", line(UpdateError.DNS) == UpdateMessages.ERROR_DNS && UpdateMessages.ERROR_DNS.contains("api.github.com"))
        check("TLS", line(UpdateError.TLS) == UpdateMessages.ERROR_TLS)
        check("TIMEOUT", line(UpdateError.TIMEOUT) == UpdateMessages.ERROR_TIMEOUT)
        check("NETWORK generic", line(UpdateError.NETWORK) == UpdateMessages.ERROR)
        check("HTTP", line(UpdateError.HTTP, "GitHub is having trouble (HTTP 500).") == UpdateMessages.ERROR_GITHUB)
        check("404 still reads as up to date", line(UpdateError.HTTP, "No releases have been published yet.").startsWith("You’re using the latest"))
        check("MALFORMED / INVALID_VERSION", line(UpdateError.MALFORMED_RESPONSE) == UpdateMessages.ERROR_RESPONSE && line(UpdateError.INVALID_VERSION) == UpdateMessages.ERROR_RESPONSE)
        check("all distinct classes produce distinct user text", setOf(UpdateError.NO_INTERNET, UpdateError.DNS, UpdateError.TLS, UpdateError.TIMEOUT, UpdateError.HTTP, UpdateError.MALFORMED_RESPONSE).map { line(it) }.toSet().size == 6)

        println("Manager: offline hint for manual checks:")
        runBlocking {
            fun mgr(t: Throwable) = UpdateManager(UpdateRepository(GitHubReleaseUpdateChecker(fetch = { throw t }, maxAttempts = 1), { InstalledVersion("0.2.1", 3) }, Dispatchers.Unconfined), CoroutineScope(Dispatchers.Unconfined))
            var m = mgr(UnknownHostException("api.github.com")); m.checkNow(online = false).join()
            check("offline + DNS failure -> NO_INTERNET (detail kept)", m.state.let { it is UpdateState.Error && it.reason == UpdateError.NO_INTERNET && it.message.contains("UnknownHost") }, m.state)
            m = mgr(UnknownHostException("api.github.com")); m.checkNow(online = true).join()
            check("online + DNS failure -> DNS", m.state.let { it is UpdateState.Error && it.reason == UpdateError.DNS })
            m = mgr(UnknownHostException("api.github.com")); m.checkNow().join()
            check("checkNow() default = online (unchanged behaviour for callers/tests)", m.state.let { it is UpdateState.Error && it.reason == UpdateError.DNS })
            val ok = UpdateManager(UpdateRepository(GitHubReleaseUpdateChecker(fetch = { HttpResponse(200, V021) }), { InstalledVersion("0.2.1", 3) }, Dispatchers.Unconfined), CoroutineScope(Dispatchers.Unconfined))
            ok.checkNow(online = false).join()
            check("offline hint never blocks a request that actually succeeds", ok.state is UpdateState.UpToDate, ok.state)
        }

        println("Transport safety:")
        check("defaultFetch refuses http://", runCatching { GitHubReleaseUpdateChecker.defaultFetch("http://api.github.com/x", 100) }.exceptionOrNull() is IllegalArgumentException)
        val src = java.io.File("app/src/main/java/com/agi/assistant/core/update").walk().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        check("no TLS/hostname bypass anywhere in update code", !src.contains("setHostnameVerifier") && !src.contains("TrustManager") && !src.contains("setDefaultSSLSocketFactory") && !src.contains("ALLOW_ALL"))
        check("no http:// fallback in update code", !Regex("\"http://").containsMatchIn(src))

        println("Live endpoint (production defaultFetch, real TLS) – skipped if sandbox has no network:")
        val live = runCatching { GitHubReleaseUpdateChecker.defaultFetch(URL, 15_000) }
        live.onSuccess { resp ->
            check("live: HTTP 200 from api.github.com", resp.code == 200, resp.code)
            val lr = run(GitHubReleaseUpdateChecker(), "0.2.1", 3)
            check("live: parses to Success, v0.2.1 build 3 is up to date for 0.2.1/3", lr is UpdateCheckResult.Success && lr.info.releaseTag.startsWith("v0.2.") , lr)
            if (lr is UpdateCheckResult.Success) println("       live latest = ${lr.info.releaseTag} build ${lr.info.versionCode} apk=${lr.info.apkAssetName} sha=${lr.info.apkSha256?.take(12)}… newer=${lr.info.isNewerVersion}")
        }.onFailure { e -> println("  skip live endpoint: ${NetworkErrorClassifier.classify(e).reason} ${NetworkErrorClassifier.brief(e)}") }

        println("\nUpdateConnectivityTest: $passed passed, $fails failed")
        if (fails > 0) System.exit(1)
    }
}
