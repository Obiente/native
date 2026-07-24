#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
default_apk="$project_root/androidApp/build/outputs/apk/debug/androidApp-debug.apk"
apk_path="${1:-$default_apk}"

if [[ ! -f "$apk_path" ]]; then
    printf 'APK not found: %s\n' "$apk_path" >&2
    printf 'Build the Android debug app first, then retry.\n' >&2
    exit 1
fi

endpoint="$($project_root/tools/adb-connect-wireless.sh)"
adb -s "$endpoint" install -r -t "$apk_path"
