package com.agi.assistant.core.update

import org.json.JSONException
import org.json.JSONObject

/**
 * Pure parser for the GitHub "latest release" payload
 * (`GET /repos/{owner}/{repo}/releases/latest`). No Android or network
 * dependencies so it is unit-testable on the JVM.
 */
object GitHubReleaseParser {

    class ParseException(message: String, val reason: UpdateError, cause: Throwable? = null) : Exception(message, cause)

    /** A parsed release before comparing against the installed version. */
    data class Release(
        val tag: String,
        val version: SemanticVersion,
        val name: String,
        val body: String,
        val publishedAt: String,
        val htmlUrl: String,
        val apkUrl: String,
        val apkName: String,
        val apkSize: Long,
        val versionCode: Long?,
        val mandatory: Boolean,
        val prerelease: Boolean,
        val draft: Boolean,
    )

    /**
     * @throws ParseException with [UpdateError.MALFORMED_RESPONSE], [UpdateError.NO_APK_ASSET]
     *         or [UpdateError.INVALID_VERSION].
     */
    fun parse(body: String): Release {
        val json = try {
            JSONObject(body)
        } catch (e: JSONException) {
            throw ParseException("Release response is not valid JSON", UpdateError.MALFORMED_RESPONSE, e)
        }
        return parse(json)
    }

    fun parse(json: JSONObject): Release {
        val tag = json.optString("tag_name").trim()
        if (tag.isEmpty()) throw ParseException("Release has no tag_name", UpdateError.MALFORMED_RESPONSE)
        val version = SemanticVersion.parse(tag)
            ?: throw ParseException("Tag '$tag' is not a semantic version", UpdateError.INVALID_VERSION)

        val assets = json.optJSONArray("assets")
        val apk = selectApkAsset(assets?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it) } } ?: emptyList())
            ?: throw ParseException("Release $tag has no .apk asset", UpdateError.NO_APK_ASSET)

        val url = apk.optString("browser_download_url").trim()
        if (!url.startsWith("https://", ignoreCase = true))
            throw ParseException("APK asset URL is not HTTPS: $url", UpdateError.MALFORMED_RESPONSE)

        val body = json.optString("body").takeIf { it != "null" } ?: ""
        return Release(
            tag = tag,
            version = version,
            name = json.optString("name").takeIf { it.isNotBlank() && it != "null" } ?: tag,
            body = body,
            publishedAt = json.optString("published_at").takeIf { it != "null" } ?: "",
            htmlUrl = json.optString("html_url").takeIf { it != "null" } ?: "",
            apkUrl = url,
            apkName = apk.optString("name"),
            apkSize = apk.optLong("size", -1L),
            versionCode = extractVersionCode(body),
            mandatory = extractMandatory(body),
            prerelease = json.optBoolean("prerelease", false),
            draft = json.optBoolean("draft", false),
        )
    }

    /**
     * Picks the best APK among the release assets without assuming a file name:
     * 1. name ends with `.apk` (case-insensitive) and the asset is uploaded;
     * 2. prefer universal builds over ABI splits, release over debug, then the largest.
     */
    fun selectApkAsset(assets: List<JSONObject>): JSONObject? {
        val apks = assets.filter { a ->
            val name = a.optString("name").lowercase()
            val type = a.optString("content_type").lowercase()
            val state = a.optString("state", "uploaded")
            (name.endsWith(".apk") || type == "application/vnd.android.package-archive") &&
                !name.endsWith(".apk.sha256") && !name.endsWith(".apks") && state == "uploaded"
        }
        if (apks.isEmpty()) return null
        return apks.sortedWith(
            compareByDescending<JSONObject> { score(it.optString("name").lowercase()) }
                .thenByDescending { it.optLong("size", 0L) }
        ).first()
    }

    private fun score(name: String): Int {
        var s = 0
        if ("universal" in name) s += 4
        if ("release" in name) s += 2
        if ("debug" in name) s -= 2
        if (Regex("(arm64|armeabi|x86|v7a|v8a)").containsMatchIn(name)) s -= 3
        return s
    }

    /** `mandatory: true`, `mandatory=yes` or a `[mandatory]` tag anywhere in the release notes. */
    internal fun extractMandatory(body: String): Boolean =
        Regex("(?im)^\\s*mandatory\\s*[:=]\\s*(true|yes|1)\\s*$").containsMatchIn(body) ||
            Regex("(?i)\\[mandatory]").containsMatchIn(body)

    /** Optional `versionCode: 12` / `versionCode=12` line in the release notes. */
    internal fun extractVersionCode(body: String): Long? =
        Regex("(?im)^\\s*versionCode\\s*[:=]\\s*(\\d+)\\s*$").find(body)?.groupValues?.get(1)?.toLongOrNull()
}
