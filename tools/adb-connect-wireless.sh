#!/usr/bin/env bash
set -euo pipefail

device_name="${ADB_DEVICE_NAME:-}"

existing_endpoint="$({
    adb devices -l 2>/dev/null || true
} | awk -v model="$device_name" '
    $1 ~ /^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+:[0-9]+$/ && $2 == "device" && (model == "" || index($0, "model:" model)) { print $1; exit }
')"

if [[ -n "$existing_endpoint" ]] && adb -s "$existing_endpoint" get-state >/dev/null 2>&1; then
    printf '%s\n' "$existing_endpoint"
    exit 0
fi

endpoint="$(
    avahi-browse -rtp _adb-tls-connect._tcp --terminate 2>/dev/null |
        awk -F';' -v device="$device_name" '
            $1 == "=" && $3 == "IPv4" && (device == "" || index($10, "name=" device)) {
                print $8 ":" $9
                exit
            }
        '
)"

if [[ -z "$endpoint" ]]; then
    if [[ -n "$device_name" ]]; then
        printf 'No paired Android device named %s was discovered.\n' "$device_name" >&2
    else
        printf 'No paired Android wireless-debugging device was discovered.\n' >&2
    fi
    printf 'Keep Wireless debugging enabled and use Pair using pairing code if the workstation was forgotten.\n' >&2
    exit 1
fi

adb connect "$endpoint" >/dev/null
if [[ "$(adb -s "$endpoint" get-state 2>/dev/null)" != "device" ]]; then
    printf 'ADB discovered %s but could not establish a trusted connection.\n' "$endpoint" >&2
    exit 1
fi

printf '%s\n' "$endpoint"
