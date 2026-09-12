package com.agi.assistant.core.update

/** Everything the UI needs to describe an available (or current) release. */
data class UpdateInfo(
    /** Normalised version, e.g. `0.2.0` (from the tag with the leading `v` stripped). */
    val versionName: String,
    /** Android versionCode if the release exposes one (e.g. `versionCode: 3` in the notes); null otherwise. */
    val versionCode: Long?,
    /** Raw GitHub tag, e.g. `v0.2.0`. */
    val releaseTag: String,
    val releaseName: String,
    /** Release body / changelog (Markdown as written on GitHub). */
    val releaseNotes: String,
    /** `browser_download_url` of the first suitable `.apk` asset. */
    val apkDownloadUrl: String,
    val apkAssetName: String,
    val apkSizeBytes: Long,
    /** ISO-8601 timestamp from GitHub (`published_at`). */
    val publishedAt: String,
    /** Web page of the release (fallback when in-app install is not possible). */
    val htmlUrl: String,
    /** True when [versionName] is strictly newer than the installed version. */
    val isNewerVersion: Boolean,
    /**
     * True when the release notes contain a `mandatory: true` line or a `[mandatory]` marker.
     * Mandatory updates cannot be postponed from the update dialog.
     */
    val isMandatory: Boolean = false,
    /** Expected SHA-256 (lower-case hex) of the APK when the release publishes one; null = unverified. */
    val apkSha256: String? = null,
    /** HTTPS URL of a checksum asset (`<apk>.sha256` or `SHA256SUMS`) to fetch before installing, if [apkSha256] is not inline. */
    val checksumAssetUrl: String? = null,
) {
    val version: SemanticVersion? get() = SemanticVersion.parse(versionName)
}
