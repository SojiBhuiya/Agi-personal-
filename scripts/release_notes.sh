#!/usr/bin/env bash
# Generates the GitHub Release body for one version from CHANGELOG.md plus the machine-readable
# markers the in-app updater (core/update/GitHubReleaseParser.kt) understands:
#   versionCode: <N>            -> lets the app compare builds with the same versionName
#   <sha256>  <apk file name>   -> sha256sum format; the app verifies the downloaded APK against it
# Usage: release_notes.sh <versionName> <versionCode> <apkFileName> <sha256hex> [changelog=CHANGELOG.md]
set -euo pipefail
VN="$1"; VC="$2"; APK="$3"; SHA="$4"; CL="${5:-CHANGELOG.md}"
[[ "$SHA" =~ ^[0-9a-fA-F]{64}$ ]] || { echo "release_notes.sh: bad sha256 '$SHA'" >&2; exit 1; }
[[ "$VC" =~ ^[0-9]+$ ]] || { echo "release_notes.sh: bad versionCode '$VC'" >&2; exit 1; }

# Section for this version: from "## <VN>" heading up to the next "## " heading.
section=""
if [ -f "$CL" ]; then
  section=$(awk -v v="$VN" '
    /^## /{ if (on) exit; on = ($0 ~ ("^## " v "([^0-9.]|$)")) ; next }
    on { print }' "$CL" | sed -e '/./,$!d')
fi
[ -n "$section" ] || section="- Release $VN (build $VC). See CHANGELOG.md for details."

cat <<NOTES
## AGI Assistant $VN

### What's New
$section

### Install
Download **$APK** below and open it on your phone (Android 8.0+). If AGI Assistant is already installed,
Settings → *Check for updates* downloads and verifies this file automatically. The APK is signed with the
permanent AGI Assistant release key; Android refuses to install a differently signed build.

### Verification
\`\`\`
$(printf '%s  %s' "${SHA,,}" "$APK")
\`\`\`

<!-- machine-readable, used by the in-app updater; keep these lines -->
versionName: $VN
versionCode: $VC
sha256: ${SHA,,}
NOTES
