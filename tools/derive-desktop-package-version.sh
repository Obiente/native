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
        printf 'Unsupported desktop package channel: %s\n' "$channel" >&2
        exit 2
        ;;
esac

# MSI constrains the first two components to 0..255 and the third to
# 0..65535. This mapping stays monotonic and is also valid for macOS,
# Debian, and RPM package metadata.
sequence=$((10#$source_sequence * 10 + lane))
if (( sequence > 16777215 )); then
    printf 'Main history exceeds the supported desktop package version range.\n' >&2
    exit 1
fi

minor=$((sequence / 65536))
patch=$((sequence % 65536))
printf '1.%s.%s\n' "$minor" "$patch"
