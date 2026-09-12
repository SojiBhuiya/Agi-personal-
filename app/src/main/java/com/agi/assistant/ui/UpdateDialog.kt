package com.agi.assistant.ui

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.agi.assistant.R
import com.agi.assistant.core.update.*
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * The update dialog. It renders every phase of the pipeline from the
 * [UpdateManager] state: available → downloading (progress + cancel) →
 * failed (retry) → ready to install. One instance per Activity; [show] is a
 * no-op while already visible, so duplicate prompts are impossible.
 *
 * Phase 3: INSTALL is shown once the APK is downloaded and verified; the
 * actual hand-off to the system installer is Phase 4, so the button explains that.
 */
class UpdateDialog(
    private val activity: Activity,
    private val policy: UpdatePromptPolicy,
    private val manager: UpdateManager,
) {
    private var dialog: Dialog? = null
    private var info: UpdateInfo? = null
    private var installedVersion = ""
    private val observer = UpdateManager.Observer { render(it) }

    val isShowing: Boolean get() = dialog?.isShowing == true

    fun show(info: UpdateInfo, installedVersion: String) {
        if (isShowing || activity.isFinishing || activity.isDestroyed) return
        this.info = info
        this.installedVersion = installedVersion
        val v = LayoutInflater.from(activity).inflate(R.layout.dialog_update, null)
        v.findViewById<TextView>(R.id.updCurrent).text = "Current version: $installedVersion"
        v.findViewById<TextView>(R.id.updNew).text = "New version: ${info.versionName}"
        v.findViewById<TextView>(R.id.updMeta).text = listOfNotNull(
            info.releaseName.takeIf { it.isNotBlank() && it != info.releaseTag },
            formatDate(info.publishedAt),
            info.apkSizeBytes.takeIf { it > 0 }?.let { UpdateMessages.mb(it) },
        ).joinToString(" • ")
        v.findViewById<TextView>(R.id.updNotes).text =
            UpdateMessages.whatsNew(info.releaseNotes).ifBlank { "No release notes were provided." }
        v.findViewById<View>(R.id.updMandatory).visibility = if (info.isMandatory) View.VISIBLE else View.GONE

        val d = Dialog(activity, android.R.style.Theme_Material_Dialog_NoActionBar)
        d.setContentView(v)
        d.window?.setBackgroundDrawableResource(R.drawable.bg_card)
        d.setCancelable(!info.isMandatory)
        d.setCanceledOnTouchOutside(false)
        d.setOnCancelListener { if (manager.state !is UpdateState.Downloading && manager.state !is UpdateState.ReadyToInstall) policy.postpone(info) }
        d.setOnDismissListener { manager.removeObserver(observer); if (dialog === d) dialog = null }

        policy.markShown(info)
        dialog = d
        d.show()
        manager.addObserver(observer) // delivers current state immediately -> render()
    }

    fun dismiss() { dialog?.dismiss(); dialog = null }

    // ---- rendering ---------------------------------------------------------------

    private fun render(state: UpdateState) {
        val d = dialog ?: return
        val i = info ?: return
        val v = d.findViewById<View>(R.id.updPrimary).rootView
        val box = v.findViewById<View>(R.id.updProgressBox)
        val status = v.findViewById<TextView>(R.id.updStatus)
        val percent = v.findViewById<TextView>(R.id.updPercent)
        val bar = v.findViewById<ProgressBar>(R.id.updProgress)
        val detail = v.findViewById<TextView>(R.id.updDetail)
        val primary = v.findViewById<Button>(R.id.updPrimary)
        val secondary = v.findViewById<Button>(R.id.updSecondary)
        val notes = v.findViewById<View>(R.id.updNotes)
        val notesTitle = v.findViewById<View>(R.id.updNotesTitle)

        fun showNotes(show: Boolean) { notes.visibility = if (show) View.VISIBLE else View.GONE; notesTitle.visibility = notes.visibility }
        val relevant = when (state) {
            is UpdateState.Downloading -> state.info.releaseTag == i.releaseTag
            is UpdateState.ReadyToInstall -> state.info.releaseTag == i.releaseTag
            is UpdateState.DownloadFailed -> state.info.releaseTag == i.releaseTag
            else -> false
        }

        when {
            state is UpdateState.Downloading && relevant -> {
                showNotes(false); box.visibility = View.VISIBLE
                status.text = UpdateMessages.DOWNLOADING
                val p = state.percent
                bar.isIndeterminate = p < 0
                if (p >= 0) bar.progress = p
                percent.text = if (p >= 0) "$p%" else ""
                detail.text = UpdateMessages.progressDetail(state.bytesDownloaded, state.totalBytes)
                primary.text = "UPDATING…"; primary.isEnabled = false; primary.setOnClickListener(null)
                secondary.text = "CANCEL"; secondary.visibility = View.VISIBLE
                secondary.setOnClickListener { manager.cancelDownload() }
                d.setCancelable(false)
            }
            state is UpdateState.ReadyToInstall && relevant -> {
                showNotes(false); box.visibility = View.VISIBLE
                status.text = UpdateMessages.DOWNLOADED
                bar.isIndeterminate = false; bar.progress = 100; percent.text = "100%"
                detail.text = (if (state.verified) "Checksum verified • " else "") + "${UpdateMessages.mb(state.file.length())} saved in app storage"
                primary.text = "INSTALL"; primary.isEnabled = true
                primary.setOnClickListener { onInstall(state) }
                secondary.text = if (i.isMandatory) "" else "LATER"
                secondary.visibility = if (i.isMandatory) View.GONE else View.VISIBLE
                secondary.setOnClickListener { d.dismiss() }
                d.setCancelable(!i.isMandatory)
            }
            state is UpdateState.DownloadFailed && relevant -> {
                showNotes(false); box.visibility = View.VISIBLE
                status.text = UpdateMessages.DOWNLOAD_FAILED
                bar.isIndeterminate = false; bar.progress = 0; percent.text = ""
                detail.text = state.message
                primary.text = "RETRY"; primary.isEnabled = true
                primary.setOnClickListener { manager.retryDownload() }
                secondary.text = "CANCEL"; secondary.visibility = View.VISIBLE
                secondary.setOnClickListener { manager.discardDownload(); d.dismiss() }
                d.setCancelable(!i.isMandatory)
            }
            else -> { // UpdateAvailable (or unrelated state): initial prompt
                showNotes(true); box.visibility = View.GONE
                primary.text = "UPDATE"; primary.isEnabled = true
                primary.setOnClickListener { manager.startDownload(i) ?: openRelease(i) }
                secondary.text = "LATER"
                secondary.visibility = if (i.isMandatory) View.GONE else View.VISIBLE
                secondary.setOnClickListener {
                    policy.postpone(i); d.dismiss()
                    Toast.makeText(activity, "We’ll remind you later.", Toast.LENGTH_SHORT).show()
                }
                d.setCancelable(!i.isMandatory)
            }
        }
    }

    /** Phase 4 wires this to the system package installer. */
    private fun onInstall(state: UpdateState.ReadyToInstall) {
        Toast.makeText(activity, "The update is downloaded and verified. Installing from inside the app arrives in the next version.", Toast.LENGTH_LONG).show()
    }

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
