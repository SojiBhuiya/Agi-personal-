package com.agi.assistant.core.tools

import android.content.Context
import com.agi.assistant.core.tools.impl.*

/**
 * Central registry of all tools. The AI layer only ever sees [specs]; the
 * agent loop resolves a [ToolCall] back to an implementation via [get].
 */
class ToolRegistry private constructor(private val tools: Map<String, Tool>) {

    val all: Collection<Tool> get() = tools.values
    val specs: List<ToolSpec> get() = tools.values.map { it.spec }

    fun get(name: String): Tool? = tools[name] ?: tools[name.lowercase()]

    companion object {
        /** Builds the default registry with every phone-control tool. */
        fun default(context: Context): ToolRegistry {
            val list: List<Tool> = listOf(
                // Apps & navigation
                OpenAppTool(),
                OpenUrlTool(),
                WebSearchTool(),
                OpenSettingsTool(),
                // Communication
                CallContactTool(),
                SendSmsTool(),
                SendWhatsAppTool(),
                FindContactTool(),
                // Device
                VolumeTool(),
                FlashlightTool(),
                BrightnessTool(),
                DeviceInfoTool(),
                AlarmTimerTool(),
                // Information (direct data sources – never the browser)
                WeatherTool(),
                // Files
                FindFilesTool(),
                // Notifications
                ReadNotificationsTool(),
                // Accessibility (screen control)
                GlobalActionTool(),
                ScrollTool(),
                TapTextTool(),
                TypeTextTool(),
                ReadScreenTool(),
                ScreenshotTool(),
                // Assistant meta tools
                SpeakTool(),
                WaitTool(),
            )
            return ToolRegistry(list.associateBy { it.spec.name })
        }
    }
}
