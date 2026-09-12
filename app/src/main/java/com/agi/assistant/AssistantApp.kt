package com.agi.assistant

import android.app.Application
import com.agi.assistant.core.agent.AssistantAgent
import com.agi.assistant.core.agent.ConversationStore
import com.agi.assistant.core.permissions.PermissionManager
import com.agi.assistant.core.settings.SecureSettings
import com.agi.assistant.core.tools.ToolRegistry
import com.agi.assistant.core.update.GitHubReleaseUpdateChecker
import com.agi.assistant.core.update.InstalledVersion
import com.agi.assistant.core.update.UpdateManager
import com.agi.assistant.core.update.UpdateRepository
import com.agi.assistant.util.mainScope
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

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = SecureSettings(this)
        permissions = PermissionManager(this)
        tools = ToolRegistry.default(this)
        conversation = ConversationStore(this)
        agent = AssistantAgent(this, settings, tools, conversation)
        Speaker.enabled = settings.speakReplies
        updateManager = UpdateManager(
            UpdateRepository(GitHubReleaseUpdateChecker(), ::installedVersion),
            mainScope(),
        )
    }

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
