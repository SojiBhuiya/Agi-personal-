#!/usr/bin/env bash
# Assembles the offline Android toolchain used by scripts/build_apk.sh and
# scripts/run_tests.sh from sources reachable without Google's servers
# (npm, PyPI, GitHub). Result: ./toolchain (git-ignored).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TC="$ROOT/toolchain"; TMP="$TC/.dl"
mkdir -p "$TC/keys" "$TC/signer" "$TMP"

echo "==> JDK (jdk4py from PyPI)"
if [ ! -x "$TC/jdk/bin/java" ]; then
  pip download jdk4py --no-deps -d "$TMP" -q
  rm -rf "$TMP/pyjdk"; unzip -q -o "$TMP"/jdk4py-*.whl -d "$TMP/pyjdk"
  rm -rf "$TC/jdk"; mv "$TMP/pyjdk/jdk4py/java-runtime" "$TC/jdk"
fi
export JAVA_HOME="$TC/jdk" PATH="$TC/jdk/bin:$PATH"

echo "==> Kotlin compiler (npm kotlin-compiler)"
if [ ! -x "$TC/kotlinc/bin/kotlinc" ]; then
  npm pack kotlin-compiler --pack-destination "$TMP" -q >/dev/null
  rm -rf "$TMP/k"; mkdir -p "$TMP/k"; tar xzf "$TMP"/kotlin-compiler-*.tgz -C "$TMP/k"
  rm -rf "$TC/kotlinc"; mv "$TMP/k/package" "$TC/kotlinc"; chmod +x "$TC/kotlinc/bin/"*
fi

echo "==> aapt2 (npm aaptjs3)"
if [ ! -x "$TC/aapt2" ]; then
  npm pack aaptjs3 --pack-destination "$TMP" -q >/dev/null
  rm -rf "$TMP/a"; mkdir -p "$TMP/a"; tar xzf "$TMP"/aaptjs3-*.tgz -C "$TMP/a"
  cp "$TMP/a/package/bin/x64/linux/aapt2" "$TC/aapt2"; chmod +x "$TC/aapt2"
fi

echo "==> r8.jar (LineageOS android_prebuilts_r8)"
if [ ! -f "$TC/r8.jar" ]; then
  sha=$(gh api "repos/LineageOS/android_prebuilts_r8/contents?ref=lineage-17.1" --jq '.[] | select(.name=="r8-master.jar") | .sha')
  gh api -H "Accept: application/vnd.github.raw" "repos/LineageOS/android_prebuilts_r8/git/blobs/$sha" > "$TC/r8.jar"
fi

echo "==> zipalign (AOSP prebuilt build-tools)"
if [ ! -x "$TC/zipalign" ]; then
  R=KiTTYsh/android_prebuilts_build-tools_linux-x86
  sha=$(gh api "repos/$R/contents/bin" --jq '.[] | select(.name=="zipalign") | .sha')
  gh api -H "Accept: application/vnd.github.raw" "repos/$R/git/blobs/$sha" > "$TC/zipalign"; chmod +x "$TC/zipalign"
  mkdir -p "$TC/lib64"
  for l in libc++.so libbase.so liblog.so libziparchive.so libz-host.so; do
    sha=$(gh api "repos/$R/contents/lib64" --jq ".[] | select(.name==\"$l\") | .sha")
    gh api -H "Accept: application/vnd.github.raw" "repos/$R/git/blobs/$sha" > "$TC/lib64/$l"
  done
fi

echo "==> android.jar API 34 (Sable/android-platforms)"
if [ ! -f "$TC/android.jar" ]; then
  rm -rf "$TMP/ap"; git clone -q --depth 1 --filter=blob:none --sparse https://github.com/Sable/android-platforms "$TMP/ap"
  (cd "$TMP/ap" && git sparse-checkout set --no-cone '/android-34/*' >/dev/null)
  cp "$TMP/ap/android-34/android.jar" "$TC/android.jar"
fi

echo "==> org.json for JVM tests"
if [ ! -f "$TC/json.jar" ]; then
  rm -rf "$TMP/gs"; git clone -q --depth 1 --filter=blob:none --sparse https://github.com/PAVAN-BUSANAMONi/garage-services "$TMP/gs"
  (cd "$TMP/gs" && git sparse-checkout set --no-cone '/**/json-20230227.jar' >/dev/null)
  cp "$(find "$TMP/gs" -name 'json-20230227.jar' | head -1)" "$TC/json.jar"
fi

echo "==> APK signer (npm apk_sign_ts)"
if [ ! -d "$TC/signer/node_modules/apk_sign_ts" ]; then
  (cd "$TC/signer" && npm init -y >/dev/null && npm install apk_sign_ts --silent)
  # Patch: 4-byte align the v1 signature entries so zipalign -c passes.
  python3 - "$TC/signer/node_modules/apk_sign_ts/dist/V1Signer.js" <<'EOF'
import sys
p=sys.argv[1]; s=open(p).read()
old="""            const local = createLocalFileHeader(name, data, crc);
            localBuffers.push(local, data);
            cdBuffers.push(createCentralDirectoryHeader(name, data, crc, cd.offset + newLocalSize));"""
new="""            const baseOff = cd.offset + newLocalSize;
            const dataStart = baseOff + 30 + name.length;
            const pad = (4 - (dataStart % 4)) % 4;
            const extra = pad ? new Uint8Array(pad) : undefined;
            const local = createLocalFileHeader(name, data, crc, 0, extra);
            localBuffers.push(local, data);
            cdBuffers.push(createCentralDirectoryHeader(name, data, crc, baseOff));"""
if old in s: open(p,'w').write(s.replace(old,new))
EOF
fi
cat > "$TC/signer/sign.mjs" <<'EOF'
import { readFileSync, writeFileSync } from 'node:fs';
import { ApkSigner, SigningKey, parseKeystore } from 'apk_sign_ts';
// Usage: node sign.mjs <in.apk> <out.apk> <keystore>
// Secrets come ONLY from the environment (never argv, so they never show up in `ps` or logs):
//   KEYSTORE_PASS  store password (required)
//   KEY_ALIAS      alias to sign with (optional: first entry)
const [,, inApk, outApk, ks] = process.argv;
const pass = process.env.KEYSTORE_PASS;
if (!pass) { console.error('KEYSTORE_PASS not set'); process.exit(2); }
const apk = new Uint8Array(readFileSync(inApk));
const { privateKey, certificate, alias } = await parseKeystore(new Uint8Array(readFileSync(ks)), pass, process.env.KEY_ALIAS || undefined);
const signer = new ApkSigner({ signingKey: SigningKey.fromPEM(privateKey, certificate) });
const { signedApk } = await signer.sign(apk);
writeFileSync(outApk, signedApk);
console.log('signed', outApk, signedApk.length, 'alias', alias);
EOF

echo "==> debug keystore"
[ -f "$TC/keys/debug.keystore" ] || keytool -genkeypair -v -keystore "$TC/keys/debug.keystore" -storepass android -keypass android \
  -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1

echo "==> versions"
java -version 2>&1 | head -1; "$TC/kotlinc/bin/kotlinc" -version 2>&1 | tail -1; "$TC/aapt2" version
java -cp "$TC/r8.jar" com.android.tools.r8.D8 --version | head -1
echo "Toolchain ready at $TC"
