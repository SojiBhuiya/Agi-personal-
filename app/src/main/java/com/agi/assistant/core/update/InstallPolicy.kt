package com.agi.assistant.core.update

import java.io.File

/**
 * Platform-independent rules for handing a staged APK to the Android package installer.
 * Everything Android-specific (PackageManager, Intents, FileProvider) lives in `ui/ApkInstaller`;
 * this object only decides *whether* an install may start and *what* an installer result means,
 * so it can be unit-tested on the JVM.
 */
object InstallPolicy {
    /** What the platform could tell us about the staged APK (null fields = unknown). */
    data class ApkFacts(
        val packageName: String?, val versionCode: Long?, val versionName: String?, val parsed: Boolean,
        /** SHA-256 fingerprints (upper-case, colon separated) of the APK's signing certificates; null = platform could not read them. */
        val signerSha256: List<String>? = null,
    )

    sealed class Preflight {
        object Ok : Preflight()
        data class Blocked(val reason: InstallError, val message: String, val discardFile: Boolean = false) : Preflight()
    }

    /**
     * Checks that must pass before the installer is launched.
     *
     * @param installedPackage this app's applicationId (`com.agi.assistant`)
     * @param installedVersionCode currently installed versionCode
     * @param canInstallUnknownApps result of `PackageManager.canRequestPackageInstalls()` (API 26+)
     */
    fun preflight(
        file: File,
        info: UpdateInfo,
        apk: ApkFacts?,
        installedPackage: String,
        installedVersionCode: Long,
        canInstallUnknownApps: Boolean,
        /** Was the file's SHA-256 checked against the published checksum? Unverified files are never installed. */
        verified: Boolean = true,
        /** SHA-256 fingerprints of the *installed* app's signing certificates (null = unknown, skip the comparison). */
        installedSignerSha256: List<String>? = null,
    ): Preflight {
        if (!file.isFile || file.length() <= 0L)
            return Preflight.Blocked(InstallError.FILE_MISSING, "The downloaded update file is missing. Please download it again.", discardFile = true)
        if (!verified)
            return Preflight.Blocked(InstallError.UNVERIFIED, "This update could not be verified against the published checksum and will not be installed.", discardFile = true)
        if (apk != null) {
            if (!apk.parsed || apk.packageName == null)
                return Preflight.Blocked(InstallError.INVALID_APK, "The downloaded file is not a valid Android package. Please download it again.", discardFile = true)
            if (apk.packageName != installedPackage)
                return Preflight.Blocked(InstallError.PACKAGE_MISMATCH, "The downloaded package (${apk.packageName}) is not AGI Assistant and will not be installed.", discardFile = true)
            val code = apk.versionCode
            if (code != null && code <= installedVersionCode)
                return Preflight.Blocked(InstallError.NOT_NEWER, "This package (build $code) is not newer than the installed build $installedVersionCode, so Android would reject it.", discardFile = true)
            // Same signing key as the installed app? Android would refuse the update anyway; we say so up front
            // (only when both sides are known – never block a legitimate update on missing information).
            val apkSigners = apk.signerSha256?.map { it.uppercase() }
            val ours = installedSignerSha256?.map { it.uppercase() }
            if (!apkSigners.isNullOrEmpty() && !ours.isNullOrEmpty() && apkSigners.none { it in ours })
                return Preflight.Blocked(InstallError.SIGNATURE_MISMATCH, "This update is signed with a different key than the installed app, so Android would refuse to install it over the current version.", discardFile = true)
        } else {
            // Platform could not parse the APK – fall back to release metadata when it has a versionCode.
            val code = info.versionCode
            if (code != null && code <= installedVersionCode)
                return Preflight.Blocked(InstallError.NOT_NEWER, "Release build $code is not newer than the installed build $installedVersionCode.", discardFile = true)
        }
        if (!canInstallUnknownApps)
            return Preflight.Blocked(InstallError.PERMISSION_REQUIRED, "Allow AGI Assistant to install updates in Android settings, then tap INSTALL again.")
        return Preflight.Ok
    }

    // ---- installer result -----------------------------------------------------------

    /** Mirrors `Activity.RESULT_*` so this file does not depend on android.jar. */
    const val RESULT_OK = -1
    const val RESULT_CANCELED = 0
    const val RESULT_FIRST_USER = 1

    sealed class Outcome {
        /** Installer returned OK. The process is normally killed during the update, so this is rare; the app still re-verifies via PackageManager. */
        object ReportedSuccess : Outcome()
        object Cancelled : Outcome()
        data class Failed(val reason: InstallError, val message: String) : Outcome()
    }

    /**
     * Interprets `onActivityResult` from `ACTION_INSTALL_PACKAGE` with `EXTRA_RETURN_RESULT`.
     * [installResultCode] is `EXTRA_INSTALL_RESULT` (a `PackageManager.INSTALL_FAILED_*` constant) when present.
     */
    fun classifyActivityResult(resultCode: Int, installResultCode: Int?): Outcome = when (resultCode) {
        RESULT_OK -> Outcome.ReportedSuccess
        RESULT_CANCELED -> Outcome.Cancelled
        else -> classifyInstallCode(installResultCode)
    }

    /** Maps `PackageManager.INSTALL_FAILED_*` (hidden but stable) codes to user-facing errors. */
    fun classifyInstallCode(code: Int?): Outcome.Failed {
        val (reason, message) = when (code) {
            null, 0 -> InstallError.INSTALL_FAILED to "Android reported \"App not installed\". Please try again; if it keeps failing, download the update again."
            -1, -5 -> InstallError.PACKAGE_CONFLICT to "A package with this name already exists in a conflicting state. Try again after restarting your phone."
            -2, -100, -101, -102, -105, -106, -107, -108, -109, -110 -> InstallError.INVALID_APK to "The update file is corrupted or not a valid package. Please download it again."
            -4 -> InstallError.INSUFFICIENT_STORAGE to "Not enough storage to install the update. Free up some space and try again."
            -7, -103, -104 -> InstallError.SIGNATURE_MISMATCH to "This update is signed with a different key than the installed app, so Android refuses to update in place. Uninstall AGI Assistant (this deletes its settings) or ask the developer for a build signed with the original key."
            -8, -9, -10, -13 -> InstallError.PACKAGE_CONFLICT to "The update conflicts with an app already installed on this phone (shared permission or provider)."
            -12, -16, -113, -114 -> InstallError.INCOMPATIBLE to "This update is not compatible with this phone (Android version or CPU architecture)."
            -25 -> InstallError.NOT_NEWER to "The update is older than the installed version, so Android rejected it."
            -111 -> InstallError.INSTALL_FAILED to "Android's installer hit an internal error. Restart your phone and try again."
            -115, -116 -> InstallError.USER_CANCELLED to "The installation was cancelled."
            else -> InstallError.INSTALL_FAILED to "Installation failed (Android error code $code)."
        }
        return Outcome.Failed(reason, message)
    }

    /**
     * Whether the release described by [info] is already installed (i.e. the installer finished and the
     * app restarted as the new version). Uses versionCode when the release publishes one, else the version name.
     */
    fun isInstalled(info: UpdateInfo, installedVersionName: String, installedVersionCode: Long): Boolean {
        info.versionCode?.let { return installedVersionCode >= it }
        val rel = info.version ?: return info.versionName == installedVersionName
        val cur = SemanticVersion.parse(installedVersionName) ?: return false
        return cur >= rel
    }
}
