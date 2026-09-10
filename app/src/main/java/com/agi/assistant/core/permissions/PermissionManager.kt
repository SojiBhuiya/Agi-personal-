package com.agi.assistant.core.permissions

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import com.agi.assistant.services.AssistantAccessibilityService
import com.agi.assistant.services.NotificationListener

/** One capability the app can ask for, with the user-facing explanation. */
data class Capability(
    val id: String,
    val title: String,
    val why: String,
    val runtimePermissions: List<String> = emptyList(),
    val special: Special? = null,
) {
    enum class Special { ACCESSIBILITY, NOTIFICATION_LISTENER, ALL_FILES, WRITE_SETTINGS, DND_ACCESS }
}

/**
 * Knows every permission the assistant may need, can report which are
 * granted, and builds the right Intent/request for each. All requests go
 * through the standard Android permission UI - nothing is bypassed.
 */
class PermissionManager(private val context: Context) {

    val capabilities: List<Capability> = listOf(
        Capability("mic", "Microphone", "Voice commands (speech recognition).", listOf(Manifest.permission.RECORD_AUDIO)),
        Capability("contacts", "Contacts", "Resolve names like \"Rahim\" to a phone number.", listOf(Manifest.permission.READ_CONTACTS)),
        Capability("phone", "Phone", "Place calls directly when you say \"call …\".", listOf(Manifest.permission.CALL_PHONE)),
        Capability("sms", "SMS", "Send text messages you dictate.", listOf(Manifest.permission.SEND_SMS)),
        Capability("camera", "Camera", "Flashlight control uses the camera flash.", listOf(Manifest.permission.CAMERA)),
        Capability("notifications_post", "Show notifications", "Foreground service status while the assistant is listening.",
            if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()),
        Capability("media", "Photos, videos & audio", "Find your files (\"find my downloaded PDF\").",
            if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
            else listOf(Manifest.permission.READ_EXTERNAL_STORAGE)),
        Capability("accessibility", "Accessibility service", "Screen control: go back, scroll, tap buttons, type text, read the screen, take screenshots. Android shows a full-screen explanation before enabling.", special = Capability.Special.ACCESSIBILITY),
        Capability("all_files", "All files access (optional)", "Find documents such as PDFs that other apps downloaded. Without it I can still open the Downloads folder for you.", special = Capability.Special.ALL_FILES),
        Capability("notif_listener", "Notification access", "Read your notifications aloud when you ask.", special = Capability.Special.NOTIFICATION_LISTENER),
        Capability("write_settings", "Modify system settings", "Change screen brightness.", special = Capability.Special.WRITE_SETTINGS),
        Capability("dnd", "Do Not Disturb access", "Mute/unmute the ringer when DND is active.", special = Capability.Special.DND_ACCESS),
    )

    fun isGranted(cap: Capability): Boolean = when (cap.special) {
        Capability.Special.ACCESSIBILITY -> isAccessibilityEnabled()
        Capability.Special.NOTIFICATION_LISTENER -> isNotificationListenerEnabled()
        Capability.Special.ALL_FILES -> Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()
        Capability.Special.WRITE_SETTINGS -> Settings.System.canWrite(context)
        Capability.Special.DND_ACCESS -> (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).isNotificationPolicyAccessGranted
        null -> cap.runtimePermissions.all { has(it) }
    }

    fun has(permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun missingRuntime(vararg permissions: String): List<String> = permissions.filterNot { has(it) }

    fun isAccessibilityEnabled(): Boolean {
        val expected = ComponentName(context, AssistantAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) || it.equals(ComponentName(context, AssistantAccessibilityService::class.java).flattenToShortString(), true) }
    }

    fun isNotificationListenerEnabled(): Boolean {
        val expected = ComponentName(context, NotificationListener::class.java)
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
        return flat.split(':').any { ComponentName.unflattenFromString(it) == expected }
    }

    /** Intent that opens the system screen where the user grants a special capability. */
    fun settingsIntent(special: Capability.Special): Intent = when (special) {
        Capability.Special.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        Capability.Special.NOTIFICATION_LISTENER -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        Capability.Special.ALL_FILES -> if (Build.VERSION.SDK_INT >= 30) Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")) else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        Capability.Special.WRITE_SETTINGS -> Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))
        Capability.Special.DND_ACCESS -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun appDetailsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
