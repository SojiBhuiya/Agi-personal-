package com.agi.assistant.core.update

/** Version-comparison rules shared by the checker, the installer preflight and the tests. */
object UpdatePolicy {
    /**
     * Android upgrades are decided by **versionCode**: when the release publishes one
     * (`versionCode: N` in the notes – every release built by our workflow does) it is authoritative,
     * so a same-versionCode rebuild is never an update and a lower one is never a downgrade offer.
     * Only releases without a versionCode fall back to comparing semantic versionNames.
     * An unparseable installed version never reports an update.
     */
    fun isNewer(releaseVersion: SemanticVersion, releaseVersionCode: Long?, installedVersion: SemanticVersion?, installedVersionCode: Long): Boolean {
        if (releaseVersionCode != null && installedVersionCode > 0) return releaseVersionCode > installedVersionCode
        if (installedVersion == null) return false
        return releaseVersion > installedVersion
    }

    /** Canonical asset name produced by the release workflow for a version. */
    fun expectedApkName(versionName: String) = "agi-assistant-$versionName-release.apk"
}
