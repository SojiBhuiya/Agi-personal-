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
This is how `dist/agi-assistant-0.1.0-debug.apk` was produced, in an
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
`dist/*.apk` is signed with a **debug** key (`CN=Android Debug`). For Play or
long-term sideloading generate your own keystore:

```bash
keytool -genkeypair -v -keystore release.keystore -alias agi -keyalg RSA -keysize 2048 -validity 10000
KEYSTORE=release.keystore KEYSTORE_PASS=... scripts/build_apk.sh release
```

**Signing-key consistency:** the in-app updater (docs/UPDATES.md) can only update in place when the
new APK is signed with the *same* key as the installed one. Use one release keystore forever, keep it
backed up, and bump `versionCode` for each release. Different key ⇒ Android rejects the update
(`INSTALL_FAILED_UPDATE_INCOMPATIBLE`) and the user would have to uninstall, losing their data.

## Installing on a phone
1. Copy the APK to the phone (USB, Drive, Telegram "Saved Messages"…).
2. Open it; allow "Install unknown apps" for the app you opened it from.
3. Launch **AGI Assistant** → tap the shield icon → grant Microphone,
   Accessibility (screen control) and any other capability you want.
4. (Optional) Settings ⚙ → pick a preset (e.g. Groq free tier) → paste your
   key → *Test connection* → Save.

`adb install -r dist/agi-assistant-0.1.0-debug.apk` also works.
