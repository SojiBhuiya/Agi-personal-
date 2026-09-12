package com.agi.assistant.services

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

/**
 * Minimal read-only content provider (same contract as AndroidX FileProvider, without the
 * dependency) so the assistant can hand files to other apps through `content://` URIs with a
 * temporary read grant – never a `file://` URI. It is *not exported*: another app can only open a
 * URI that this app explicitly granted with `FLAG_GRANT_READ_URI_PERMISSION`.
 *
 * Exposed roots (nothing else is reachable; paths are canonicalised to block `..`):
 *  - `/downloads/<rel>` → public Downloads folder (viewer hand-off for the file tools)
 *  - `/updates/<name>`  → `noBackupFilesDir/updates` (staged update APKs for the package installer)
 */
class AssistantFileProvider : ContentProvider() {
    override fun onCreate() = true

    private fun fileFor(uri: Uri): File {
        val path = uri.path ?: throw FileNotFoundException(uri.toString())
        val (root, rel) = when {
            path.startsWith("/downloads/") -> downloadsRoot() to path.removePrefix("/downloads/")
            path.startsWith("/updates/") -> updatesRoot(context!!) to path.removePrefix("/updates/")
            else -> throw FileNotFoundException(uri.toString())
        }
        val f = File(root, rel).canonicalFile
        if (!f.path.startsWith(root.path + File.separator) || !f.isFile) throw FileNotFoundException(uri.toString())
        return f
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read-only")
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor {
        val f = fileFor(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val c = MatrixCursor(cols)
        c.addRow(cols.map { when (it) { OpenableColumns.DISPLAY_NAME -> f.name; OpenableColumns.SIZE -> f.length(); else -> null } })
        return c
    }

    override fun getType(uri: Uri): String? {
        val f = fileFor(uri)
        if (f.extension.equals("apk", true)) return APK_MIME
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase())
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0

    companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
        private fun authority(context: Context) = "${context.packageName}.files"
        private fun downloadsRoot() = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).canonicalFile
        /** Must match the directory used by `ApkDownloader` in `AssistantApp`. */
        fun updatesRoot(context: Context): File = File(context.noBackupFilesDir, "updates").canonicalFile

        fun uriFor(context: Context, file: File): Uri {
            val root = downloadsRoot()
            val rel = file.canonicalFile.path.removePrefix(root.path).trimStart('/')
            return Uri.parse("content://${authority(context)}/downloads/$rel")
        }

        /** `content://` URI for a staged update APK; throws if [file] is outside the updates directory. */
        fun updateApkUri(context: Context, file: File): Uri {
            val root = updatesRoot(context)
            val c = file.canonicalFile
            require(c.parentFile == root) { "not a staged update: $file" }
            return Uri.Builder().scheme("content").authority(authority(context)).appendPath("updates").appendPath(c.name).build()
        }
    }
}
