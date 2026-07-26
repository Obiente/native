#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" -ne 2 ]]; then
    printf 'Usage: %s TRUSTED_CI_RUN_NUMBER CHANNEL\n' "$0" >&2
    exit 2
fi

run_number="$1"
channel="$2"

[[ "$run_number" =~ ^[1-9][0-9]*$ ]]

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

# All installable channels derive their code from the same trusted
# "Build and test" workflow sequence. Channel lanes order builds from the
# same source run while leaving room for future channels.
version_code=$((20000000 + 10#$run_number * 10 + lane))
if (( version_code > 2100000000 )); then
    printf 'Derived Android version code exceeds the platform limit.\n' >&2
    exit 1
fi

printf '%s\n' "$version_code"
