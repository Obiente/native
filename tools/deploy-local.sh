#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
desktop_executable="$project_root/ui/build/compose/binaries/main/app/NextcloudNative/bin/NextcloudNative"
desktop_log="$project_root/ui/build/compose/binaries/main/app/NextcloudNative/nextcloud-native.log"
desktop_process_pattern='/NextcloudNative/bin/NextcloudNative([[:space:]]|$)'

if [[ "$(uname -s)" != "Linux" ]]; then
    printf 'This helper currently deploys the Linux desktop app and Android app.\n' >&2
    exit 1
fi

java_command="${JAVA_HOME:+$JAVA_HOME/bin/}java"
if ! command -v "$java_command" >/dev/null 2>&1; then
    printf 'JDK 21 was not found. Install it and set JAVA_HOME before deploying.\n' >&2
    exit 1
fi

java_version="$("$java_command" -version 2>&1 | awk -F'"' '/version/ { print $2; exit }')"
java_major="${java_version%%.*}"
if [[ "$java_major" != "21" ]]; then
    printf 'JDK 21 is required, but Java %s is active. Set JAVA_HOME to a JDK 21 installation.\n' "${java_version:-unknown}" >&2
    exit 1
fi

if [[ -z "${ANDROID_HOME:-}" && -z "${ANDROID_SDK_ROOT:-}" ]]; then
    printf 'Set ANDROID_HOME or ANDROID_SDK_ROOT to your Android SDK directory.\n' >&2
    exit 1
fi

printf 'Testing shared UI and building Linux desktop plus Android...\n'
"$project_root/gradlew" \
    :ui:desktopTest \
    :ui:createDistributable \
    :androidApp:assembleDebug

if [[ ! -x "$desktop_executable" ]]; then
    printf 'Desktop executable not found after build: %s\n' "$desktop_executable" >&2
    exit 1
fi

running_desktop_pids=()
while read -r desktop_pid; do
    [[ -n "$desktop_pid" ]] || continue
    process_executable="$(readlink "/proc/$desktop_pid/exe" 2>/dev/null || true)"
    process_executable="${process_executable% (deleted)}"
    if [[ "$process_executable" == "$desktop_executable" ]]; then
        running_desktop_pids+=("$desktop_pid")
    fi
done < <(pgrep -f "$desktop_process_pattern" || true)

if [[ "${#running_desktop_pids[@]}" -gt 0 ]]; then
    printf 'Restarting the running Linux desktop app with the new build...\n'
    kill "${running_desktop_pids[@]}"

    for _ in {1..50}; do
        still_running=false
        for desktop_pid in "${running_desktop_pids[@]}"; do
            if kill -0 "$desktop_pid" 2>/dev/null; then
                still_running=true
                break
            fi
        done
        [[ "$still_running" == false ]] && break
        sleep 0.1
    done

    if [[ "$still_running" == true ]]; then
        printf 'The previous desktop app did not close; refusing to start a duplicate.\n' >&2
        exit 1
    fi
fi

printf 'Opening Linux desktop app...\n'
nohup "$desktop_executable" >"$desktop_log" 2>&1 </dev/null &

printf 'Installing and opening Android app...\n'
"$project_root/tools/deploy-android-debug.sh" --skip-build
