package com.agi.assistant.core.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.coroutineContext

/** Result of [ApkDownloader.download]. */
sealed class DownloadResult {
    data class Success(val file: File, val sha256: String, val verified: Boolean, val bytes: Long) : DownloadResult()
    data class Failure(val reason: DownloadError, val message: String, val cause: Throwable? = null) : DownloadResult()
}

/**
 * Downloads a release APK into app-controlled storage ([directory]).
 *
 * Safety properties:
 *  - HTTPS only; the URL comes from [UpdateInfo.apkDownloadUrl] (GitHub asset), never hard-coded.
 *  - Streams into `<name>.part`; the final file only appears after the byte count matches the
 *    server's Content-Length (when known) and the SHA-256 matches a published checksum (when known).
 *    So "complete" is never reported for a truncated or corrupted file.
 *  - Never touches the installed application; the file is just staged for the system installer.
 *  - Resumes an interrupted `.part` via `Range` when the server supports it; otherwise restarts.
 *  - Partial files are deleted on any failure that cannot be resumed and on cancellation.
 *  - Checks free space up front (Content-Length + margin) → INSUFFICIENT_STORAGE.
 */
class ApkDownloader(
    private val directory: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val connectTimeoutMs: Int = 20_000,
    private val readTimeoutMs: Int = 30_000,
    /** Injectable for tests: opens a connection for a URL. */
    private val open: (URL) -> HttpURLConnection = { url ->
        (url.openConnection() as HttpsURLConnection).apply { instanceFollowRedirects = true }
    },
    private val freeSpace: (File) -> Long = { it.usableSpace },
    /**
     * Checksum policy. `true` (production default): a release must publish a SHA-256 – inline in the
     * notes or as `<apk>.sha256` / `SHA256SUMS` – and a download without one is refused
     * (`CHECKSUM_UNAVAILABLE`), so an unverified APK is never handed to the installer.
     * `false` only for repositories that never publish checksums; the result is then flagged unverified.
     */
    private val requireChecksum: Boolean = true,
) {
    fun interface ProgressListener { fun onProgress(bytesDownloaded: Long, totalBytes: Long) }

    /** Minimum plausible APK size; anything smaller is treated as an invalid response (error page etc.). */
    private val minApkBytes = 50 * 1024L
    private val progressStepBytes = 64 * 1024L

    /** Where the APK for [info] is staged. Deterministic so a completed download can be reused. */
    fun targetFile(info: UpdateInfo): File = File(directory, safeName(info))
    private fun partFile(info: UpdateInfo): File = File(directory, safeName(info) + ".part")

    private fun safeName(info: UpdateInfo): String {
        val base = info.apkAssetName.ifBlank { "update-${info.versionName}.apk" }.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return "agi-assistant-${info.versionName}-" + base.removeSuffix(".apk").takeLast(40) + ".apk"
    }

    /** Returns an already-downloaded, verified file for [info] if present (size sane & checksum OK when known). */
    suspend fun existingComplete(info: UpdateInfo): DownloadResult.Success? = withContext(ioDispatcher) {
        val f = targetFile(info)
        if (!f.isFile || f.length() < minApkBytes) return@withContext null
        if (info.apkSizeBytes > 0 && f.length() != info.apkSizeBytes) { f.delete(); return@withContext null }
        val sum = sha256(f)
        val expected = info.apkSha256
        if (expected != null && !expected.equals(sum, true)) { f.delete(); return@withContext null }
        DownloadResult.Success(f, sum, expected != null, f.length())
    }

    /** Deletes any staged files for [info] (used after a failure the user gives up on, or after install). */
    fun cleanup(info: UpdateInfo) { partFile(info).delete(); targetFile(info).delete() }

    /** Deletes every staged APK/partial in [directory]. */
    fun cleanupAll() { directory.listFiles()?.filter { it.name.endsWith(".apk") || it.name.endsWith(".part") }?.forEach { it.delete() } }

    /** Deletes incomplete partials and staged APKs older than [maxAgeMs] (complete recent ones are kept for reuse). */
    fun cleanupStale(maxAgeMs: Long = 7L * 24 * 3600 * 1000) {
        val now = System.currentTimeMillis()
        directory.listFiles()?.forEach { f ->
            if (f.name.endsWith(".part") || (f.name.endsWith(".apk") && now - f.lastModified() > maxAgeMs)) f.delete()
        }
    }

    /**
     * Downloads [info]'s APK. Progress is reported on the calling dispatcher of [onProgress] (IO thread);
     * callers should hop to main. Cancelling the coroutine yields [DownloadError.CANCELLED] and removes the partial file.
     */
    suspend fun download(info: UpdateInfo, onProgress: ProgressListener = ProgressListener { _, _ -> }): DownloadResult = withContext(ioDispatcher) {
        val url = info.apkDownloadUrl
        if (!url.startsWith("https://", ignoreCase = true))
            return@withContext DownloadResult.Failure(DownloadError.INVALID_RESPONSE, "Refusing to download over a non-HTTPS URL.")
        if (!directory.isDirectory && !directory.mkdirs())
            return@withContext DownloadResult.Failure(DownloadError.STORAGE_IO, "Cannot create the download directory.")

        val part = partFile(info)
        val target = targetFile(info)
        target.delete() // never hand out a stale file under the final name while we are downloading

        // Expected sum: inline in the notes, else fetched from the checksum asset.
        val expectedSha = info.apkSha256 ?: info.checksumAssetUrl?.let { fetchChecksum(it, info.apkAssetName) }
        if (expectedSha == null && requireChecksum) {
            return@withContext DownloadResult.Failure(
                DownloadError.CHECKSUM_UNAVAILABLE,
                if (info.checksumAssetUrl != null) "The release's checksum file could not be read, so the update cannot be verified. Please try again later."
                else "This release does not publish a SHA-256 checksum, so the update cannot be verified and will not be installed.",
            )
        }

        var attempt = 0
        var lastFailure: DownloadResult.Failure? = null
        while (attempt < 2) { // one automatic retry for transient interruptions
            attempt++
            val r = downloadOnce(url, part, info, onProgress)
            when (r) {
                is Once.Done -> {
                    if (r.bytes < minApkBytes) {
                        part.delete()
                        return@withContext DownloadResult.Failure(DownloadError.INVALID_RESPONSE, "The server returned an empty or invalid APK (${r.bytes} bytes).")
                    }
                    if (info.apkSizeBytes > 0 && r.bytes != info.apkSizeBytes) {
                        part.delete()
                        return@withContext DownloadResult.Failure(DownloadError.INVALID_RESPONSE, "Downloaded size (${r.bytes} bytes) does not match the release asset (${info.apkSizeBytes} bytes).")
                    }
                    if (!isApk(part)) {
                        part.delete()
                        return@withContext DownloadResult.Failure(DownloadError.INVALID_RESPONSE, "The downloaded file is not a valid APK.")
                    }
                    val sum = sha256(part)
                    if (expectedSha != null && !expectedSha.equals(sum, ignoreCase = true)) {
                        part.delete()
                        return@withContext DownloadResult.Failure(DownloadError.CHECKSUM_MISMATCH, "Checksum verification failed. The download was discarded for your safety.")
                    }
                    if (!part.renameTo(target)) {
                        part.delete()
                        return@withContext DownloadResult.Failure(DownloadError.STORAGE_IO, "Could not finalise the downloaded file.")
                    }
                    return@withContext DownloadResult.Success(target, sum, expectedSha != null, r.bytes)
                }
                is Once.Fail -> {
                    lastFailure = DownloadResult.Failure(r.reason, r.message, r.cause)
                    if (!r.retryable) { part.delete(); return@withContext lastFailure }
                    // retryable (interrupted/timeout): keep .part for resume and loop once more
                }
            }
        }
        // Give up but keep the partial so an explicit user retry can resume it.
        return@withContext lastFailure!!
    }

    private sealed class Once {
        data class Done(val bytes: Long) : Once()
        data class Fail(val reason: DownloadError, val message: String, val retryable: Boolean, val cause: Throwable? = null) : Once()
    }

    private suspend fun downloadOnce(url: String, part: File, info: UpdateInfo, onProgress: ProgressListener): Once {
        var conn: HttpURLConnection? = null
        var out: FileOutputStream? = null
        var downloaded = 0L
        try {
            val existing = if (part.isFile) part.length() else 0L
            conn = open(URL(url)).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("Accept", "application/octet-stream")
                setRequestProperty("User-Agent", "AGI-Assistant-Android")
                if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
            }
            val code = conn.responseCode
            val resuming = code == HttpURLConnection.HTTP_PARTIAL && existing > 0
            if (code !in 200..299) {
                return Once.Fail(DownloadError.HTTP, when (code) {
                    404 -> "The update file is no longer available (HTTP 404)."
                    403, 429 -> "GitHub is rate-limiting downloads right now (HTTP $code). Please try again later."
                    in 500..599 -> "GitHub is having trouble (HTTP $code). Please try again later."
                    else -> "Download failed with HTTP $code."
                }, retryable = code in 500..599)
            }
            val type = conn.contentType?.lowercase().orEmpty()
            if (type.startsWith("text/html")) return Once.Fail(DownloadError.INVALID_RESPONSE, "The server returned a web page instead of the APK.", retryable = false)

            val len = conn.contentLengthLong
            val total = when {
                resuming && len > 0 -> existing + len
                len > 0 -> len
                info.apkSizeBytes > 0 -> info.apkSizeBytes
                else -> -1L
            }
            val need = (if (total > 0) total - (if (resuming) existing else 0L) else info.apkSizeBytes.coerceAtLeast(20L * 1024 * 1024)) + 8L * 1024 * 1024
            if (freeSpace(directory) < need)
                return Once.Fail(DownloadError.INSUFFICIENT_STORAGE, "Not enough free storage to download the update (${need / 1024 / 1024} MB needed).", retryable = false)

            if (!resuming) part.delete()
            downloaded = if (resuming) existing else 0L
            var lastReport = downloaded
            onProgress.onProgress(downloaded, total)

            out = FileOutputStream(part, resuming)
            val input: InputStream = conn.inputStream
            val buf = ByteArray(64 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                downloaded += n
                if (downloaded - lastReport >= progressStepBytes || downloaded == total) {
                    lastReport = downloaded
                    onProgress.onProgress(downloaded, total)
                }
            }
            out.flush(); out.fd.sync(); out.close(); out = null
            if (total > 0 && downloaded < total)
                return Once.Fail(DownloadError.INTERRUPTED, "The connection closed before the download finished.", retryable = true)
            if (total > 0 && downloaded > total)
                return Once.Fail(DownloadError.INVALID_RESPONSE, "The server sent more data than expected.", retryable = false)
            onProgress.onProgress(downloaded, if (total > 0) total else downloaded)
            return Once.Done(downloaded)
        } catch (e: CancellationException) {
            runCatching { out?.close() }
            part.delete()
            return Once.Fail(DownloadError.CANCELLED, "Download cancelled.", retryable = false, cause = e)
        } catch (e: SocketTimeoutException) {
            return Once.Fail(DownloadError.TIMEOUT, "The network is too slow or unresponsive. Please try again.", retryable = true, cause = e)
        } catch (e: InterruptedIOException) {
            return Once.Fail(DownloadError.INTERRUPTED, "The download was interrupted.", retryable = true, cause = e)
        } catch (e: IOException) {
            val msg = e.message.orEmpty()
            return if (msg.contains("ENOSPC", true) || msg.contains("No space left", true))
                Once.Fail(DownloadError.INSUFFICIENT_STORAGE, "Your phone ran out of storage during the download.", retryable = false, cause = e)
            else if (downloaded > 0 && (msg.contains("Premature EOF", true) || msg.contains("unexpected end of stream", true) || msg.contains("reset", true)))
                Once.Fail(DownloadError.INTERRUPTED, "The connection closed before the download finished.", retryable = true, cause = e)
            else Once.Fail(DownloadError.NETWORK, "Connection failed: ${e.message ?: e.javaClass.simpleName}", retryable = true, cause = e)
        } catch (e: Exception) {
            return Once.Fail(DownloadError.UNKNOWN, "Unexpected error: ${e.message ?: e.javaClass.simpleName}", retryable = false, cause = e)
        } finally {
            runCatching { out?.close() }
            conn?.disconnect()
        }
    }

    /** Fetches a small checksum asset; returns null on any problem (download proceeds unverified). */
    private fun fetchChecksum(url: String, apkName: String): String? = runCatching {
        if (!url.startsWith("https://", true)) return null
        val c = open(URL(url)).apply { connectTimeout = connectTimeoutMs; readTimeout = readTimeoutMs; setRequestProperty("User-Agent", "AGI-Assistant-Android") }
        try {
            if (c.responseCode !in 200..299 || c.contentLengthLong > 64 * 1024) return null
            GitHubReleaseParser.parseChecksumFile(c.inputStream.bufferedReader().use { it.readText() }, apkName)
        } finally { c.disconnect() }
    }.getOrNull()

    companion object {
        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        /** An APK is a ZIP: it must start with the local-file-header magic `PK\u0003\u0004`. */
        fun isApk(file: File): Boolean = runCatching {
            file.inputStream().use { val b = ByteArray(4); it.read(b) == 4 && b[0] == 0x50.toByte() && b[1] == 0x4B.toByte() && b[2] == 3.toByte() && b[3] == 4.toByte() }
        }.getOrDefault(false)
    }
}
