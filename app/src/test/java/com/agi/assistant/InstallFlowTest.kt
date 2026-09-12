package com.agi.assistant

import com.agi.assistant.core.update.*
import com.agi.assistant.core.update.InstallPolicy.ApkFacts
import com.agi.assistant.core.update.InstallPolicy.Preflight
import kotlinx.coroutines.*
import java.io.File

/** Phase 4: install preflight rules, installer-result classification and manager install states. */
object InstallFlowTest {
    private var fails = 0; private var passed = 0
    private fun check(name: String, cond: Boolean, detail: Any? = "") {
        if (cond) { passed++; println("  ok   $name") } else { fails++; println("  FAIL $name  -> $detail") }
    }

    private fun info(versionName: String = "0.2.0", versionCode: Long? = 2) =
        UpdateInfo(versionName, versionCode, "v$versionName", "AGI $versionName", "", "https://github.com/x/y/releases/download/v$versionName/agi.apk", "agi.apk", -1, "", "https://github.com/x/y", true)

    private fun tempApk(bytes: Int = 200 * 1024): File = File.createTempFile("agi", ".apk").apply { writeBytes(ByteArray(bytes) { 1 }); deleteOnExit() }
    private const val PKG = "com.agi.assistant"
    private fun blocked(p: Preflight) = p as? Preflight.Blocked

    @JvmStatic
    fun main(args: Array<String>) {
        println("Preflight:")
        val f = tempApk()
        val good = ApkFacts(PKG, 2, "0.2.0", parsed = true)
        check("valid newer APK + permission -> Ok", InstallPolicy.preflight(f, info(), good, PKG, 1, true) is Preflight.Ok)
        var b = blocked(InstallPolicy.preflight(f, info(), good, PKG, 1, false))
        check("missing 'install unknown apps' -> PERMISSION_REQUIRED, file kept", b?.reason == InstallError.PERMISSION_REQUIRED && b.discardFile == false, b)
        b = blocked(InstallPolicy.preflight(File("/nonexistent/x.apk"), info(), good, PKG, 1, true))
        check("missing file -> FILE_MISSING (discard)", b?.reason == InstallError.FILE_MISSING && b.discardFile, b)
        val empty = tempApk(0)
        b = blocked(InstallPolicy.preflight(empty, info(), good, PKG, 1, true))
        check("empty file -> FILE_MISSING", b?.reason == InstallError.FILE_MISSING, b)
        b = blocked(InstallPolicy.preflight(f, info(), ApkFacts("com.other.app", 9, "1.0", true), PKG, 1, true))
        check("different applicationId -> PACKAGE_MISMATCH (discard)", b?.reason == InstallError.PACKAGE_MISMATCH && b.discardFile, b)
        b = blocked(InstallPolicy.preflight(f, info(), ApkFacts(PKG, 1, "0.1.0", true), PKG, 1, true))
        check("same versionCode -> NOT_NEWER", b?.reason == InstallError.NOT_NEWER, b)
        b = blocked(InstallPolicy.preflight(f, info(), ApkFacts(PKG, 0, "0.0.1", true), PKG, 1, true))
        check("lower versionCode -> NOT_NEWER", b?.reason == InstallError.NOT_NEWER, b)
        b = blocked(InstallPolicy.preflight(f, info(), ApkFacts(null, null, null, parsed = false), PKG, 1, true))
        check("unparseable APK -> INVALID_APK (discard)", b?.reason == InstallError.INVALID_APK && b.discardFile, b)
        check("platform facts unavailable: falls back to release versionCode (newer) -> Ok", InstallPolicy.preflight(f, info(versionCode = 2), null, PKG, 1, true) is Preflight.Ok)
        b = blocked(InstallPolicy.preflight(f, info(versionCode = 1), null, PKG, 1, true))
        check("facts unavailable + release versionCode not newer -> NOT_NEWER", b?.reason == InstallError.NOT_NEWER, b)
        check("facts unavailable + no versionCode anywhere -> Ok (installer decides)", InstallPolicy.preflight(f, info(versionCode = null), null, PKG, 1, true) is Preflight.Ok)
        check("order: package checks before permission", blocked(InstallPolicy.preflight(f, info(), ApkFacts("x", 5, "1", true), PKG, 1, false))?.reason == InstallError.PACKAGE_MISMATCH)

        println("Installer result classification:")
        check("RESULT_OK -> ReportedSuccess", InstallPolicy.classifyActivityResult(InstallPolicy.RESULT_OK, null) is InstallPolicy.Outcome.ReportedSuccess)
        check("RESULT_CANCELED -> Cancelled", InstallPolicy.classifyActivityResult(InstallPolicy.RESULT_CANCELED, null) is InstallPolicy.Outcome.Cancelled)
        fun failed(code: Int?) = InstallPolicy.classifyActivityResult(InstallPolicy.RESULT_FIRST_USER, code) as InstallPolicy.Outcome.Failed
        check("RESULT_FIRST_USER without code -> INSTALL_FAILED ('App not installed')", failed(null).reason == InstallError.INSTALL_FAILED && failed(null).message.contains("App not installed"))
        check("-7 UPDATE_INCOMPATIBLE -> SIGNATURE_MISMATCH", failed(-7).reason == InstallError.SIGNATURE_MISMATCH)
        check("-104 INCONSISTENT_CERTIFICATES -> SIGNATURE_MISMATCH", failed(-104).reason == InstallError.SIGNATURE_MISMATCH)
        check("signature message explains the key issue", failed(-7).message.contains("different key"))
        check("-4 INSUFFICIENT_STORAGE", failed(-4).reason == InstallError.INSUFFICIENT_STORAGE)
        check("-1 ALREADY_EXISTS -> PACKAGE_CONFLICT", failed(-1).reason == InstallError.PACKAGE_CONFLICT)
        check("-13 CONFLICTING_PROVIDER -> PACKAGE_CONFLICT", failed(-13).reason == InstallError.PACKAGE_CONFLICT)
        check("-2 INVALID_APK", failed(-2).reason == InstallError.INVALID_APK)
        check("-16 CPU_ABI_INCOMPATIBLE -> INCOMPATIBLE", failed(-16).reason == InstallError.INCOMPATIBLE)
        check("-12 OLDER_SDK -> INCOMPATIBLE", failed(-12).reason == InstallError.INCOMPATIBLE)
        check("-25 VERSION_DOWNGRADE -> NOT_NEWER", failed(-25).reason == InstallError.NOT_NEWER)
        check("-115 ABORTED -> USER_CANCELLED", failed(-115).reason == InstallError.USER_CANCELLED)
        check("unknown code -> INSTALL_FAILED with code in message", failed(-999).reason == InstallError.INSTALL_FAILED && failed(-999).message.contains("-999"))

        println("isInstalled (post-restart reconciliation):")
        check("versionCode reached -> installed", InstallPolicy.isInstalled(info(versionCode = 2), "0.2.0", 2))
        check("versionCode not reached -> not installed even if name matches", !InstallPolicy.isInstalled(info(versionCode = 2), "0.2.0", 1))
        check("no versionCode: semantic compare", InstallPolicy.isInstalled(info(versionCode = null), "0.2.1", 1) && !InstallPolicy.isInstalled(info(versionCode = null), "0.1.9", 1))

        println("Manager install states:")
        runBlocking {
            val dir = kotlin.io.path.createTempDirectory("inst").toFile()
            val i = info()
            val dl = ApkDownloader(dir, Dispatchers.IO)
            val target = dl.targetFile(i).apply { parentFile!!.mkdirs(); writeBytes(ByteArray(200 * 1024) { 1 }.also { it[0] = 0x50; it[1] = 0x4B; it[2] = 3; it[3] = 4 }) }
            val repo = UpdateRepository(object : UpdateChecker { override suspend fun check(installedVersionName: String, installedVersionCode: Long) = UpdateCheckResult.Success(i) }, { InstalledVersion("0.1.0", 1) }, Dispatchers.Unconfined)
            val scope = CoroutineScope(Dispatchers.Default)
            val mgr = UpdateManager(repo, scope, downloader = dl)
            val states = java.util.Collections.synchronizedList(ArrayList<String>())
            mgr.addObserver { states += it::class.simpleName!! }
            mgr.checkNow().join(); mgr.startDownload(i)!!.join()
            check("staged file reused -> ReadyToInstall", mgr.state is UpdateState.ReadyToInstall, mgr.state)

            mgr.markInstallerLaunched(target)
            check("markInstallerLaunched -> InstallerLaunched (not 'installed')", mgr.state is UpdateState.InstallerLaunched && !mgr.state.isTerminal)
            check("checkNow while installer open does not disturb state", run { mgr.checkNow().join(); mgr.state is UpdateState.InstallerLaunched })
            mgr.installerReturned()!!.join()
            check("returned without result -> ReadyToInstall again (INSTALL enabled)", mgr.state is UpdateState.ReadyToInstall, mgr.state)

            mgr.markInstallFailed(InstallError.PERMISSION_REQUIRED, "allow first")
            val e = mgr.state as? UpdateState.InstallationError
            check("permission missing -> InstallationError, file kept", e?.reason == InstallError.PERMISSION_REQUIRED && e.fileDiscarded == false && target.exists(), mgr.state)
            mgr.retryInstall()!!.join()
            check("retry after permission granted -> ReadyToInstall", mgr.state is UpdateState.ReadyToInstall, mgr.state)

            mgr.markInstallerLaunched(target)
            mgr.markInstallFailed(InstallError.SIGNATURE_MISMATCH, "different key")
            check("installer failure -> InstallationError with reason", (mgr.state as? UpdateState.InstallationError)?.reason == InstallError.SIGNATURE_MISMATCH && target.exists())
            check("statusLine for install error", UpdateMessages.statusLine(mgr.state, "0.1.0").startsWith("Installation failed: different key"))

            mgr.markInstallFailed(InstallError.INVALID_APK, "corrupt", discardFile = true)
            check("discarding failure deletes staged file", !target.exists() && (mgr.state as UpdateState.InstallationError).fileDiscarded)
            val j = mgr.retryInstall()
            check("retry after discard re-downloads (Downloading)", j != null && states.last() == "Downloading" || mgr.state is UpdateState.Downloading || mgr.state is UpdateState.DownloadFailed, mgr.state)
            j?.join()

            // Reconcile: app restarted as the new version.
            target.writeBytes(ByteArray(10))
            val mgr2 = UpdateManager(repo, scope, downloader = dl)
            mgr2.checkNow().join()
            mgr2.startDownload(i) // stages info (download fails fast: bad file/no server), fine for this test
            delay(50)
            check("reconcile: old version still installed -> false", !mgr2.reconcileInstalled("0.1.0", 1))
            check("reconcile: new version installed -> cleans staged file", mgr2.reconcileInstalled("0.2.0", 2) && !target.exists() && mgr2.stagedInfo == null)

            check("InstallerLaunched status line never says installed", !UpdateMessages.statusLine(UpdateState.InstallerLaunched(i, target), "0.1.0").contains("installed", true) || UpdateMessages.statusLine(UpdateState.InstallerLaunched(i, target), "0.1.0").contains("finish installing"))
            scope.cancel()
        }

        println("\n$passed passed, $fails failed")
        if (fails > 0) System.exit(1)
    }
}
