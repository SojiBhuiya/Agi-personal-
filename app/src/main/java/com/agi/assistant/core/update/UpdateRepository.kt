package com.agi.assistant.core.update

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Describes the currently installed build; supplied by the Android layer (PackageManager). */
data class InstalledVersion(val versionName: String, val versionCode: Long)

/**
 * Single entry point for "is there an update?". Runs the checker on [ioDispatcher]
 * and remembers the last result so multiple screens can share it.
 */
class UpdateRepository(
    private val checker: UpdateChecker,
    private val installedVersionProvider: () -> InstalledVersion,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    @Volatile var lastResult: UpdateCheckResult? = null
        private set
    @Volatile var lastCheckedAt: Long = 0L
        private set

    val installed: InstalledVersion get() = installedVersionProvider()

    suspend fun checkForUpdate(): UpdateCheckResult = withContext(ioDispatcher) {
        val v = installedVersionProvider()
        val r = try {
            checker.check(v.versionName, v.versionCode)
        } catch (e: Exception) {
            UpdateCheckResult.Failure(UpdateError.UNKNOWN, e.message ?: e.javaClass.simpleName, e)
        }
        lastResult = r
        lastCheckedAt = System.currentTimeMillis()
        r
    }

    /** Maps a result to the UI state, given the installed version. */
    fun toState(result: UpdateCheckResult): UpdateState = when (result) {
        is UpdateCheckResult.Success ->
            if (result.info.isNewerVersion) UpdateState.UpdateAvailable(result.info)
            else UpdateState.UpToDate(installed.versionName, result.info)
        is UpdateCheckResult.Failure -> UpdateState.Error(result.reason, result.message, result.cause)
    }
}
