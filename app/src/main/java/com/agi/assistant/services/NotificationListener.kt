package com.agi.assistant.services

import android.app.Notification
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Lets the assistant read the notifications the user asks about. Nothing is
 * stored; the active notifications are read on demand from the system.
 */
class NotificationListener : NotificationListenerService() {

    data class Item(val app: String, val title: String, val text: String, val time: Long)

    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    fun current(limit: Int = 15): List<Item> {
        val sbns: Array<StatusBarNotification> = runCatching { activeNotifications }.getOrNull() ?: return emptyList()
        val pm = packageManager
        return sbns
            .filter { !it.isOngoing || it.notification.category == Notification.CATEGORY_CALL }
            .sortedByDescending { it.postTime }
            .mapNotNull { sbn ->
                val extras = sbn.notification.extras
                val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
                val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
                if (title.isBlank() && text.isBlank()) return@mapNotNull null
                val app = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, PackageManager.GET_META_DATA)).toString() }.getOrDefault(sbn.packageName)
                Item(app, title, text, sbn.postTime)
            }
            .take(limit)
    }

    companion object {
        @Volatile var instance: NotificationListener? = null
            private set
    }
}
