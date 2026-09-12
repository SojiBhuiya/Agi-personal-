# AGI Assistant 0.2.0

versionCode: 2
sha256: <fill in from the CI artifact .sha256 before publishing>

## What's New
- In-app updates from GitHub Releases: automatic check on start (6 h cooldown, silent offline), update dialog with What's New
- Secure APK download to app storage with progress, resume, retry and SHA-256 verification
- One-tap install through the Android package installer (handles "Install unknown apps" permission)
- Manual "Check for updates" in Settings › Updates

## Signing
Signed with the persistent AGI Assistant release key (certificate SHA-256 `5F:25:0D:82:3B:07:65:71:67:CF:7F:F9:41:E1:BB:5E:37:0C:D7:A9:5C:0E:E5:A9:D4:55:12:F2:F3:29:8E:8C`). Builds installed from the earlier debug-signed 0.1.0 APK must be uninstalled once before installing this release; all future releases update in place.

## Install / update
- Fresh install: download `agi-assistant-0.2.0-release.apk`, open it, allow "Install unknown apps".
- Existing users: the app will offer this release automatically; later releases install from inside the app.

Asset: agi-assistant-0.2.0-release.apk – take the file and its SHA-256 from the "Release build (signed)" workflow artifact; update the `sha256:` line above before publishing.
