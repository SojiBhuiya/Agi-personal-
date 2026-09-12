# In-app update system

Source of truth: **GitHub Releases** of `SojiBhuiya/Agi-personal-`
(`https://api.github.com/repos/SojiBhuiya/Agi-personal-/releases/latest`, HTTPS only).

## Phase 1 (implemented) – check & compare

```
UpdateManager (app-scoped state holder, observers on main thread)
   └─ UpdateRepository (runs on Dispatchers.IO, caches last result)
        ├─ InstalledVersion  ← AssistantApp.installedVersion() (PackageManager, never hard-coded)
        └─ UpdateChecker (interface)
             └─ GitHubReleaseUpdateChecker (HttpsURLConnection, injectable fetch)
                  ├─ GitHubReleaseParser  (tag_name / name / body / published_at / assets → first suitable .apk)
                  └─ SemanticVersion      (v-prefix normalised, SemVer ordering, pre-release aware)
```

* `UpdateInfo`: versionName, versionCode (optional `versionCode: N` line in the release notes),
  releaseTag, releaseName, releaseNotes, apkDownloadUrl, apkAssetName, apkSizeBytes, publishedAt,
  htmlUrl, isNewerVersion.
* `UpdateState`: `Idle → Checking → UpdateAvailable | UpToDate | Error(reason)` with
  `UpdateError ∈ {NETWORK, HTTP, MALFORMED_RESPONSE, NO_APK_ASSET, INVALID_VERSION, UNKNOWN}`.
  Nothing throws to callers; a repo with no releases yields `Error(HTTP, "No releases have been published yet.")`.
* APK asset selection: any asset whose name ends in `.apk` (or has the APK content type) and is
  fully uploaded; prefers `universal` > `release` > others, avoids ABI splits/`debug`, then largest.
  Non-HTTPS download URLs are rejected.
* Version rule: `release > installed` ⇒ update; equal versionName falls back to the published
  `versionCode` when available; an unparseable installed version never reports an update.

### UI
* Settings › **Updates** card: installed version, *Check for updates*, status/notes, *View release*.
* Home screen: automatic throttled check on start (≥ 6 h apart) and a banner when an update exists.

### Publishing a release the app will pick up
```
git tag v0.2.0 && git push --tags
gh release create v0.2.0 dist/agi-assistant-0.2.0-release.apk --title "AGI Assistant 0.2.0" --notes "…"
```
Bump `versionName`/`versionCode` in `app/build.gradle.kts` first; optionally add `versionCode: N`
to the notes.

## Phase 2 (implemented) – update UI

* **Dialog** (`ui/UpdateDialog.kt`, `layout/dialog_update.xml`): "New Update Available", current
  version, new version, release date/size, *What's New* (Markdown bullets normalised), **UPDATE** and
  **LATER**. UPDATE starts the in-app download (Phase 3).
* **Non-intrusive policy** (`core/update/UpdatePromptPolicy.kt`): prompt only for newer releases,
  once per session per tag (no duplicate dialogs), and never within 24 h of a "Later" tap.
  A release is **mandatory** when its notes contain `mandatory: true` or `[mandatory]` – then LATER is
  hidden, the dialog is not cancelable, and it is shown every session.
* **Settings › Updates** card: installed version, *Check for updates* (shows "Checking for updates..."),
  status via `UpdateMessages` – "You’re using the latest version." / "Unable to check for updates. Please
  try again later." – and an **Update** button that reopens the dialog.
* Home screen: throttled auto-check on start; banner "Update available …" with *View*.
* Tests: `UpdateUiPolicyTest` (27 checks) covers dedupe, postponement/snooze expiry, mandatory rules,
  marker parsing and message formatting.

## Phase 3 (implemented) – APK download

* **`core/update/ApkDownloader.kt`** streams `UpdateInfo.apkDownloadUrl` (the GitHub asset URL from
  the release check – nothing is hard-coded, HTTPS only) into app-private storage
  `noBackupFilesDir/updates/<tag>-<asset>.apk`. The installed app is never touched.
* **Never reports "complete" unless it is**: data goes to a `.part` file; it is renamed to `.apk` only
  after byte count == Content-Length (and == the release asset size), the file is a ZIP (APK magic),
  is at least 50 KB and has a non-HTML content type. If the release publishes a SHA-256 (inline
  `sha256: <hex>` / `<hex>  <apk>` line in the notes, a `<apk>.sha256` asset, `SHA256SUMS`, or
  `checksums.txt`) the digest is verified and a mismatch deletes the file
  (`DownloadError.CHECKSUM_MISMATCH`). `ReadyToInstall.verified` tells the UI whether a checksum was checked.
* **Failure handling** → `UpdateState.DownloadFailed(reason, message)`: `NETWORK`, `TIMEOUT`
  (slow/unresponsive), `INTERRUPTED` (connection closed early – partial kept, resumed with `Range` on
  retry, restarted if the server ignores ranges), `INSUFFICIENT_STORAGE` (checked up front against
  Content-Length + 8 MB and detected via ENOSPC mid-way), `HTTP` (4xx/5xx), `INVALID_RESPONSE`
  (empty/tiny/HTML/non-ZIP/size mismatch/plain http), `CANCELLED`. One automatic retry for transient
  errors, then the user gets **RETRY**. Cancelling or non-resumable failures delete the `.part`;
  `cleanupStale()` removes leftovers older than 7 days at app start.
* **State machine** (`UpdateManager`): `UpdateAvailable → Downloading(bytes,total) → ReadyToInstall(file, sha256, verified)`
  or `DownloadFailed`; `startDownload / cancelDownload / retryDownload / discardDownload`. A re-check
  keeps `ReadyToInstall` when the staged tag is still the newest. Downloads run on `Dispatchers.IO`
  in the app scope, so the assistant stays fully usable; the dialog can be dismissed while downloading.
* **UI**: dialog shows "Downloading update... 62%" with a progress bar and "1.2 MB of 4.8 MB",
  **CANCEL**; on completion "Update downloaded" + **INSTALL**; on failure the message + **RETRY**.
  Home banner "Update downloaded: X is ready to install." and Settings status reflect the same state.
  **No auto-install**: INSTALL is a placeholder until Phase 4 – nothing is installed automatically.
* Tests: `ApkDownloaderTest` (62 checks, local HTTP server + stub connections).

## Phase 4 (implemented) – install through the Android package installer

Flow: **INSTALL** → preflight (`core/update/InstallPolicy.kt`) → *Install unknown apps* check →
(if missing: `ACTION_MANAGE_UNKNOWN_APP_SOURCES` for `package:com.agi.assistant`, user returns, taps
INSTALL again) → `ui/ApkInstaller.kt` launches the **system** installer → user confirms → Android
replaces the app in place; data is preserved because the package name and signing key are unchanged.

* **FileProvider**: the staged APK (`noBackupFilesDir/updates/…apk`) is exposed only through
  `AssistantFileProvider` (`content://com.agi.assistant.files/updates/<name>`), `exported=false`,
  `grantUriPermissions=true`, path canonicalised (no `..`). The installer gets a temporary
  `FLAG_GRANT_READ_URI_PERMISSION`. **No `file://` URI is ever created.**
* **Intent**: `ACTION_INSTALL_PACKAGE` with MIME `application/vnd.android.package-archive`,
  `EXTRA_NOT_UNKNOWN_SOURCE`, `EXTRA_RETURN_RESULT` (so failures come back via `onActivityResult`),
  fallback to `ACTION_VIEW` if no handler. `<queries>` declares the intent for Android 11+ visibility.
* **Permission**: `REQUEST_INSTALL_PACKAGES` is declared (minSdk 26 → the per-app "Install unknown
  apps" model applies on every supported version). `PackageManager.canRequestPackageInstalls()` is
  checked before every launch; if false the settings screen for this app is opened – never a crash,
  with fallbacks to the app-details page / security settings. On return the state flips back to
  READY_TO_INSTALL automatically once the toggle is on.
* **Preflight** before launching (JVM-tested): file exists & non-empty; `PackageManager.getPackageArchiveInfo`
  parses it; package name **must** be `com.agi.assistant`; `versionCode` **must be higher** than the
  installed one (otherwise Android rejects it – blocked with `NOT_NEWER`, file discarded).
* **States**: `ReadyToInstall` (READY_TO_INSTALL) → `InstallerLaunched` (INSTALLER_LAUNCHED) →
  system installs (app restarts as new version) / `InstallationError` (INSTALLATION_ERROR, with
  `InstallError` reason: PERMISSION_REQUIRED, FILE_MISSING, PACKAGE_MISMATCH, NOT_NEWER, INVALID_APK,
  NO_INSTALLER, USER_CANCELLED, SIGNATURE_MISMATCH, PACKAGE_CONFLICT, INCOMPATIBLE,
  INSUFFICIENT_STORAGE, INSTALL_FAILED, UNKNOWN). `PackageManager.INSTALL_FAILED_*` codes from
  `EXTRA_INSTALL_RESULT` are mapped to friendly messages ("App not installed", conflict, incompatible,
  signature mismatch, storage). Coming back without a result (user cancelled) re-verifies the file and
  returns to READY_TO_INSTALL so INSTALL can be pressed again.
* **Honesty**: the app never says "installed". While the installer is open the UI says "Waiting for
  Android to finish installing…". Success is only recognised on the next start, when the installed
  `versionCode`/name matches the staged release (`SecureSettings.stagedUpdate` + `reconcileInstalled`),
  at which point the staged APK is deleted.
* **Never**: silent install, uninstall-before-install, or touching the installed package ourselves.
* Tests: `InstallFlowTest` (43 checks).

### Signing key – read before publishing a release
Android only updates an app in place when the new APK is signed with the **same key** as the installed
one (and has the same `applicationId` and a higher `versionCode`). If a release is signed with a
different key, the installer fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`; the app surfaces this as
"signed with a different key" and the user would have to uninstall (losing data). Therefore:

1. Keep **one** release keystore for the lifetime of the app; back it up securely (loss = no more updates).
   The key in use since 0.2.0 has certificate SHA-256 `5F:25:0D:82:…:F3:29:8E:8C` (full value in docs/BUILD.md).
2. Build every release with the **Release build (signed)** GitHub Actions workflow (secrets-based signing, fingerprint pinned); local `scripts/build_apk.sh release` remains possible with the same key via `.signing.env`.
3. Bump `versionCode` (and `versionName`) in `app/build.gradle.kts` for every release; the checker,
   the preflight and Android itself all require it to increase.
4. Never publish a debug-signed APK as a release – installs over a release build will fail.
5. Publish the SHA-256 (`sha256: <hex>` in the notes or a `SHA256SUMS` asset) so downloads are verified.

## Phase 5 (implemented) – automatic checking

* **When**: `MainActivity.onStart` (app launch and every return to the foreground) calls
  `AssistantApp.autoCheckForUpdates()`. Nothing runs on the main thread – the request is an IO coroutine
  in the app scope; chat, voice, accessibility, notification listener, device tools and AI providers are
  untouched (they share no code path with the checker).
* **Cooldown** (`core/update/AutoCheckPolicy.kt`): at most one automatic GitHub request per **6 h**,
  persisted in `SecureSettings.lastCheckedAt` (survives restarts); plus an in-process 60 s guard so
  Activity recreation / rapid navigation never issues bursts; never while checking, downloading or
  installing. Failed checks also stamp the time, so errors do not cause retries every launch.
* **Offline**: `ConnectivityManager` says no internet → no request, no state change, no message. A
  failing *automatic* check keeps the previous state (no error banner); a failing *manual* check shows
  the error because the user asked.
* **Stored locally**: last check time, last seen release tag, last prompted tag + time, postponement.
* **No repeated dialogs**: once per process per tag (existing), and across restarts the same
  non-mandatory release is not re-prompted automatically within 24 h of the last dialog
  (`lastPromptedTag/At`). "Later" snoozes 24 h. Mandatory releases always prompt. The banner and
  Settings status still show the available update.
* **Manual** Settings › *Check for updates* always requests immediately (no cooldown) and resets the
  prompt limits.
* Tests: `AutoCheckTest` (30 checks).

## Later ideas
Background download via WorkManager-equivalent, delta updates, changelog history screen.

## Release checklist (used for 0.2.0)
1. Bump `versionCode` (+1) and `versionName` in `app/build.gradle.kts` – never change `applicationId`.
2. `scripts/run_tests.sh` → all suites green; `scripts/build_apk.sh release` (zero compiler warnings).
3. Verify: `aapt2 dump badging dist/agi-assistant-<v>-release.apk` shows `com.agi.assistant`, the new
   versionName and a higher versionCode; `zipalign -c` passes; signer certificate fingerprint matches
   the previous release (see BUILD.md – same key or Android refuses the update).
4. `dist/SHA256SUMS` + `dist/<apk>.sha256` regenerated; `dist/RELEASE_NOTES_<v>.md` contains
   `versionCode: N` and `sha256: <hex>` markers (the checker reads both from the release body).
5. Commit `release: prepare AGI Assistant <v>`, tag `v<v>`, create the GitHub Release with the APK,
   the `.sha256` file and the notes as body. The tag **must** be `v<versionName>`.
