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
KEYSTORE="${KEYSTORE:-$TOOLCHAIN/keys/debug.keystore}"
KEYSTORE_PASS="${KEYSTORE_PASS:-android}"
MIN_API=26

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
if command -v apksigner >/dev/null 2>&1; then
  apksigner sign --ks "$KEYSTORE" --ks-pass "pass:$KEYSTORE_PASS" --out "$APK" "$OUT/aligned.apk"
else
  (cd "$TOOLCHAIN/signer" && node sign.mjs "$OUT/aligned.apk" "$APK" "$KEYSTORE" "$KEYSTORE_PASS")
fi
LD_LIBRARY_PATH="$(dirname "$ZIPALIGN")/lib64:${LD_LIBRARY_PATH:-}" "$ZIPALIGN" -c -p 4 "$APK" >/dev/null && echo "    alignment OK"
"$AAPT2" dump badging "$APK" | head -2
(cd "$DIST" && sha256sum ./*.apk > SHA256SUMS && grep "$(basename "$APK")" SHA256SUMS)
echo "==> Done: $APK ($(du -h "$APK" | cut -f1))"
