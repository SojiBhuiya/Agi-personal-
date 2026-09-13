package com.agi.assistant.core.tools.impl

import com.agi.assistant.core.tools.*
import com.agi.assistant.services.NotificationListener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReadNotificationsTool : Tool {
    override val category = "Notifications"
    override val spec = ToolSpec(
        "read_notifications",
        "Read the user's current notifications (app, title, text).",
        listOf(ToolParam("limit", ParamType.INTEGER, "Max notifications (default 10)", required = false)),
        intent = ToolIntent.INFORMATION, rawOutput = true,
    )

    override suspend fun execute(args: Map<String, Any?>, ctx: ToolContext): ToolResult {
        val listener = NotificationListener.instance
            ?: return ToolResult.permission(PermissionNeed(PermissionNeed.Kind.NOTIFICATION_LISTENER, "Notification access so I can read your notifications"))
        val items = listener.current(args.int("limit", 10).coerceIn(1, 30))
        if (items.isEmpty()) return ToolResult.ok("You have no notifications right now.", "No notifications")
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val text = items.joinToString("\n") { "${fmt.format(Date(it.time))} ${it.app}: ${it.title}${if (it.text.isNotBlank()) " — ${it.text.take(160)}" else ""}" }
        return ToolResult.ok("You have ${items.size} notification(s):\n$text", "You have ${items.size} notifications")
    }
}
