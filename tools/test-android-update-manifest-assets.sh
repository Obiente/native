#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
temporary_directory="$(mktemp -d)"
trap 'rm -r -- "$temporary_directory"' EXIT

tag="nightly-20260726-1430-run42-abcdef12"
version_code="$("$project_root/tools/derive-android-version-code.sh" 42 nightly)"
apk="$temporary_directory/nextcloud-native-${tag}-android.apk"
manifest="$temporary_directory/update-manifest.json"
printf 'synthetic signed APK fixture\n' >"$apk"
apk_size="$(stat --format='%s' "$apk")"
apk_sha256="$(sha256sum "$apk" | awk '{print $1}')"

GITHUB_REPOSITORY="Obiente/nc-native" \
    "$project_root/tools/create-android-update-manifest.sh" \
    "$manifest" \
    "nightly-v1" \
    "$tag" \
    "$tag" \
    "$version_code" \
    "$(basename "$apk")" \
    "$apk_size" \
    "$apk_sha256" \
    '["bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"]'

"$project_root/tools/verify-android-update-manifest-assets.sh" \
    "$manifest" \
    "$apk" \
    "Obiente/nc-native" \
    "$tag" \
    "nightly-v1" \
    "$tag" \
    "$version_code"

printf 'changed APK bytes\n' >>"$apk"
if "$project_root/tools/verify-android-update-manifest-assets.sh" \
    "$manifest" \
    "$apk" \
    "Obiente/nc-native" \
    "$tag" \
    "nightly-v1" \
    "$tag" \
    "$version_code" >/dev/null 2>&1; then
    echo "A manifest must not validate against different APK bytes." >&2
    exit 1
fi

printf 'Android update manifest asset-integrity checks passed.\n'
