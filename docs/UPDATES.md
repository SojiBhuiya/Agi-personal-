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

## Phase 4 (next) – install
Hand the staged file to the system installer (`PackageInstaller` / `ACTION_VIEW` with a `FileProvider`
URI, `REQUEST_INSTALL_PACKAGES`), handle the "unknown sources" permission flow, and remove the staged
APK once the installed `versionCode` matches.
