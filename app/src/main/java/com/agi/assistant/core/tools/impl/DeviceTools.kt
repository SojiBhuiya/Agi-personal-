package com.agi.assistant.core.tools.impl

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.Settings
import com.agi.assistant.core.tools.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Android binding of the volume feature. All semantics live in [VolumeController] / [VolumeMath]
 * (JVM-tested); this class only adapts [AudioManager] and the DND permission rules.
 */
class VolumeTool : Tool {
    override val category = "Device"
    override val spec = ToolSpec(
        "volume",
        "Control volume. set = absolute percent (level 0-100); up/down = one percentage point, or 'delta' " +
            "percentage points; adjust = signed delta; mute, unmute, max, get (read current).",
        listOf(
            ToolParam("action", ParamType.STRING, "set, up, down, adjust, mute, unmute, max, get", enumValues = listOf("set", "up", "down", "adjust", "mute", "unmute", "max", "get")),
            ToolParam("level", ParamType.INTEGER, "Target percent 0-100 when action=set (absolute, not relative)", required = false),
            ToolParam("delta", ParamType.INTEGER, "Percentage points to move for up/down/adjust (default 1)", required = false),
            ToolParam("stream", ParamType.STRING, "media (default), ring, alarm, call, notification", required = false, enumValues = listOf("media", "ring", "alarm", "call", "notification")),
        ),
    )

    override suspend fun execute(args: Map<String, Any?>, ctx: ToolContext): ToolResult {
        val am = ctx.context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val nm = ctx.context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val cmd = VolumeController.fromToolArgs(args)
        val streamId = AndroidVolumeBackend.streamId(cmd.stream)
        // Ringer/notification changes while DND is on need policy access; ask instead of crashing.
        if ((streamId == AudioManager.STREAM_RING || streamId == AudioManager.STREAM_NOTIFICATION) &&
            cmd.action == VolumeCommand.Action.MUTE && !nm.isNotificationPolicyAccessGranted)
            return ToolResult.permission(PermissionNeed(PermissionNeed.Kind.DND_ACCESS, "Do Not Disturb access to mute the ringer"))
        return try {
            val out = VolumeController(AndroidVolumeBackend(am)).apply(cmd)
            ToolResult.ok(out.message, out.spoken)
        } catch (e: SecurityException) {
            ToolResult.permission(PermissionNeed(PermissionNeed.Kind.DND_ACCESS, "Do Not Disturb access is required to change this volume"))
        }
    }
}

/** [VolumeBackend] over the real [AudioManager]; the only place that touches stream indices on device. */
class AndroidVolumeBackend(private val am: AudioManager) : VolumeBackend {
    override fun maxIndex(stream: String) = am.getStreamMaxVolume(streamId(stream))
    override fun currentIndex(stream: String) = am.getStreamVolume(streamId(stream))
    override fun setIndex(stream: String, index: Int) {
        val id = streamId(stream)
        val target = index.coerceIn(0, am.getStreamMaxVolume(id))
        if (target > 0 && am.isStreamMute(id)) am.adjustStreamVolume(id, AudioManager.ADJUST_UNMUTE, 0)
        am.setStreamVolume(id, target, AudioManager.FLAG_SHOW_UI)
    }
    override fun setMuted(stream: String, muted: Boolean) =
        am.adjustStreamVolume(streamId(stream), if (muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
    override fun isMuted(stream: String) = am.isStreamMute(streamId(stream))

    companion object {
        fun streamId(stream: String) = when (stream) {
            "ring" -> AudioManager.STREAM_RING; "alarm" -> AudioManager.STREAM_ALARM; "call" -> AudioManager.STREAM_VOICE_CALL
            "notification" -> AudioManager.STREAM_NOTIFICATION; else -> AudioManager.STREAM_MUSIC
        }
    }
}

class FlashlightTool : Tool {
    override val category = "Device"
    override val spec = ToolSpec("flashlight", "Turn the flashlight (torch) on or off.", listOf(ToolParam("on", ParamType.BOOLEAN, "true = on, false = off")))

    override suspend fun execute(args: Map<String, Any?>, ctx: ToolContext): ToolResult {
        val cm = ctx.context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ?: return ToolResult.fail("This phone has no flashlight.")
        val on = args.bool("on", true)
        return try {
            cm.setTorchMode(id, on)
            ToolResult.ok("Flashlight ${if (on) "on" else "off"}.", "Flashlight ${if (on) "on" else "off"}")
        } catch (e: Exception) {
            ToolResult.fail("Could not switch the flashlight: ${e.message}")
        }
    }
}

class BrightnessTool : Tool {
    override val category = "Device"
    override val spec = ToolSpec(
        "brightness",
        "Change screen brightness: up, down or set to a percent.",
        listOf(ToolParam("action", ParamType.STRING, "up, down, set", enumValues = listOf("up", "down", "set")), ToolParam("level", ParamType.INTEGER, "Percent 0-100 for set", required = false)),
    )

    override suspend fun execute(args: Map<String, Any?>, ctx: ToolContext): ToolResult {
        if (!Settings.System.canWrite(ctx.context))
            return ToolResult.permission(PermissionNeed(PermissionNeed.Kind.WRITE_SETTINGS, "\"Modify system settings\" permission to change brightness"))
        val cr = ctx.context.contentResolver
        val cur = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
        val target = when (args.str("action", "up")) {
            "up" -> (cur + 51).coerceAtMost(255)
            "down" -> (cur - 51).coerceAtLeast(5)
            else -> (args.int("level", 50).coerceIn(0, 100) * 255 / 100).coerceAtLeast(1)
        }
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, target)
        return ToolResult.ok("Brightness set to ${target * 100 / 255}%.")
    }
}

class DeviceInfoTool : Tool {
    override val category = "Device"
    override val spec = ToolSpec("device_info", "Get current time, date, battery level and device model. Use this for any time/date/battery question.", intent = ToolIntent.INFORMATION)

    override suspend fun execute(args: Map<String, Any?>, ctx: ToolContext): ToolResult {
        val bm = ctx.context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        val now = Date()
        val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(now)
        val date = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(now)
        return ToolResult.ok("Time: $time. Date: $date. Battery: $pct%${if (charging) " (charging)" else ""}. Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}.",
            "It's $time, battery $pct percent")
    }
}

class AlarmTimerTool : Tool {
    override val category = "Device"
    override val spec = ToolSpec(
        "alarm_timer",
        "Set an alarm (hour/minute, 24h) or a countdown timer (seconds) in the Clock app.",
        listOf(
            ToolParam("type", ParamType.STRING, "alarm or timer", enumValues = listOf("alarm", "timer")),
            ToolParam("hour", ParamType.INTEGER, "0-23 for alarm", required = false),
            ToolParam("minute", ParamType.INTEGER, "0-59 for alarm", required = false),
            ToolParam("seconds", ParamType.INTEGER, "Duration for timer", required = false),
            ToolParam("label", ParamType.STRING, "Optional label", required = false),
        ),
    )

    override suspend fun execute(args: Map<String, Any?>, ctx: ToolContext): ToolResult {
        val intent = if (args.str("type", "alarm") == "timer") {
            Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, args.int("seconds", 60)).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        } else {
            val h = args["hour"]; val m = args["minute"]
            if (h == null) return ToolResult.fail("I need the alarm time (hour and minute).")
            Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, args.int("hour")).putExtra(AlarmClock.EXTRA_MINUTES, args.int("minute")).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
        args.str("label").takeIf { it.isNotBlank() }?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(ctx.context.packageManager) == null) return ToolResult.fail("No clock app supports this.")
        ctx.context.startActivity(intent)
        return if (args.str("type") == "timer") ToolResult.ok("Timer set for ${args.int("seconds", 60)} seconds.", "Timer started")
        else ToolResult.ok("Alarm set for %02d:%02d.".format(args.int("hour"), args.int("minute")), "Alarm set")
    }
}
