#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
parser="$repo_root/tools/extract-apksigner-certificate-sha256.sh"
expected='0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef'

old_format="$(
    printf 'Signer #1 certificate SHA-256 digest: %s\n' "$expected" |
        "$parser"
)"
new_format="$(
    printf 'V2 Signer: certificate SHA-256 digest: %s\n' "$expected" |
        "$parser"
)"

if [[ "$old_format" != "$expected" || "$new_format" != "$expected" ]]; then
    printf 'APK certificate parser changed a valid digest.\n' >&2
    exit 1
fi

if printf 'Verifies\n' | "$parser" >/dev/null 2>&1; then
    printf 'APK certificate parser accepted output without a digest.\n' >&2
    exit 1
fi

if printf 'Signer #1 certificate SHA-256 digest: invalid\n' |
    "$parser" >/dev/null 2>&1; then
    printf 'APK certificate parser accepted a malformed digest.\n' >&2
    exit 1
fi

printf 'APK certificate parser tests passed.\n'
