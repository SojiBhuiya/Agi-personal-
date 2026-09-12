package com.agi.assistant.ui

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.agi.assistant.R
import com.agi.assistant.core.update.UpdateInfo
import com.agi.assistant.core.update.UpdateMessages
import com.agi.assistant.core.update.UpdatePromptPolicy
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * The "New Update Available" dialog. One instance per Activity; [show] is a
 * no-op while a dialog is already visible, so duplicate prompts are impossible
 * even if several state updates arrive.
 *
 * Phase 2: UPDATE opens the GitHub release page. Phase 3 will replace that
 * with in-app download + install.
 */
class UpdateDialog(private val activity: Activity, private val policy: UpdatePromptPolicy) {
    private var dialog: Dialog? = null

    val isShowing: Boolean get() = dialog?.isShowing == true

    fun show(info: UpdateInfo, installedVersion: String, onUpdate: (UpdateInfo) -> Unit = { openRelease(it) }) {
        if (isShowing || activity.isFinishing || activity.isDestroyed) return
        val v = LayoutInflater.from(activity).inflate(R.layout.dialog_update, null)
        v.findViewById<TextView>(R.id.updCurrent).text = "Current version: $installedVersion"
        v.findViewById<TextView>(R.id.updNew).text = "New version: ${info.versionName}"
        v.findViewById<TextView>(R.id.updMeta).text = listOfNotNull(
            info.releaseName.takeIf { it.isNotBlank() && it != info.releaseTag },
            formatDate(info.publishedAt),
            info.apkSizeBytes.takeIf { it > 0 }?.let { "%.1f MB".format(it / 1024.0 / 1024.0) },
        ).joinToString(" • ")
        v.findViewById<TextView>(R.id.updNotes).text =
            UpdateMessages.whatsNew(info.releaseNotes).ifBlank { "No release notes were provided." }
        v.findViewById<View>(R.id.updMandatory).visibility = if (info.isMandatory) View.VISIBLE else View.GONE

        val d = Dialog(activity, android.R.style.Theme_Material_Dialog_NoActionBar)
        d.setContentView(v)
        d.window?.setBackgroundDrawableResource(R.drawable.bg_card)
        d.setCancelable(!info.isMandatory)
        d.setCanceledOnTouchOutside(false)
        d.setOnDismissListener { if (dialog === d) dialog = null }

        val later = v.findViewById<Button>(R.id.updLater)
        later.visibility = if (info.isMandatory) View.GONE else View.VISIBLE
        later.setOnClickListener {
            policy.postpone(info)
            d.dismiss()
            Toast.makeText(activity, "We’ll remind you later.", Toast.LENGTH_SHORT).show()
        }
        v.findViewById<Button>(R.id.updUpdate).setOnClickListener { onUpdate(info) }
        d.setOnCancelListener { policy.postpone(info) }

        policy.markShown(info)
        dialog = d
        d.show()
    }

    fun dismiss() { dialog?.dismiss(); dialog = null }

    private fun openRelease(info: UpdateInfo) {
        val url = info.htmlUrl.takeIf { it.startsWith("https://") } ?: info.apkDownloadUrl
        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { Toast.makeText(activity, "No browser available to open the release.", Toast.LENGTH_SHORT).show() }
    }

    private fun formatDate(iso: String): String? = runCatching {
        val p = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(p.parse(iso)!!)
    }.getOrNull()
}
