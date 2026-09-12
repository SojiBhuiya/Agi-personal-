package com.agi.assistant.core.update

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * [UpdateChecker] backed by the GitHub Releases API:
 * `https://api.github.com/repos/{owner}/{repo}/releases/latest`.
 *
 * Uses HttpURLConnection like the rest of the app (no extra HTTP library).
 * The [fetch] function is injectable so tests can supply canned responses.
 */
class GitHubReleaseUpdateChecker(
    private val owner: String = DEFAULT_OWNER,
    private val repo: String = DEFAULT_REPO,
    private val timeoutMs: Int = 15_000,
    private val fetch: (url: String) -> HttpResponse = { url -> defaultFetch(url, timeoutMs) },
) : UpdateChecker {

    data class HttpResponse(val code: Int, val body: String)

    val latestReleaseUrl: String get() = "https://api.github.com/repos/$owner/$repo/releases/latest"

    override suspend fun check(installedVersionName: String, installedVersionCode: Long): UpdateCheckResult {
        val response = try {
            fetch(latestReleaseUrl)
        } catch (e: IOException) {
            return UpdateCheckResult.Failure(UpdateError.NETWORK, "Could not reach GitHub: ${e.message ?: e.javaClass.simpleName}", e)
        } catch (e: Exception) {
            return UpdateCheckResult.Failure(UpdateError.UNKNOWN, "Update check failed: ${e.message ?: e.javaClass.simpleName}", e)
        }

        if (response.code !in 200..299) {
            val msg = when (response.code) {
                404 -> "No releases have been published yet."
                403, 429 -> "GitHub rate limit reached. Try again later."
                else -> "GitHub returned HTTP ${response.code}."
            }
            return UpdateCheckResult.Failure(UpdateError.HTTP, msg)
        }

        val release = try {
            GitHubReleaseParser.parse(response.body)
        } catch (e: GitHubReleaseParser.ParseException) {
            return UpdateCheckResult.Failure(e.reason, e.message ?: "Unreadable release", e)
        } catch (e: Exception) {
            return UpdateCheckResult.Failure(UpdateError.MALFORMED_RESPONSE, "Unreadable release: ${e.message}", e)
        }

        val installed = SemanticVersion.parse(installedVersionName)
        val newer = when {
            installed == null -> false // unknown installed version: never claim an update
            release.version > installed -> true
            release.version < installed -> false
            // Same versionName: fall back to versionCode when the release publishes one.
            release.versionCode != null && installedVersionCode > 0 -> release.versionCode > installedVersionCode
            else -> false
        }

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
            )
        )
    }

    companion object {
        const val DEFAULT_OWNER = "SojiBhuiya"
        const val DEFAULT_REPO = "Agi-personal-"

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
