package com.agi.assistant

import android.app.Application
import com.agi.assistant.core.agent.AssistantAgent
import com.agi.assistant.core.agent.ConversationStore
import com.agi.assistant.core.permissions.PermissionManager
import com.agi.assistant.core.settings.SecureSettings
import com.agi.assistant.core.tools.ToolRegistry
import com.agi.assistant.voice.Speaker

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

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = SecureSettings(this)
        permissions = PermissionManager(this)
        tools = ToolRegistry.default(this)
        conversation = ConversationStore(this)
        agent = AssistantAgent(this, settings, tools, conversation)
        Speaker.enabled = settings.speakReplies
    }

    companion object {
        lateinit var instance: AssistantApp; private set
    }
}
