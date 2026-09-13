# Changelog

## 0.2.0 (versionCode 2) – 2026-09-12
- GitHub Release update checker (SemVer + versionCode comparison, asset selection, HTTPS only)
- Non-intrusive update UI: dialog with What's New, Later/snooze, mandatory releases, Settings › Updates card
- APK downloader: app-private storage, progress, Range resume, retry, SHA-256 verification, cleanup
- Android installer flow: FileProvider content:// URI, REQUEST_INSTALL_PACKAGES, unknown-apps permission handling, error mapping
- Release builds now signed with the persistent release key (see docs/BUILD.md)
- Automatic update checking on start/foreground with persisted 6 h cooldown, offline silence, re-prompt suppression
- Online AI brain: OpenAI-compatible + Gemini providers with config validation, classified errors, API-key redaction, real Test connection
- Volume control: absolute/relative percent in English and Bangla (Bengali numerals), read-back results
- CI publishes signed builds as GitHub Releases (APK + SHA-256 + notes) – the in-app update channel

## 0.1.0 (versionCode 1)
- Initial release: provider-independent assistant, voice, accessibility tools, device tools
