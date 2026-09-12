package com.agi.assistant.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import com.agi.assistant.AssistantApp
import com.agi.assistant.core.update.InstallError
import com.agi.assistant.core.update.InstallPolicy
import com.agi.assistant.core.update.UpdateManager
import com.agi.assistant.core.update.UpdateState
import com.agi.assistant.services.AssistantFileProvider
import java.io.File

/**
 * Hands a staged update APK to the Android **system package installer**.
 *
 *  - The file is shared as a `content://` URI from [AssistantFileProvider] with a temporary
 *    `FLAG_GRANT_READ_URI_PERMISSION` – never `file://` (which crashes on API 24+).
 *  - `ACTION_INSTALL_PACKAGE` + `application/vnd.android.package-archive`; `EXTRA_NOT_UNKNOWN_SOURCE`
 *    and `EXTRA_RETURN_RESULT` so the installer reports failures back to [onActivityResult].
 *  - minSdk is 26, so the "Install unknown apps" per-app permission model applies everywhere:
 *    `REQUEST_INSTALL_PACKAGES` is declared and [PackageManager.canRequestPackageInstalls] is checked;
 *    when missing we open `ACTION_MANAGE_UNKNOWN_APP_SOURCES` for `package:com.agi.assistant` and the
 *    user taps INSTALL again after returning.
 *  - The user always confirms in the system UI. We never uninstall first and never claim success:
 *    the state goes to [UpdateState.InstallerLaunched] and only the OS-reported outcome (or the app
 *    restarting as the new version) moves it on.
 */
class ApkInstaller(private val activity: Activity, private val manager: UpdateManager) {
    private val app get() = activity.application as AssistantApp

    /** Entry point for the INSTALL button. Safe to call repeatedly. */
    fun install(state: UpdateState.ReadyToInstall) {
        val installed = app.installedVersion()
        val facts = inspect(state.file)
        val pre = InstallPolicy.preflight(
            state.file, state.info, facts, activity.packageName, installed.versionCode, canInstallUnknownApps(),
        )
        when (pre) {
            is InstallPolicy.Preflight.Blocked -> {
                manager.markInstallFailed(pre.reason, pre.message, discardFile = pre.discardFile)
                if (pre.reason == InstallError.PERMISSION_REQUIRED) openUnknownSourcesSettings()
            }
            InstallPolicy.Preflight.Ok -> launchInstaller(state.file, state)
        }
    }

    /** Re-check after the user returns from the settings screen or the installer. */
    fun onActivityResumed() {
        when (val s = manager.state) {
            is UpdateState.InstallerLaunched -> {
                // Still the old version running → the install did not complete (cancelled or failed
                // without a result). Give the user INSTALL again; the file is re-verified first.
                if (awaitingResult) return // onActivityResult will handle it
                manager.installerReturned()
            }
            is UpdateState.InstallationError ->
                if (s.reason == InstallError.PERMISSION_REQUIRED && canInstallUnknownApps()) manager.retryInstall()
            else -> {}
        }
    }

    /** Wire from `Activity.onActivityResult`. Returns true when the result was ours. */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        when (requestCode) {
            REQ_INSTALL -> {
                awaitingResult = false
                val code = data?.getIntExtra("android.intent.extra.INSTALL_RESULT", Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }
                when (val out = InstallPolicy.classifyActivityResult(resultCode, code)) {
                    InstallPolicy.Outcome.ReportedSuccess -> {
                        // Normally the process is replaced before we get here. If we are still the old
                        // version, do NOT claim success – re-verify the file and stay honest.
                        val staged = (manager.state as? UpdateState.InstallerLaunched)
                        if (staged != null && !InstallPolicy.isInstalled(staged.info, app.installedVersion().versionName, app.installedVersion().versionCode)) {
                            manager.installerReturned()
                            Toast.makeText(activity, "Android did not finish installing the update. You can try again.", Toast.LENGTH_LONG).show()
                        }
                    }
                    InstallPolicy.Outcome.Cancelled -> manager.installerReturned()
                    is InstallPolicy.Outcome.Failed -> manager.markInstallFailed(out.reason, out.message, discardFile = out.reason == InstallError.INVALID_APK)
                }
                return true
            }
            REQ_UNKNOWN_SOURCES -> { onActivityResumed(); return true }
        }
        return false
    }

    // ---- internals -------------------------------------------------------------------

    private var awaitingResult = false

    fun canInstallUnknownApps(): Boolean =
        if (Build.VERSION.SDK_INT >= 26) runCatching { activity.packageManager.canRequestPackageInstalls() }.getOrDefault(false) else true

    /** Opens "Install unknown apps" for this app; falls back to the app's details page. Never throws. */
    fun openUnknownSourcesSettings() {
        val pkg = Uri.parse("package:${activity.packageName}")
        val intents = listOf(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, pkg),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg),
            Intent(Settings.ACTION_SECURITY_SETTINGS),
        )
        for (i in intents) {
            try { activity.startActivityForResult(i, REQ_UNKNOWN_SOURCES); return } catch (_: ActivityNotFoundException) {} catch (_: SecurityException) {}
        }
        Toast.makeText(activity, "Open Android Settings › Apps › AGI Assistant › Install unknown apps, then tap INSTALL again.", Toast.LENGTH_LONG).show()
    }

    private fun launchInstaller(file: File, state: UpdateState.ReadyToInstall) {
        val uri = try { AssistantFileProvider.updateApkUri(activity, file) } catch (e: IllegalArgumentException) {
            manager.markInstallFailed(InstallError.FILE_MISSING, "The update file is not in the app's update folder.", discardFile = true); return
        }
        @Suppress("DEPRECATION")
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, AssistantFileProvider.APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
            putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, activity.packageName)
        }
        val viewFallback = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, AssistantFileProvider.APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // Remember what we launched so a restart as the new version can clean the staged APK.
        app.settings.stagedUpdate = "${state.info.versionName}|${state.info.versionCode ?: inspect(file)?.versionCode ?: 0}"
        try {
            activity.startActivityForResult(intent, REQ_INSTALL)
            awaitingResult = true
            manager.markInstallerLaunched(file)
        } catch (e: ActivityNotFoundException) {
            try {
                activity.startActivity(viewFallback)
                awaitingResult = false
                manager.markInstallerLaunched(file)
            } catch (e2: Exception) {
                manager.markInstallFailed(InstallError.NO_INSTALLER, "No package installer is available on this device.")
            }
        } catch (e: SecurityException) {
            manager.markInstallFailed(InstallError.PERMISSION_REQUIRED, "Android blocked the installer: allow installing updates for AGI Assistant, then tap INSTALL again.")
            openUnknownSourcesSettings()
        } catch (e: Exception) {
            manager.markInstallFailed(InstallError.UNKNOWN, "Could not open the installer: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Parses the APK with PackageManager so package name / versionCode are checked before launching. */
    private fun inspect(file: File): InstallPolicy.ApkFacts? = runCatching {
        val pm = activity.packageManager
        @Suppress("DEPRECATION")
        val pi = if (Build.VERSION.SDK_INT >= 33) pm.getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(0))
        else pm.getPackageArchiveInfo(file.path, 0)
        if (pi == null) InstallPolicy.ApkFacts(null, null, null, parsed = false)
        else {
            @Suppress("DEPRECATION")
            val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
            InstallPolicy.ApkFacts(pi.packageName, code, pi.versionName, parsed = true)
        }
    }.getOrNull()

    companion object {
        const val REQ_INSTALL = 0x1A57
        const val REQ_UNKNOWN_SOURCES = 0x1A58
    }
}
