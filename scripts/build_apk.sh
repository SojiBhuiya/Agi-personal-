#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Offline / Gradle-free APK build for AGI Assistant.
#
# The project is a normal Gradle Android project (open it in Android Studio and
# run `./gradlew assembleDebug`). This script is an *alternative* pipeline that
# only needs: a JDK (17+), the Kotlin command-line compiler, aapt2, d8/r8,
# zipalign, android.jar and an APK signer. It is what produced dist/*.apk.
#
# Usage:
#   TOOLCHAIN=/path/to/toolchain scripts/build_apk.sh [debug|release]
#
# Expected toolchain layout (see docs/BUILD.md):
#   $TOOLCHAIN/jdk/bin/java
#   $TOOLCHAIN/kotlinc/bin/kotlinc  + lib/kotlin-stdlib.jar, lib/kotlinx-coroutines-core-jvm.jar
#   $TOOLCHAIN/aapt2
#   $TOOLCHAIN/r8.jar
#   $TOOLCHAIN/zipalign (+ lib64/ if needed)
#   $TOOLCHAIN/android.jar (API 34)
#   $TOOLCHAIN/signer/sign.mjs (node) or apksigner on PATH
# ---------------------------------------------------------------------------
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODE="${1:-debug}"
TOOLCHAIN="${TOOLCHAIN:-$ROOT/toolchain}"
OUT="$ROOT/app/build-offline"
DIST="$ROOT/dist"
SRC="$ROOT/app/src/main"

JAVA_HOME="${JAVA_HOME:-$TOOLCHAIN/jdk}"
export JAVA_HOME PATH="$JAVA_HOME/bin:$PATH"
KOTLINC="${KOTLINC:-$TOOLCHAIN/kotlinc/bin/kotlinc}"
KLIB="$(dirname "$KOTLINC")/../lib"
AAPT2="${AAPT2:-$TOOLCHAIN/aapt2}"
R8="${R8:-$TOOLCHAIN/r8.jar}"
ZIPALIGN="${ZIPALIGN:-$TOOLCHAIN/zipalign}"
ANDROID_JAR="${ANDROID_JAR:-$TOOLCHAIN/android.jar}"
MIN_API=26

# Signing -----------------------------------------------------------------------
#   debug   : toolchain/keys/debug.keystore (generated, throw-away) unless KEYSTORE is set.
#   release : REQUIRES the persistent release key. Configure via environment only:
#               KEYSTORE=/secure/path/agi-release.jks   (kept OUTSIDE the repo; *.jks/*.keystore are git-ignored)
#               KEYSTORE_PASS=...                        (never passed on a command line, never logged)
#               KEY_ALIAS=agi-release                    (optional; defaults to the keystore's first entry)
#             or put those three lines in $ROOT/.signing.env (git-ignored) – see docs/BUILD.md.
#             Android only updates in place when every release is signed with the SAME key, so a
#             release build with the debug key is refused unless ALLOW_DEBUG_KEY_RELEASE=1.
if [ -f "$ROOT/.signing.env" ] && [ "$MODE" = "release" ]; then set -a; . "$ROOT/.signing.env"; set +a; fi
if [ "$MODE" = "release" ]; then
  if [ -z "${KEYSTORE:-}" ] || [ ! -f "$KEYSTORE" ]; then
    echo "ERROR: release builds need the persistent release keystore: set KEYSTORE (path) and KEYSTORE_PASS," >&2
    echo "       or create $ROOT/.signing.env (see docs/BUILD.md 'Release signing'). Run scripts/make_release_key.sh once." >&2
    exit 1
  fi
  if [ -z "${KEYSTORE_PASS:-}" ]; then echo "ERROR: KEYSTORE_PASS not set (environment only; it is never echoed)." >&2; exit 1; fi
  if [ "$(basename "$KEYSTORE")" = "debug.keystore" ] && [ "${ALLOW_DEBUG_KEY_RELEASE:-0}" != "1" ]; then
    echo "ERROR: refusing to sign a release with the debug key." >&2; exit 1
  fi
else
  KEYSTORE="${KEYSTORE:-$TOOLCHAIN/keys/debug.keystore}"
  KEYSTORE_PASS="${KEYSTORE_PASS:-android}"
fi
export KEYSTORE_PASS KEY_ALIAS="${KEY_ALIAS:-}"

APP_ID="com.agi.assistant"
VERSION_CODE="$(grep -oP 'versionCode = \K\d+' "$ROOT/app/build.gradle.kts")"
VERSION_NAME="$(grep -oP 'versionName = "\K[^"]+' "$ROOT/app/build.gradle.kts")"

echo "==> AGI Assistant $VERSION_NAME ($VERSION_CODE) [$MODE]"
rm -rf "$OUT" && mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" "$DIST"

# 1. Resources -----------------------------------------------------------------
echo "==> aapt2 compile"
"$AAPT2" compile --dir "$SRC/res" -o "$OUT/res.zip"

echo "==> aapt2 link"
# Manifest placeholders that Gradle would normally substitute.
# (Gradle derives the package from `namespace`; aapt2 needs it in the manifest.)
sed -e "s/\${applicationId}/$APP_ID/g" \
    -e "0,/xmlns:tools=\"http:\/\/schemas.android.com\/tools\"/s//xmlns:tools=\"http:\/\/schemas.android.com\/tools\" package=\"$APP_ID\"/" \
    "$SRC/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"
LINK_FLAGS=(--auto-add-overlay --min-sdk-version $MIN_API --target-sdk-version 34
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME"
  --rename-manifest-package "$APP_ID")
[ "$MODE" = "debug" ] && LINK_FLAGS+=(--debug-mode)
"$AAPT2" link -o "$OUT/base.apk" -I "$ANDROID_JAR" --manifest "$OUT/AndroidManifest.xml" \
  --java "$OUT/gen" "${LINK_FLAGS[@]}" "$OUT/res.zip"

# 2. Kotlin ---------------------------------------------------------------------
echo "==> kotlinc"
"$KOTLINC" -jvm-target 1.8 -no-reflect -Xno-call-assertions -Xno-param-assertions -Xno-receiver-assertions \
  -cp "$ANDROID_JAR:$KLIB/kotlinx-coroutines-core-jvm.jar" \
  -d "$OUT/classes" "$SRC/java" "$OUT/gen" 2>&1 | grep -v '^warning:' || true
[ -d "$OUT/classes/com" ] || { echo "kotlinc produced no classes"; exit 1; }

# 3. DEX ------------------------------------------------------------------------
echo "==> d8"
D8_FLAGS=(--min-api $MIN_API --lib "$ANDROID_JAR" --output "$OUT/dex")
[ "$MODE" = "release" ] && D8_FLAGS+=(--release) || D8_FLAGS+=(--debug)
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
java -Xmx1g -cp "$R8" com.android.tools.r8.D8 "${D8_FLAGS[@]}" \
  @"$OUT/classes.txt" "$KLIB/kotlin-stdlib.jar" "$KLIB/kotlinx-coroutines-core-jvm.jar" 2>&1 | grep -v '^Warning' || true
[ -f "$OUT/dex/classes.dex" ] || { echo "d8 failed"; exit 1; }

# 4. Package --------------------------------------------------------------------
echo "==> package"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q -u "$OUT/unsigned.apk" classes*.dex)
LD_LIBRARY_PATH="$(dirname "$ZIPALIGN")/lib64:${LD_LIBRARY_PATH:-}" "$ZIPALIGN" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

# 5. Sign -----------------------------------------------------------------------
echo "==> sign"
APK="$DIST/agi-assistant-$VERSION_NAME-$MODE.apk"
echo "    keystore: $KEYSTORE (alias: ${KEY_ALIAS:-<first>})"
if command -v apksigner >/dev/null 2>&1; then
  apksigner sign --ks "$KEYSTORE" --ks-pass env:KEYSTORE_PASS ${KEY_ALIAS:+--ks-key-alias "$KEY_ALIAS"} --out "$APK" "$OUT/aligned.apk"
else
  (cd "$TOOLCHAIN/signer" && node sign.mjs "$OUT/aligned.apk" "$APK" "$KEYSTORE")
fi
# Print the signer certificate fingerprint so it can be compared with the previous release.
unzip -p "$APK" 'META-INF/*.RSA' 2>/dev/null | openssl pkcs7 -inform DER -print_certs 2>/dev/null | openssl x509 -noout -fingerprint -sha256 2>/dev/null | sed 's/^/    signer /' || true
LD_LIBRARY_PATH="$(dirname "$ZIPALIGN")/lib64:${LD_LIBRARY_PATH:-}" "$ZIPALIGN" -c -p 4 "$APK" >/dev/null && echo "    alignment OK"
"$AAPT2" dump badging "$APK" | head -2
(cd "$DIST" && sha256sum ./*.apk > SHA256SUMS && grep "$(basename "$APK")" SHA256SUMS)
echo "==> Done: $APK ($(du -h "$APK" | cut -f1))"
