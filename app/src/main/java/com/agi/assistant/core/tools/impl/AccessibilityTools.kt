package com.agi.assistant.core.tools.impl

import android.accessibilityservice.AccessibilityService
import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.agi.assistant.core.tools.*
import com.agi.assistant.services.AssistantAccessibilityService
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun a11y(): AssistantAccessibilityService? = AssistantAccessibilityService.instance
private val needA11y = PermissionNeed(PermissionNeed.Kind.ACCESSIBILITY, "Enable the assistant's Accessibility service to control the screen")

class GlobalActionTool : Tool {
    override val category = "Screen control"
    override val spec = ToolSpec(
        "global_action",
        "Perform a system navigation action: back, home, recents, notifications, quick_settings, lock, power_menu.",
        listOf(ToolParam("action", ParamType.STRING, "Action", enumValues = listOf("back", "home", "recents", "notifications", "quick_settings", "lock", "power_menu"))),
    )

    override suspend fun execute(args: Map<String, Any?>, ctx: ToolContext): ToolResult {
        val svc = a11y() ?: return ToolResult.permission(needA11y)
        val action = args.str("action", "back")
        val code = when (action) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "lock" -> if (Build.VERSION.SDK_INT >= 28) AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN else return ToolResult.fail("Lock needs Android 9+.")
            "power_menu" -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
            else -> return ToolResult.fail("Unknown action $action")
        }
        val ok = svc.globalAction(code)
        return if (ok) ToolResult.ok("Performed '$action'.", action.replace('_', ' '), leftApp = true) else ToolResult.fail("System refused '$action'.")
    }
}

class ScrollTool : Tool {
    override val category = "Screen control"
    override val spec = ToolSpec(
        "scroll",
        "Scroll the current screen.",
        listOf(ToolParam("direction", ParamType.STRING, "down, up, left, right", enumValues = listOf("down", "up", "left", "right")), ToolParam("times", ParamType.INTEGER, "How many times (default 1)", required = false)),
    )

    override suspend fun execute(args: Map<String, Any?>, ctx: ToolContext): ToolResult {
        val svc = a11y() ?: return ToolResult.permission(needA11y)
        val dir = args.str("direction", "down")
        var done = 0
        repeat(args.int("times", 1).coerceIn(1, 10)) { if (svc.scroll(dir)) done++; delay(400) }
        return if (done > 0) ToolResult.ok("Scrolled $dir${if (done > 1) " $done times" else ""}.") else ToolResult.fail("Nothing scrollable on this screen.")
    }
}

class TapTextTool : Tool {
    override val category = "Screen control"
    override val spec = ToolSpec(
        "tap_text",
        "Tap a button/link/element on the current screen identified by its visible text or description. Use read_screen first if unsure what is on screen.",
        listOf(ToolParam("text", ParamType.STRING, "Visible text (partial match, case-insensitive)")),
    )

    override suspend fun execute(args: Map<String, Any?>, ctx: ToolContext): ToolResult {
        val svc = a11y() ?: return ToolResult.permission(needA11y)
        val text = args.str("text").trim()
        if (text.isEmpty()) return ToolResult.fail("Which element should I tap?")
        val hit = svc.clickByText(text)
        return if (hit != null) ToolResult.ok("Tapped \"$hit\".", "Tapped $hit")
        else ToolResult.fail("Couldn't find anything labelled \"$text\" on screen. Visible items: ${svc.snapshot(25).joinToString { it.text }.take(400)}")
    }
}

class TypeTextTool : Tool {
    override val category = "Screen control"
    override val spec = ToolSpec(
        "type_text",
        "Type text into the currently focused input field (or the first editable field on screen).",
        listOf(ToolParam("text", ParamType.STRING, "Text to type"), ToolParam("append", ParamType.BOOLEAN, "Append to existing text instead of replacing (default false)", required = false)),
    )

    override suspend fun execute(args: Map<String, Any?>, ctx: ToolContext): ToolResult {
        val svc = a11y() ?: return ToolResult.permission(needA11y)
        val text = args.str("text")
        return if (svc.typeText(text, args.bool("append"))) ToolResult.ok("Typed \"$text\".", "Typed it")
        else ToolResult.fail("No editable text field is focused. Tap an input field first (tap_text) and try again.")
    }
}

class ReadScreenTool : Tool {
    override val category = "Screen control"
    override val spec = ToolSpec(
        "read_screen",
        "Read the text currently visible on the screen (from any app). Use it to check results after opening a page or to find button labels.",
        listOf(ToolParam("purpose", ParamType.STRING, "Why you are reading (ignored, for your own reasoning)", required = false)),
    )

    override suspend fun execute(args: Map<String, Any?>, ctx: ToolContext): ToolResult {
        val svc = a11y() ?: return ToolResult.permission(needA11y)
        // Give a freshly opened app/page a moment to render.
        delay(1800)
        var text = svc.screenText()
        if (text.isBlank()) { delay(1500); text = svc.screenText() }
        val pkg = AssistantAccessibilityService.currentPackage
        return if (text.isBlank()) ToolResult.fail("The screen has no readable text right now${pkg?.let { " (app: $it)" } ?: ""}.")
        else ToolResult.ok("Screen content${pkg?.let { " of $it" } ?: ""}:\n$text")
    }
}

class ScreenshotTool : Tool {
    override val category = "Screen control"
    override val spec = ToolSpec("screenshot", "Take a screenshot and save it to Pictures/Screenshots.")

    override suspend fun execute(args: Map<String, Any?>, ctx: ToolContext): ToolResult {
        val svc = a11y() ?: return ToolResult.permission(needA11y)
        if (Build.VERSION.SDK_INT >= 30) {
            val bmp: Bitmap? = svc.screenshot()
            if (bmp != null) {
                val name = "Assistant_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".png"
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Screenshots")
                }
                val cr = ctx.context.contentResolver
                val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return ToolResult.fail("Could not create the image file.")
                cr.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                return ToolResult.ok("Screenshot saved as Pictures/Screenshots/$name.", "Screenshot saved")
            }
        }
        // Older Android: ask the system to take one (API 28+).
        if (Build.VERSION.SDK_INT >= 28 && svc.globalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT))
            return ToolResult.ok("Screenshot taken by the system.", "Screenshot taken")
        return ToolResult.fail("Screenshots need Android 9 or newer.")
    }
}

class SpeakTool : Tool {
    override val category = "Assistant"
    override val spec = ToolSpec("speak", "Say something out loud to the user (text-to-speech).", listOf(ToolParam("text", ParamType.STRING, "What to say")))
    override suspend fun execute(args: Map<String, Any?>, ctx: ToolContext): ToolResult {
        val t = args.str("text")
        com.agi.assistant.voice.Speaker.speak(ctx.context, t)
        return ToolResult.ok(t, t)
    }
}

class WaitTool : Tool {
    override val category = "Assistant"
    override val spec = ToolSpec("wait", "Pause for a few seconds (e.g. to let a page load) before the next step.", listOf(ToolParam("seconds", ParamType.INTEGER, "1-15 seconds")))
    override suspend fun execute(args: Map<String, Any?>, ctx: ToolContext): ToolResult {
        val s = args.int("seconds", 2).coerceIn(1, 15)
        delay(s * 1000L)
        return ToolResult.ok("Waited $s seconds.")
    }
}
