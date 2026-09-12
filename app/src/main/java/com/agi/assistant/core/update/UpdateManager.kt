package com.agi.assistant.core.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArraySet

/**
 * App-scoped state holder (ViewModel-like, but lifecycle independent so it
 * survives Activity recreation without AndroidX). Observers are notified on
 * the scope's dispatcher (the app passes a main-thread scope).
 *
 * Phases 1–3: check → prompt → download. Installing is Phase 4.
 */
class UpdateManager(
    private val repository: UpdateRepository,
    private val scope: CoroutineScope,
    /** Minimum interval between automatic checks (default 6 h). */
    private val minAutoCheckIntervalMs: Long = 6 * 60 * 60 * 1000L,
    private val downloader: ApkDownloader? = null,
    /** Hop used to publish download progress to observers (the app passes the main dispatcher). */
    private val notifyDispatcher: kotlinx.coroutines.CoroutineDispatcher? = null,
) {
    fun interface Observer { fun onStateChanged(state: UpdateState) }

    @Volatile var state: UpdateState = UpdateState.Idle
        private set
    private val observers = CopyOnWriteArraySet<Observer>()
    private var job: Job? = null
    private var downloadJob: Job? = null

    /** The release we are downloading / have downloaded; survives a later "up to date" re-check. */
    @Volatile var stagedInfo: UpdateInfo? = null
        private set

    fun addObserver(o: Observer) { observers += o; o.onStateChanged(state) }
    fun removeObserver(o: Observer) { observers -= o }

    /** Runs a check unless one is already in flight or a download is active/staged. */
    fun checkNow(): Job {
        job?.takeIf { it.isActive }?.let { return it }
        if (state is UpdateState.Downloading) return downloadJob!!
        setState(UpdateState.Checking)
        return scope.launch {
            val result = repository.checkForUpdate()
            var next = repository.toState(result)
            // Keep a finished download visible if it is still the newest release.
            val staged = stagedInfo
            if (staged != null && next is UpdateState.UpdateAvailable && next.info.releaseTag == staged.releaseTag && downloader != null) {
                downloader.existingComplete(staged)?.let { next = UpdateState.ReadyToInstall(staged, it.file, it.sha256, it.verified) }
            }
            setState(next)
        }.also { job = it }
    }

    // ---- Download (Phase 3) -----------------------------------------------------

    val isDownloading: Boolean get() = downloadJob?.isActive == true

    /**
     * Starts downloading [info]'s APK. No-op if that release is already downloading;
     * reuses an already complete & verified file instantly.
     */
    fun startDownload(info: UpdateInfo): Job? {
        val dl = downloader ?: return null
        if (isDownloading) return downloadJob
        stagedInfo = info
        setState(UpdateState.Downloading(info, 0, info.apkSizeBytes))
        return scope.launch {
            val reuse = dl.existingComplete(info)
            val result = reuse ?: dl.download(info) { done, total ->
                val s = UpdateState.Downloading(info, done, total)
                if (notifyDispatcher != null) scope.launch(notifyDispatcher) { if (state is UpdateState.Downloading) setState(s) }
                else if (state is UpdateState.Downloading) setState(s)
            }
            when (result) {
                is DownloadResult.Success -> setState(UpdateState.ReadyToInstall(info, result.file, result.sha256, result.verified))
                is DownloadResult.Failure -> setState(UpdateState.DownloadFailed(info, result.reason, result.message, result.cause))
            }
        }.also { downloadJob = it }
    }

    /** Cancels an in-flight download; the partial file is removed and the state returns to UpdateAvailable. */
    fun cancelDownload() {
        val j = downloadJob ?: return
        val info = (state as? UpdateState.Downloading)?.info ?: stagedInfo
        j.cancel()
        downloadJob = null
        info?.let { downloader?.cleanup(it); setState(UpdateState.UpdateAvailable(it)) }
    }

    /** Retry after [UpdateState.DownloadFailed] (resumes a partial file when the server allows it). */
    fun retryDownload(): Job? = (state as? UpdateState.DownloadFailed)?.let { startDownload(it.info) }

    /** Discards a failed download and returns to the plain "update available" state. */
    fun discardDownload() {
        val info = (state as? UpdateState.DownloadFailed)?.info ?: (state as? UpdateState.ReadyToInstall)?.info ?: return
        downloader?.cleanup(info)
        stagedInfo = null
        setState(UpdateState.UpdateAvailable(info))
    }

    /** Called from app start; throttled so we do not hammer GitHub. */
    fun checkIfStale(): Job? {
        val stale = System.currentTimeMillis() - repository.lastCheckedAt > minAutoCheckIntervalMs
        return if (stale && state !is UpdateState.Checking) checkNow() else null
    }

    private fun setState(s: UpdateState) {
        state = s
        observers.forEach { it.onStateChanged(s) }
    }
}
