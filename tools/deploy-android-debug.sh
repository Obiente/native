#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
skip_build=false

if [[ "${1:-}" == "--skip-build" ]]; then
    skip_build=true
    shift
fi

if [[ "$#" -ne 0 ]]; then
    printf 'Usage: %s [--skip-build]\n' "${0##*/}" >&2
    exit 2
fi

if [[ -z "${ANDROID_HOME:-}" && -z "${ANDROID_SDK_ROOT:-}" ]]; then
    printf 'Set ANDROID_HOME or ANDROID_SDK_ROOT to your Android SDK directory.\n' >&2
    exit 1
fi

if [[ "$skip_build" == false ]]; then
    "$project_root/gradlew" :androidApp:assembleDebug
fi

"$project_root/tools/install-debug-apk.sh"

endpoint="$($project_root/tools/adb-connect-wireless.sh)"
adb -s "$endpoint" shell am start -W -n dev.obiente.nextcloudnative/.MainActivity
