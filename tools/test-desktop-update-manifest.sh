#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "$0")/.." && pwd)"
temporary="$(mktemp -d)"
trap 'rm -r -- "$temporary"' EXIT
tag="nightly-20260731-1543-run358-02200472"

printf 'rpm fixture' >"$temporary/nextcloudnative-1.0.2921-1.x86_64.rpm"
printf 'deb fixture' >"$temporary/nextcloudnative_1.0.2921_amd64.deb"
printf 'msi fixture' >"$temporary/NextcloudNative-1.0.2921.msi"
printf 'dmg fixture' >"$temporary/NextcloudNative-1.0.2921.dmg"

"$project_root/tools/has-direct-linux-update-assets.sh" "$temporary"
"$project_root/tools/has-direct-desktop-update-assets.sh" "$temporary"

GITHUB_REPOSITORY=Obiente/nc-native \
    "$project_root/tools/create-desktop-update-manifest.sh" \
    "$temporary/desktop-update-manifest.json" \
    nightly-v1 \
    "$tag" \
    "$tag" \
    20002921 \
    1.0.2921 \
    "$temporary"

"$project_root/tools/verify-desktop-update-manifest-assets.sh" \
    "$temporary/desktop-update-manifest.json" \
    "$temporary" \
    Obiente/nc-native \
    "$tag" \
    nightly-v1 \
    "$tag" \
    20002921 \
    1.0.2921

expanded_manifest="$temporary/desktop-update-manifest-expanded.json"
jq '.assets[0].futureField = "unsupported"' \
    "$temporary/desktop-update-manifest.json" >"$expanded_manifest"
if "$project_root/tools/verify-desktop-update-manifest-assets.sh" \
    "$expanded_manifest" \
    "$temporary" \
    Obiente/nc-native \
    "$tag" \
    nightly-v1 \
    "$tag" \
    20002921 \
    1.0.2921 >/dev/null 2>&1; then
    echo "A desktop asset with an unknown field passed manifest verification." >&2
    exit 1
fi

jq -e '
  keys == [
    "assets", "channel", "packageVersion", "releaseNotesUrl",
    "schemaVersion", "versionCode", "versionName"
  ] and
  ([.assets[] | .platform] | sort) == ["linux","linux","windows"] and
  ([.assets[] | select(.platform == "linux") | .format] | sort) == ["deb","rpm"]
' "$temporary/desktop-update-manifest.json" >/dev/null

non_linux="$temporary/non-linux"
mkdir "$non_linux"
printf 'msi fixture' >"$non_linux/NextcloudNative-1.0.2921.msi"
printf 'dmg fixture' >"$non_linux/NextcloudNative-1.0.2921.dmg"
if ! "$project_root/tools/has-direct-desktop-update-assets.sh" "$non_linux"; then
    echo "A Windows MSI must advance the direct desktop update channel." >&2
    exit 1
fi
GITHUB_REPOSITORY=Obiente/nc-native \
    "$project_root/tools/create-desktop-update-manifest.sh" \
    "$non_linux/desktop-update-manifest.json" \
    nightly-v1 \
    "$tag" \
    "$tag" \
    20002921 \
    1.0.2921 \
    "$non_linux"
jq -e '
  keys == [
    "assets", "channel", "packageVersion", "releaseNotesUrl",
    "schemaVersion", "versionCode", "versionName"
  ] and
  ([.assets[] | .platform] | unique) == ["windows"]
' \
    "$non_linux/desktop-update-manifest.json" >/dev/null

mac_only="$temporary/mac-only"
mkdir "$mac_only"
printf 'dmg fixture' >"$mac_only/NextcloudNative-1.0.2921.dmg"
if "$project_root/tools/has-direct-desktop-update-assets.sh" "$mac_only"; then
    echo "A distribution-managed macOS package must not advance the direct update channel." >&2
    exit 1
fi

printf 'Desktop update manifest generation checks passed.\n'
