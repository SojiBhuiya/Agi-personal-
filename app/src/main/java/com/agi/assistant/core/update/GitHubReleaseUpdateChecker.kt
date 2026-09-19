package com.agi.assistant.core.update

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * [UpdateChecker] backed by the GitHub Releases API:
 * `https://api.github.com/repos/{owner}/{repo}/releases/latest`.
 *
 * Uses HttpsURLConnection like the rest of the app (no extra HTTP library) with the platform's
 * default TLS validation and hostname verification – nothing is relaxed. Transient failures
 * (DNS "no address", timeouts, resets, HTTP 5xx) are retried a couple of times with a short
 * back-off; every failure is classified by [NetworkErrorClassifier] so the UI can tell
 * "no internet" from "DNS" from "TLS" from "GitHub error".
 * The [fetch] function is injectable so tests can supply canned responses.
 */
class GitHubReleaseUpdateChecker(
    private val owner: String = DEFAULT_OWNER,
    private val repo: String = DEFAULT_REPO,
    private val timeoutMs: Int = 15_000,
    private val fetch: (url: String) -> HttpResponse = { url -> defaultFetch(url, timeoutMs) },
    /** Total attempts for retryable failures (DNS blip, timeout, reset, 5xx). 1 = no retry. */
    private val maxAttempts: Int = DEFAULT_ATTEMPTS,
    /** Back-off between attempts, injectable so tests do not sleep. */
    private val backoffMs: LongArray = longArrayOf(700L, 1_500L),
    private val sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) : UpdateChecker {

    data class HttpResponse(val code: Int, val body: String)

    /** One line per attempt ("attempt 1: DNS UnknownHostException: …"); safe to show under "Details". */
    @Volatile var lastTrace: List<String> = emptyList()
        private set

    val latestReleaseUrl: String get() = "https://api.github.com/repos/$owner/$repo/releases/latest"

    override suspend fun check(installedVersionName: String, installedVersionCode: Long): UpdateCheckResult {
        val trace = ArrayList<String>()
        var response: HttpResponse? = null
        var failure: UpdateCheckResult.Failure? = null
        var attempt = 0
        while (attempt < maxAttempts.coerceAtLeast(1)) {
            attempt++
            val c: NetworkErrorClassifier.Classified = try {
                val r = fetch(latestReleaseUrl)
                if (r.code in 200..299) { response = r; trace += "attempt $attempt: HTTP ${r.code}"; break }
                trace += "attempt $attempt: HTTP ${r.code}"
                NetworkErrorClassifier.classifyHttp(r.code, r.body).also { failure = UpdateCheckResult.Failure(it.reason, it.message) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                NetworkErrorClassifier.classify(e).also {
                    trace += "attempt $attempt: ${it.reason} ${NetworkErrorClassifier.brief(e)}"
                    failure = UpdateCheckResult.Failure(it.reason, it.message, e)
                }
            }
            if (!c.retryable || attempt >= maxAttempts) break
            sleep(backoffMs.getOrElse(attempt - 1) { backoffMs.lastOrNull() ?: 0L })
        }
        lastTrace = trace
        if (response == null) {
            val f = failure ?: UpdateCheckResult.Failure(UpdateError.UNKNOWN, "Update check failed")
            return if (attempt > 1) f.copy(message = f.message + " (tried $attempt times)") else f
        }

        val release = try {
            GitHubReleaseParser.parse(response.body)
        } catch (e: GitHubReleaseParser.ParseException) {
            return UpdateCheckResult.Failure(e.reason, e.message ?: "Unreadable release", e)
        } catch (e: Exception) {
            return UpdateCheckResult.Failure(UpdateError.MALFORMED_RESPONSE, "Unreadable release: ${e.message}", e)
        }

        val installed = SemanticVersion.parse(installedVersionName)
        val newer = UpdatePolicy.isNewer(release.version, release.versionCode, installed, installedVersionCode)

        return UpdateCheckResult.Success(
            UpdateInfo(
                versionName = release.version.toString(),
                versionCode = release.versionCode,
                releaseTag = release.tag,
                releaseName = release.name,
                releaseNotes = release.body,
                apkDownloadUrl = release.apkUrl,
                apkAssetName = release.apkName,
                apkSizeBytes = release.apkSize,
                publishedAt = release.publishedAt,
                htmlUrl = release.htmlUrl,
                isNewerVersion = newer,
                isMandatory = release.mandatory,
                apkSha256 = release.sha256,
                checksumAssetUrl = release.checksumAssetUrl,
            )
        )
    }

    companion object {
        const val DEFAULT_OWNER = "SojiBhuiya"
        const val DEFAULT_REPO = "Agi-personal-"
        const val DEFAULT_ATTEMPTS = 3

        /** Blocking HTTPS GET. Must run on a background dispatcher. */
        fun defaultFetch(url: String, timeoutMs: Int): HttpResponse {
            require(url.startsWith("https://")) { "Update checks must use HTTPS" }
            val conn = URL(url).openConnection() as HttpsURLConnection
            try {
                conn.requestMethod = "GET"
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                conn.setRequestProperty("User-Agent", "AGI-Assistant-Android")
                conn.setRequestProperty("Accept-Encoding", "identity")
                conn.useCaches = false
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                return HttpResponse(code, body)
            } finally {
                conn.disconnect()
            }
        }
    }
}
