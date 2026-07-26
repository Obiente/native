#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" -ne 2 ]]; then
    printf 'Usage: %s MAIN_HISTORY_SEQUENCE CHANNEL\n' "$0" >&2
    exit 2
fi

source_sequence="$1"
channel="$2"

[[ "$source_sequence" =~ ^[1-9][0-9]*$ ]]

case "$channel" in
    nightly) lane=1 ;;
    alpha) lane=2 ;;
    beta) lane=3 ;;
    rc) lane=4 ;;
    *)
        printf 'Unsupported Android update channel: %s\n' "$channel" >&2
        exit 2
        ;;
esac

# All installable channels derive their code from the full main-history count
# reachable from the immutable source commit. Channel lanes order builds from
# the same source while leaving room for future channels.
version_code=$((20000000 + 10#$source_sequence * 10 + lane))
if (( version_code > 2100000000 )); then
    printf 'Main history exceeds the Android version-code allocation.\n' >&2
    exit 1
fi

printf '%s\n' "$version_code"
