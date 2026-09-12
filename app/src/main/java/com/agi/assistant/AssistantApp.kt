package com.agi.assistant

import android.app.Application
import com.agi.assistant.core.agent.AssistantAgent
import com.agi.assistant.core.agent.ConversationStore
import com.agi.assistant.core.permissions.PermissionManager
import com.agi.assistant.core.settings.SecureSettings
import com.agi.assistant.core.tools.ToolRegistry
import com.agi.assistant.core.update.ApkDownloader
import com.agi.assistant.core.update.AutoCheckPolicy
import com.agi.assistant.core.update.GitHubReleaseUpdateChecker
import com.agi.assistant.core.update.InstalledVersion
import com.agi.assistant.core.update.UpdateManager
import com.agi.assistant.core.update.UpdatePromptPolicy
import com.agi.assistant.core.update.UpdateRepository
import com.agi.assistant.util.MainDispatcher
import com.agi.assistant.util.mainScope
import java.io.File
import com.agi.assistant.voice.Speaker
import android.os.Build

/**
 * Application-scoped composition root. Dependencies are wired manually to keep
 * the app free of code-generation frameworks; swap this for Hilt later if the
 * project grows.
 */
class AssistantApp : Application() {
    lateinit var settings: SecureSettings; private set
    lateinit var permissions: PermissionManager; private set
    lateinit var tools: ToolRegistry; private set
    lateinit var conversation: ConversationStore; private set
    lateinit var agent: AssistantAgent; private set
    lateinit var updateManager: UpdateManager; private set
    lateinit var updatePolicy: UpdatePromptPolicy; private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = SecureSettings(this)
        permissions = PermissionManager(this)
        tools = ToolRegistry.default(this)
        conversation = ConversationStore(this)
        agent = AssistantAgent(this, settings, tools, conversation)
        Speaker.enabled = settings.speakReplies
        updatePolicy = UpdatePromptPolicy(settings)
        // APKs are staged in app-private storage; the package installer reads them through a
        // content:// URI from AssistantFileProvider with a temporary read grant (never file://).
        val downloadDir = com.agi.assistant.services.AssistantFileProvider.updatesRoot(this)
        val downloader = ApkDownloader(downloadDir)
        updateManager = UpdateManager(
            UpdateRepository(GitHubReleaseUpdateChecker(), ::installedVersion),
            mainScope(),
            downloader = downloader,
            notifyDispatcher = MainDispatcher,
            autoPolicy = AutoCheckPolicy(settings),
        )
        // Drop incomplete partials and week-old packages; a recent complete file is reused (after re-verification).
        downloader.cleanupStale()
        // If we restarted as the version whose installer was launched, the update succeeded: remove the APK.
        settings.stagedUpdate?.let { staged ->
            val v = installedVersion()
            val name = staged.substringBefore('|'); val code = staged.substringAfter('|', "").toLongOrNull()
            val installed = (code != null && code > 0 && v.versionCode >= code) || v.versionName == name
            if (installed) { downloader.cleanupAll(); settings.stagedUpdate = null }
        }
    }

    /** Best-effort connectivity signal; on any doubt we say "online" and let the request fail quietly. */
    fun isOnline(): Boolean = runCatching {
        val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(true)

    /**
     * Automatic update check for app start / return to foreground. Non-blocking (IO coroutine),
     * cooldown-limited by [AutoCheckPolicy], silent when offline or failing.
     */
    fun autoCheckForUpdates() { updateManager.checkAutomatically(isOnline()) }

    /** Installed version straight from PackageManager (never hard-coded). */
    fun installedVersion(): InstalledVersion {
        val info = packageManager.getPackageInfo(packageName, 0)
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        return InstalledVersion(info.versionName ?: "0.0.0", code)
    }

    companion object {
        lateinit var instance: AssistantApp; private set
    }
}
