#!/usr/bin/env bash
# Prints the GitHub release text for one version, in English and Ukrainian: which file to download, the version's
# section of CHANGELOG.md and of CHANGELOG.uk.md, and the signing certificate. Used by release.yml.
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

cat <<EOF
## English

Star Nav $v for Android 8.0 and newer. What the app does and how to set it up: [README]($repo/blob/v$v/README.md#readme).

### Which file

| File | For |
|---|---|
| \`star-nav-$v-play-arm64-v8a.apk\` | most phones (64-bit); also puts Google Play services' fused location into mock mode |
| \`star-nav-$v-foss-arm64-v8a.apk\` | the same phones, without any Google library |
| \`*-armeabi-v7a.apk\` | older 32-bit phones |
| \`*-universal.apk\` | any phone, if unsure (larger download) |
| \`*-x86_64.apk\` | emulators and x86 devices |

Every file is signed with the same key. A build from another source (Google Play, F-Droid, your own) has another key: to switch, export the sessions you want to keep, uninstall, then install this one.

### What's new in $v

$en

All versions: [CHANGELOG.md]($repo/blob/v$v/CHANGELOG.md).

## Українська

Star Nav $v для Android 8.0 і новіших. Що робить застосунок і як його налаштувати: [README українською]($repo/blob/v$v/README.uk.md#readme).

### Який файл

| Файл | Для чого |
|---|---|
| \`star-nav-$v-play-arm64-v8a.apk\` | більшість смартфонів (64-біт); також переводить «злите» (fused) місцезнаходження Google Play services у фіктивний режим |
| \`star-nav-$v-foss-arm64-v8a.apk\` | ті самі смартфони, без жодних бібліотек Google |
| \`*-armeabi-v7a.apk\` | старіші 32-бітні смартфони |
| \`*-universal.apk\` | будь-який смартфон, якщо не впевнені (більший файл) |
| \`*-x86_64.apk\` | емулятори й пристрої x86 |

Усі файли підписані одним ключем. Збірка з іншого джерела (Google Play, F-Droid, власна) має інший ключ: щоб перейти, експортуйте потрібні сесії, видаліть застосунок і встановіть цю.

### Що нового в $v

$uk

Усі версії: [CHANGELOG.uk.md]($repo/blob/v$v/CHANGELOG.uk.md) (до 0.5.5 — англійською в [CHANGELOG.md]($repo/blob/v$v/CHANGELOG.md)).

---

Signing certificate SHA-256 / Сертифікат підпису SHA-256: \`$cert\`. Checksums / контрольні суми: \`SHA256SUMS\`.
EOF
