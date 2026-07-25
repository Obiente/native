#!/usr/bin/env bash
set -euo pipefail

certificate_sha256="$(
    awk -F': ' \
        '/certificate SHA-256 digest:/ { print tolower($NF); exit }'
)"

if [[ ! "$certificate_sha256" =~ ^[0-9a-f]{64}$ ]]; then
    printf 'No valid APK signing certificate SHA-256 digest was reported.\n' >&2
    exit 1
fi

printf '%s\n' "$certificate_sha256"
