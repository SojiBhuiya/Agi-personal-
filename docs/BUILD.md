# Building

## Option A – Android Studio / Gradle (recommended for development)
Open the project in Android Studio (Hedgehog or newer) and press Run, or:

```bash
./gradlew assembleDebug            # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease          # configure signing in app/build.gradle.kts first
```

The only dependency is `kotlinx-coroutines-android`; everything else is the
Android framework. compileSdk 34, minSdk 26, targetSdk 34, Kotlin 2.0.

> The Gradle wrapper JAR is not committed (binary); run `gradle wrapper` once
> or let Android Studio generate it.

## Option B – Offline toolchain (`scripts/build_apk.sh`)
This is how the signed APK from the **Release build (signed)** GitHub Actions artifact was produced, in an
environment where Google's Maven/SDK servers were unreachable. It performs the
same steps Gradle does:

| Step | Tool | Source used |
|------|------|-------------|
| Compile resources / link manifest, generate `R.java` | `aapt2` 8.x | npm `aaptjs3` (prebuilt Linux binary) |
| Compile Kotlin (`-jvm-target 1.8`) | `kotlinc` 2.4 | npm `kotlin-compiler` (JetBrains) |
| Dex | `D8` (r8.jar) | LineageOS `android_prebuilts_r8` |
| Align | `zipalign` | AOSP prebuilt build-tools |
| Sign v1+v2+v3 | `apk_sign_ts` (node) or `apksigner` | npm |
| Framework stubs | `android.jar` API 34 | Sable/android-platforms |
| JDK | Temurin/`jdk4py` 25 | PyPI |

Expected layout (symlinks are fine):

```
toolchain/
  jdk/            android.jar    r8.jar
  kotlinc/        aapt2          zipalign  lib64/
  keys/debug.keystore            signer/sign.mjs + node_modules
```

```bash
TOOLCHAIN=$PWD/toolchain scripts/build_apk.sh debug     # -> dist/agi-assistant-<ver>-debug.apk
TOOLCHAIN=$PWD/toolchain scripts/build_apk.sh release   # unminified release build
scripts/run_tests.sh                                     # JVM tests
```

## Signing

### CI release build (GitHub Actions – the canonical way to sign releases)
`.github/workflows/release.yml` builds and signs the release APK on every push / tag / manual run:

1. Verifies the four repository secrets exist (`AGI_RELEASE_KEYSTORE_BASE64`, `AGI_RELEASE_STORE_PASSWORD`,
   `AGI_RELEASE_KEY_ALIAS`, `AGI_RELEASE_KEY_PASSWORD`) without printing them.
2. Decodes the keystore into `$RUNNER_TEMP` (mode 600) and exports only its **path** as
   `AGI_RELEASE_KEYSTORE_FILE`; `app/build.gradle.kts` reads the path + passwords from the
   environment and creates the `release` signing config (v1+v2+v3). Without those variables the
   release build type stays unsigned, so local builds are unchanged.
3. Runs the JVM suites (`gradle coreTests` + `providerWireTest`), `lintRelease` (report only), then `assembleRelease`.
4. **Verifies** with `apksigner`: v1/v2/v3 all true, exactly one signer, and the signer certificate
   SHA-256 equals the pinned `EXPECTED_CERT_SHA256` – otherwise the job fails. Also checks
   `zipalign`, `package=com.agi.assistant`, and that versionName/versionCode match `build.gradle.kts`.
5. Uploads `agi-assistant-<version>-release.apk` + `.sha256` as a workflow artifact (no GitHub
   Release is created – publishing stays a manual step).
6. Shreds the temporary keystore (`if: always()`).

To rotate the pin after a (deliberate) key change, update `EXPECTED_CERT_SHA256` in the workflow
and the table below in the same commit.


### Release signing (persistent key – REQUIRED for every release)
Android only updates an app in place when the new APK is signed with the **same key** as the installed
one. AGI Assistant 0.2.0 and every later release are signed with the persistent release key, which lives **only** in the owner's backup and in GitHub Actions secrets (base64):

| | |
|---|---|
| Alias | `agi-release` |
| Key | generated and held by the project owner; only the certificate fingerprint is public |
| History | Two earlier keys (`D7:0A:77:…`, `6C:E0:19:…`) were generated in an ephemeral sandbox on 2026-09-12 and lost before anything signed with them was published; both are void. |
| Certificate SHA-256 | `5F:25:0D:82:3B:07:65:71:67:CF:7F:F9:41:E1:BB:5E:37:0C:D7:A9:5C:0E:E5:A9:D4:55:12:F2:F3:29:8E:8C` |

The keystore file and its password are **not in the repository** (`*.jks`, `*.keystore`,
`.signing.env` are git-ignored) and must be backed up privately (password manager + offline copy).
Losing the key means existing installs can never be updated in place again.

Configuration is environment-only – nothing is passed on a command line or printed:

```bash
# one time, outside the repo (the script refuses paths inside it and never overwrites):
KEYSTORE=~/.agi-assistant-signing/agi-release.jks KEYSTORE_PASS='…' scripts/make_release_key.sh

# per machine: .signing.env at the repo root (git-ignored, chmod 600) – build_apk.sh sources it for release builds
KEYSTORE=/abs/path/agi-release.jks
KEYSTORE_PASS=…
KEY_ALIAS=agi-release

scripts/build_apk.sh release      # refuses to run without the release key; prints the signer fingerprint
```

CI: provide `KEYSTORE_PASS` as a secret and the keystore as a base64 secret decoded to a temp file;
never echo them. `build_apk.sh` passes the password to apksigner via `env:` and to the offline
signer via the environment only. The debug key is rejected for release builds unless
`ALLOW_DEBUG_KEY_RELEASE=1` (only for local experiments – never publish such a build).

After every release build compare the printed `signer sha256 Fingerprint=` with the table above.

### Debug signing
`scripts/build_apk.sh debug` uses a throw-away key generated by `setup_toolchain.sh`
(`toolchain/keys/debug.keystore`, CN=Android Debug). Debug builds cannot update over release builds
and vice versa.

## Installing on a phone
1. Copy the APK to the phone (USB, Drive, Telegram "Saved Messages"…).
2. Open it; allow "Install unknown apps" for the app you opened it from.
3. Launch **AGI Assistant** → tap the shield icon → grant Microphone,
   Accessibility (screen control) and any other capability you want.
4. (Optional) Settings ⚙ → pick a preset (e.g. Groq free tier) → paste your
   key → *Test connection* → Save.

`adb install -r dist/agi-assistant-0.2.0-release.apk` also works.
