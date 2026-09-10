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
 * Minimal read-only content provider so the assistant can hand a file in the
 * public Downloads folder to a viewer app with a temporary read grant. It
 * intentionally only exposes files under Downloads.
 */
class AssistantFileProvider : ContentProvider() {
    override fun onCreate() = true

    private fun fileFor(uri: Uri): File {
        val rel = uri.path?.removePrefix("/downloads/") ?: throw FileNotFoundException(uri.toString())
        val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).canonicalFile
        val f = File(root, rel).canonicalFile
        if (!f.path.startsWith(root.path) || !f.isFile) throw FileNotFoundException(uri.toString())
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

    override fun getType(uri: Uri): String? =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileFor(uri).extension.lowercase())

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0

    companion object {
        fun uriFor(context: Context, file: File): Uri {
            val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).canonicalFile
            val rel = file.canonicalFile.path.removePrefix(root.path).trimStart('/')
            return Uri.parse("content://${context.packageName}.files/downloads/$rel")
        }
    }
}
