#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" -ne 7 ]]; then
    printf 'Usage: %s OUTPUT VERSION VERSION_CODE APK_NAME APK_SIZE APK_SHA256 SIGNER_DIGESTS_JSON\n' "$0" >&2
    exit 2
fi

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
version="$2"

"$project_root/tools/create-android-update-manifest.sh" \
    "$1" \
    "prerelease-v1" \
    "v${version}" \
    "$version" \
    "$3" \
    "$4" \
    "$5" \
    "$6" \
    "$7"
