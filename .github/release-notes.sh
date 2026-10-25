#!/usr/bin/env bash
# Prints the GitHub release text for one version: which file to download, the version's
# CHANGELOG.md section and the signing certificate. Used by release.yml.
# Usage: .github/release-notes.sh VERSION CERT_SHA256
set -euo pipefail
v="$1"
cert="$2"
repo="${GITHUB_SERVER_URL:-https://github.com}/${GITHUB_REPOSITORY:?GITHUB_REPOSITORY not set}"

# GitHub shows every newline of a release text as a line break, so the section's hard-wrapped
# lines are joined: a line that does not start a heading, list item or table row continues the one before.
notes="$(awk -v head="## [$v]" '
  index($0, head) == 1 { on = 1; next }
  on && /^## \[/ { exit }
  on { print }
' CHANGELOG.md | sed '/./,$!d' | awk '
  /^[[:space:]]*$/ { if (buf != "") print buf; print ""; buf = ""; next }
  buf != "" && !/^[[:space:]]*([#|>]|[-*+] |[0-9]+\. )/ && buf !~ /^#/ { sub(/^[[:space:]]+/, ""); buf = buf " " $0; next }
  { if (buf != "") print buf; buf = $0 }
  END { if (buf != "") print buf }
')"
test -n "$notes" || { echo "CHANGELOG.md has no [$v] section" >&2; exit 1; }

cat <<EOF
Star Nav $v for Android 8.0 and newer. What the app does and how to set it up: [README]($repo/blob/v$v/README.md#readme).

## Which file

| File | For |
|---|---|
| \`star-nav-$v-play-arm64-v8a.apk\` | most phones (64-bit); also puts Google Play services' fused location into mock mode |
| \`star-nav-$v-foss-arm64-v8a.apk\` | the same phones, without any Google library |
| \`*-armeabi-v7a.apk\` | older 32-bit phones |
| \`*-universal.apk\` | any phone, if unsure (larger download) |
| \`*-x86_64.apk\` | emulators and x86 devices |

Every file is signed with the same key. A build from another source (Google Play, F-Droid, your own) has another key: to switch, export the sessions you want to keep, uninstall, then install this one.

## What's new in $v

$notes

All versions: [CHANGELOG.md]($repo/blob/v$v/CHANGELOG.md).

Signing certificate SHA-256: \`$cert\`. Checksums of the files: \`SHA256SUMS\`.
EOF
