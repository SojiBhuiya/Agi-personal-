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
  **LATER**. UPDATE currently opens the GitHub release page; Phase 3 replaces it with in-app install.
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

## Phase 3 (next) – download & install
`UpdateState.Downloading(progress)` / `Downloaded(file)` / `Installing`; download the
`apkDownloadUrl` with `DownloadManager` into app-private storage, verify size/SHA-256 when a
`*.apk.sha256` asset exists, then hand the file to the system installer via
`PackageInstaller`/`ACTION_VIEW` with a `FileProvider` URI (requires `REQUEST_INSTALL_PACKAGES`).
