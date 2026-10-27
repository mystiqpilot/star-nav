#!/usr/bin/env bash
# Prints the GitHub release text for one version, in English and Ukrainian: which file to download, the version's
# section of CHANGELOG.md and of CHANGELOG.uk.md, the signing certificate and the checksums link. Used by release.yml.
# Usage: .github/release-notes.sh VERSION CERT_SHA256
set -euo pipefail
v="$1"
cert="$2"
repo="${GITHUB_SERVER_URL:-https://github.com}/${GITHUB_REPOSITORY:?GITHUB_REPOSITORY not set}"

# The version's section of one changelog, its headings one level down (they sit under "What's new"). GitHub shows
# every newline of a release text as a line break, so hard-wrapped lines are joined: a line that does not start a
# heading, list item or table row continues the one before.
section() {
  local s
  s="$(awk -v head="## [$v]" '
    index($0, head) == 1 { on = 1; next }
    on && /^## \[/ { exit }
    on { print }
  ' "$1" | sed '/./,$!d' | awk '
    /^[[:space:]]*$/ { if (buf != "") print buf; print ""; buf = ""; next }
    buf != "" && !/^[[:space:]]*([#|>]|[-*+] |[0-9]+\. )/ && buf !~ /^#/ { sub(/^[[:space:]]+/, ""); buf = buf " " $0; next }
    { if (buf != "") print buf; buf = $0 }
    END { if (buf != "") print buf }
  ' | sed 's/^#/##/')"
  test -n "$s" || { echo "$1 has no [$v] section" >&2; exit 1; }
  printf '%s\n' "$s"
}
en="$(section CHANGELOG.md)"
uk="$(section CHANGELOG.uk.md)"

# A table row: what the phone is, then the play and foss APK for its ABI as download links.
dl="$repo/releases/download/v$v"
apk() { printf '[star-nav-%s-%s-%s.apk](%s/star-nav-%s-%s-%s.apk)' "$v" "$1" "$2" "$dl" "$v" "$1" "$2"; }
row() { printf '| %s | %s | %s |' "$1" "$(apk play "$2")" "$(apk foss "$2")"; }

cat <<EOF
## English

Star Nav $v for Android 8.0 and newer. What the app does and how to set it up: [README]($repo/blob/v$v/README.md#readme).

### Which file

| Phone | With Google Play services | Without Google |
|---|---|---|
$(row "Most phones (64-bit)" arm64-v8a)
$(row "Older 32-bit phones" armeabi-v7a)
$(row "Any phone, if unsure (larger download)" universal)
$(row "Emulators and x86 devices" x86_64)

The build with Google Play services also puts their fused location into mock mode; the one without has no Google library. Every file is signed with the same key. A build from another source (Google Play, F-Droid, your own) has another key: to switch, export the sessions you want to keep, uninstall, then install this one.

Signing certificate SHA-256:

\`\`\`text
$cert
\`\`\`

Checksums of every file: [SHA256SUMS]($dl/SHA256SUMS).

### What's new in $v

$en

All versions: [CHANGELOG.md]($repo/blob/v$v/CHANGELOG.md).

## Українська

Star Nav $v для Android 8.0 і новіших. Що робить застосунок і як його налаштувати: [README українською]($repo/blob/v$v/README.uk.md#readme).

### Який файл

| Смартфон | Із сервісами Google Play | Без Google |
|---|---|---|
$(row "Більшість смартфонів (64-біт)" arm64-v8a)
$(row "Старіші 32-бітні смартфони" armeabi-v7a)
$(row "Будь-який смартфон, якщо не впевнені (більший файл)" universal)
$(row "Емулятори й пристрої x86" x86_64)

Збірка із сервісами Google Play також переводить їхнє «злите» (fused) місцезнаходження у фіктивний режим; збірка без Google не має жодних бібліотек Google. Усі файли підписані одним ключем. Збірка з іншого джерела (Google Play, F-Droid, власна) має інший ключ: щоб перейти, експортуйте потрібні сесії, видаліть застосунок і встановіть цю.

Сертифікат підпису SHA-256:

\`\`\`text
$cert
\`\`\`

Контрольні суми всіх файлів: [SHA256SUMS]($dl/SHA256SUMS).

### Що нового в $v

$uk

Усі версії: [CHANGELOG.uk.md]($repo/blob/v$v/CHANGELOG.uk.md) (до 0.5.5 — англійською в [CHANGELOG.md]($repo/blob/v$v/CHANGELOG.md)).
EOF
