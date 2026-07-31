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

jq -e '
  ([.assets[] | .platform] | sort) == ["linux","linux","macos","windows"] and
  ([.assets[] | select(.platform == "linux") | .format] | sort) == ["deb","rpm"]
' "$temporary/desktop-update-manifest.json" >/dev/null

printf 'Desktop update manifest generation checks passed.\n'
