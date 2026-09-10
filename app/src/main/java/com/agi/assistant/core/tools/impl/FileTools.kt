package com.agi.assistant.core.tools.impl

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.agi.assistant.core.tools.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FindFilesTool : Tool {
    override val category = "Files"
    override val spec = ToolSpec(
        "find_files",
        "Find files on the phone (PDFs, documents, images, videos, audio, downloads) and optionally open the newest match.",
        listOf(
            ToolParam("type", ParamType.STRING, "pdf, document, image, video, audio or any", required = false, enumValues = listOf("pdf", "document", "image", "video", "audio", "any")),
            ToolParam("query", ParamType.STRING, "Part of the file name", required = false),
            ToolParam("downloads_only", ParamType.BOOLEAN, "Only look in the Downloads folder", required = false),
            ToolParam("open", ParamType.BOOLEAN, "Open the newest match (default true when exactly one match)", required = false),
        ),
    )

    private data class Hit(val name: String, val uri: Uri, val mime: String?, val modified: Long, val size: Long)

    override suspend fun execute(args: Map<String, Any?>, ctx: ToolContext): ToolResult {
        val type = args.str("type", "any").lowercase(Locale.ROOT)
        val query = args.str("query").trim()
        val downloadsOnly = args.bool("downloads_only")
        val cr = ctx.context.contentResolver

        // Media (images/video/audio) need media permissions on API 33+, READ_EXTERNAL_STORAGE before.
        val mediaPerm = when {
            Build.VERSION.SDK_INT >= 33 -> when (type) { "image" -> Manifest.permission.READ_MEDIA_IMAGES; "video" -> Manifest.permission.READ_MEDIA_VIDEO; "audio" -> Manifest.permission.READ_MEDIA_AUDIO; else -> null }
            else -> Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (mediaPerm != null && ctx.context.checkSelfPermission(mediaPerm) != PackageManager.PERMISSION_GRANTED)
            return ToolResult.permission(PermissionNeed(PermissionNeed.Kind.RUNTIME, "Storage/media access to find your files", listOf(mediaPerm)))

        val hits = ArrayList<Hit>()
        val selection = StringBuilder("1=1")
        val selArgs = ArrayList<String>()
        when (type) {
            "pdf" -> { selection.append(" AND ${MediaStore.Files.FileColumns.MIME_TYPE}=?"); selArgs += "application/pdf" }
            "document" -> { selection.append(" AND (${MediaStore.Files.FileColumns.MIME_TYPE} LIKE 'application/%' OR ${MediaStore.Files.FileColumns.MIME_TYPE} LIKE 'text/%')") }
            "image" -> { selection.append(" AND ${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}") }
            "video" -> { selection.append(" AND ${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}") }
            "audio" -> { selection.append(" AND ${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO}") }
        }
        if (query.isNotEmpty()) { selection.append(" AND ${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"); selArgs += "%$query%" }
        if (downloadsOnly) { selection.append(" AND ${MediaStore.Files.FileColumns.DATA} LIKE ?"); selArgs += "%/Download/%" }

        val collection = if (Build.VERSION.SDK_INT >= 29 && (type == "pdf" || type == "document" || downloadsOnly)) MediaStore.Downloads.EXTERNAL_CONTENT_URI
        else MediaStore.Files.getContentUri("external")
        val proj = arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DISPLAY_NAME, MediaStore.Files.FileColumns.MIME_TYPE, MediaStore.Files.FileColumns.DATE_MODIFIED, MediaStore.Files.FileColumns.SIZE)
        runCatching {
            cr.query(collection, proj, selection.toString(), selArgs.toTypedArray(), "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC")?.use { c ->
                while (c.moveToNext() && hits.size < 10) {
                    val id = c.getLong(0)
                    hits += Hit(c.getString(1) ?: "file", ContentUris.withAppendedId(collection, id), c.getString(2), c.getLong(3) * 1000, c.getLong(4))
                }
            }
        }
        // Fallback for PDFs in Downloads on devices where MediaStore is not fully indexed.
        if (hits.isEmpty() && (type == "pdf" || type == "document" || downloadsOnly)) {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.listFiles()?.filter { f ->
                f.isFile && (type != "pdf" || f.extension.equals("pdf", true)) && (query.isEmpty() || f.name.contains(query, true))
            }?.sortedByDescending { it.lastModified() }?.take(10)?.forEach { f ->
                hits += Hit(f.name, Uri.fromFile(f), if (f.extension.equals("pdf", true)) "application/pdf" else null, f.lastModified(), f.length())
            }
        }
        if (hits.isEmpty()) {
            val isDoc = type == "pdf" || type == "document" || downloadsOnly
            val restricted = Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()
            if (isDoc && restricted) {
                // Scoped storage hides other apps' documents. Offer the system Downloads UI now and
                // explain the optional "All files access" capability for direct search.
                val dl = Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val opened = runCatching { ctx.context.startActivity(dl); true }.getOrDefault(false)
                return ToolResult(
                    success = opened,
                    output = (if (opened) "I opened your Downloads folder so you can pick the file. " else "") +
                        "To let me search downloaded ${type}s by name directly, grant \"All files access\" in Permissions.",
                    spoken = if (opened) "Here are your downloads" else null,
                    needsPermission = if (opened) null else PermissionNeed(PermissionNeed.Kind.ALL_FILES, "All files access to search your downloaded documents"),
                    leftApp = opened,
                )
            }
            return ToolResult.fail("No ${if (type == "any") "" else "$type "}files found${if (query.isNotEmpty()) " matching \"$query\"" else ""}${if (downloadsOnly) " in Downloads" else ""}.")
        }

        val fmt = SimpleDateFormat("d MMM HH:mm", Locale.getDefault())
        val listing = hits.joinToString("\n") { "${it.name} (${it.size / 1024} KB, ${fmt.format(Date(it.modified))})" }
        val shouldOpen = args["open"]?.let { args.bool("open") } ?: (hits.size == 1 || type == "pdf")
        if (shouldOpen) {
            val h = hits.first()
            val uri = if (h.uri.scheme == "file") androidx_fileprovider(ctx, File(h.uri.path!!)) else h.uri
            val open = Intent(Intent.ACTION_VIEW).setDataAndType(uri, h.mime ?: "*/*").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val opened = runCatching { ctx.context.startActivity(open); true }.getOrDefault(false)
            if (opened) return ToolResult.ok("Found ${hits.size} file(s):\n$listing\n\nOpened ${h.name}.", "Opening ${h.name}", leftApp = true)
        }
        return ToolResult.ok("Found ${hits.size} file(s):\n$listing", "Found ${hits.size} files")
    }

    private fun androidx_fileprovider(ctx: ToolContext, file: File): Uri =
        com.agi.assistant.services.AssistantFileProvider.uriFor(ctx.context, file)
}
