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
    /** Cooldown/offline rules for automatic checks (Phase 5). Null → legacy in-memory [checkIfStale]. */
    private val autoPolicy: AutoCheckPolicy? = null,
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

    /**
     * Fires once when a download the user started (this session) has been verified and staged, so the
     * UI can open the Android installer immediately instead of waiting for a second tap. The manager
     * itself never installs anything; the listener is the foreground Activity's ApkInstaller.
     */
    @Volatile var onReadyToInstall: ((UpdateState.ReadyToInstall) -> Unit)? = null

    fun addObserver(o: Observer) { observers += o; o.onStateChanged(state) }
    fun removeObserver(o: Observer) { observers -= o }

    /** Runs a check unless one is already in flight or a download is active/staged. */
    fun checkNow(): Job = check(automatic = false)

    /**
     * Automatic check (app start / foreground). [online] comes from ConnectivityManager. Obeys
     * [AutoCheckPolicy] cooldown/throttle and fails *silently*: an offline or failed automatic
     * check leaves the previous state untouched, so no error appears anywhere in the UI.
     * Returns null when no request was made.
     */
    fun checkAutomatically(online: Boolean): Job? {
        val p = autoPolicy ?: return checkIfStale()
        lastAutoDecision = p.decide(online, state)
        if (lastAutoDecision != AutoCheckPolicy.Decision.CHECK) return null
        p.markAttempt()
        return check(automatic = true)
    }

    @Volatile var lastAutoDecision: AutoCheckPolicy.Decision? = null
        private set

    private fun check(automatic: Boolean): Job {
        job?.takeIf { it.isActive }?.let { return it }
        if (state is UpdateState.Downloading) return downloadJob!!
        if (state is UpdateState.InstallerLaunched) return job ?: scope.launch {} // don't disturb an install in progress
        val previous = state
        setState(UpdateState.Checking)
        return scope.launch {
            val result = repository.checkForUpdate()
            autoPolicy?.recordResult(result)
            var next = repository.toState(result)
            if (automatic && result is UpdateCheckResult.Failure) next = if (previous is UpdateState.Checking) UpdateState.Idle else previous
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
                is DownloadResult.Success -> {
                    val ready = UpdateState.ReadyToInstall(info, result.file, result.sha256, result.verified)
                    setState(ready)
                    if (reuse == null) onReadyToInstall?.invoke(ready)   // fresh download → hand straight to the installer
                }
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
        val info = (state as? UpdateState.DownloadFailed)?.info ?: (state as? UpdateState.ReadyToInstall)?.info ?: (state as? UpdateState.InstallationError)?.info ?: return
        downloader?.cleanup(info)
        stagedInfo = null
        setState(UpdateState.UpdateAvailable(info))
    }

    // ---- Install (Phase 4) -------------------------------------------------------
    // The manager never installs anything itself; the UI layer (ApkInstaller) launches the system
    // installer and reports back through these methods so every screen shows the same state.

    /** The system installer UI was opened for the staged file. */
    fun markInstallerLaunched(file: java.io.File) {
        val info = (state as? UpdateState.ReadyToInstall)?.info ?: (state as? UpdateState.InstallationError)?.info ?: stagedInfo ?: return
        setState(UpdateState.InstallerLaunched(info, file))
    }

    /** Install could not start / failed. Keeps the file (for a retry) unless [discardFile]. */
    fun markInstallFailed(reason: InstallError, message: String, discardFile: Boolean = false) {
        val (info, file) = when (val s = state) {
            is UpdateState.ReadyToInstall -> s.info to s.file
            is UpdateState.InstallerLaunched -> s.info to s.file
            is UpdateState.InstallationError -> s.info to s.file
            else -> { val i = stagedInfo ?: return; i to (downloader?.targetFile(i) ?: return) }
        }
        if (discardFile) downloader?.cleanup(info)
        setState(UpdateState.InstallationError(info, file, reason, message, discardFile))
    }

    /** User came back from the installer/settings without a result: allow INSTALL again if the file is still complete. */
    fun installerReturned(): Job? {
        val s = state as? UpdateState.InstallerLaunched ?: return null
        val dl = downloader ?: run { setState(UpdateState.ReadyToInstall(s.info, s.file, "", false)); return null }
        return scope.launch {
            val ok = dl.existingComplete(s.info)
            if (state !is UpdateState.InstallerLaunched) return@launch
            if (ok != null) setState(UpdateState.ReadyToInstall(s.info, ok.file, ok.sha256, ok.verified))
            else setState(UpdateState.InstallationError(s.info, s.file, InstallError.FILE_MISSING, "The downloaded update file is no longer available. Please download it again.", fileDiscarded = true))
        }
    }

    /** After an [UpdateState.InstallationError]: back to ReadyToInstall (file kept) or re-download (file discarded). */
    fun retryInstall(): Job? {
        val s = state as? UpdateState.InstallationError ?: return null
        return if (s.fileDiscarded) startDownload(s.info) else installerReturnedFrom(s)
    }

    private fun installerReturnedFrom(s: UpdateState.InstallationError): Job? {
        setState(UpdateState.InstallerLaunched(s.info, s.file))
        return installerReturned()
    }

    /**
     * Called on app start: if the staged release is now the installed version, the update
     * succeeded and the staged APK can be removed. Returns true when a cleanup happened.
     */
    fun reconcileInstalled(installedVersionName: String, installedVersionCode: Long): Boolean {
        val dl = downloader ?: return false
        val staged = stagedInfo ?: return false
        if (!InstallPolicy.isInstalled(staged, installedVersionName, installedVersionCode)) return false
        dl.cleanup(staged); stagedInfo = null
        if (state is UpdateState.ReadyToInstall || state is UpdateState.InstallerLaunched || state is UpdateState.InstallationError)
            setState(UpdateState.UpToDate(installedVersionName, staged))
        return true
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
