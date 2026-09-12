#!/usr/bin/env bash
# One-time: create the persistent AGI Assistant release keystore OUTSIDE the repository.
# Android only installs updates signed with the same key, so BACK THIS FILE UP. Losing it means
# existing users can never update in place again.
#
#   KEYSTORE=/secure/path/agi-release.jks KEYSTORE_PASS=... scripts/make_release_key.sh
#
# The password is read from the environment (or prompted, hidden) and never printed.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-$ROOT/toolchain/jdk}" PATH="${JAVA_HOME:-$ROOT/toolchain/jdk}/bin:$PATH"
KEYSTORE="${KEYSTORE:?set KEYSTORE to the output path, e.g. ~/.agi-assistant/agi-release.jks}"
KEY_ALIAS="${KEY_ALIAS:-agi-release}"
case "$(cd "$(dirname "$KEYSTORE")" 2>/dev/null && pwd || echo "")" in "$ROOT"*) echo "ERROR: keep the keystore outside the repo ($ROOT)." >&2; exit 1;; esac
[ -f "$KEYSTORE" ] && { echo "ERROR: $KEYSTORE already exists – refusing to overwrite a release key." >&2; exit 1; }
if [ -z "${KEYSTORE_PASS:-}" ]; then read -r -s -p "New keystore password (min 8 chars, hidden): " KEYSTORE_PASS; echo; fi
[ "${#KEYSTORE_PASS}" -ge 8 ] || { echo "ERROR: password too short." >&2; exit 1; }
mkdir -p "$(dirname "$KEYSTORE")"; chmod 700 "$(dirname "$KEYSTORE")"
export KEYSTORE_PASS
# keytool reads the password from env via :env, so it never appears in argv / ps.
keytool -genkeypair -v -keystore "$KEYSTORE" -storetype JKS -alias "$KEY_ALIAS" \
  -keyalg RSA -keysize 4096 -sigalg SHA256withRSA -validity 10950 \
  -storepass:env KEYSTORE_PASS -keypass:env KEYSTORE_PASS \
  -dname "CN=AGI Assistant Release, OU=Release, O=AGI Assistant, C=BD" >/dev/null 2>&1
chmod 600 "$KEYSTORE"
echo "Created $KEYSTORE (alias $KEY_ALIAS)."
echo "Certificate SHA-256 fingerprint (record this in docs/BUILD.md):"
keytool -list -v -keystore "$KEYSTORE" -alias "$KEY_ALIAS" -storepass:env KEYSTORE_PASS 2>/dev/null | grep -m1 "SHA256:" | sed 's/^\s*//'
cat <<MSG

Next: create $ROOT/.signing.env (git-ignored, chmod 600) with:
  KEYSTORE=$KEYSTORE
  KEYSTORE_PASS=<your password>
  KEY_ALIAS=$KEY_ALIAS
and back up $KEYSTORE + the password in a password manager.
MSG
