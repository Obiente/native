#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fixture_root="$(mktemp -d)"
trap 'rm -rf -- "$fixture_root"' EXIT

# Supply only the helper's filesystem commands, never a host-installed SDK tool.
mkdir -p "$fixture_root/commands"
for utility in mkdir grep sed tail; do
    printf '#!/bin/bash\nexec %q "$@"\n' "$(type -P "$utility")" >"$fixture_root/commands/$utility"
    chmod +x "$fixture_root/commands/$utility"
done

create_tool() {
    local destination="$1" label="$2" fails="$3"
    mkdir -p "$(dirname "$destination")"
    {
        printf '#!/bin/bash\nset -euo pipefail\n'
        printf 'printf "%%s\\n" %q >> "$TOOL_CALLS"\n' "$label"
        if [[ "$fails" == true ]]; then
            printf 'echo "synthetic unavailable device" >&2\nexit 17\n'
        else
            cat <<'TOOL'
[[ "$*" == 'create avd --force --name nc_native_fixture --package system-images;android-36;default;x86_64 --device pixel_8' ]]
[[ "$ANDROID_AVD_HOME" == "$EXPECTED_STATE/avd" ]]
[[ "$ANDROID_USER_HOME" == "$EXPECTED_STATE/android-user" ]]
mkdir -p "$ANDROID_AVD_HOME/nc_native_fixture.avd"
printf 'hw.keyboard=no\n' >"$ANDROID_AVD_HOME/nc_native_fixture.avd/config.ini"
TOOL
        fi
    } >"$destination"
    chmod +x "$destination"
}

run_case() {
    local name="$1" configured="$2" fallback="$3" expected="$4"
    local directory="$fixture_root/$name"
    mkdir -p "$directory/sdk/emulator" "$directory/sdk/system-images/android-36/default/x86_64" "$directory/path"
    printf '#!/bin/bash\n[[ "$1" == -list-avds ]]\n' >"$directory/sdk/emulator/emulator"
    chmod +x "$directory/sdk/emulator/emulator"
    [[ "$configured" == absent ]] || create_tool "$directory/path/avdmanager" configured "$configured"
    [[ "$fallback" == absent ]] || create_tool "$directory/sdk/cmdline-tools/latest/bin/avdmanager" fallback "$fallback"
    local status=0
    (
        source "$project_root/tools/android-emulator.sh"
        export PATH="$directory/path:$fixture_root/commands"
        export TOOL_CALLS="$directory/calls" EXPECTED_STATE="$directory/state"
        system_image='system-images;android-36;default;x86_64'
        device_profile=pixel_8
        ensure_avd fixture "$directory/sdk" "$directory/state"
    ) >"$directory/output" 2>&1 || status=$?
    if [[ "$expected" == failure ]]; then
        [[ "$status" -ne 0 ]] || { echo "$name unexpectedly succeeded" >&2; exit 1; }
    else
        [[ "$status" -eq 0 ]] || { cat "$directory/output" >&2; exit 1; }
        [[ "$(cat "$directory/calls")" == "$expected" ]]
        grep -Fxq 'hw.keyboard=yes' "$directory/state/avd/nc_native_fixture.avd/config.ini"
        grep -Fxq nc_native_fixture "$directory/output"
    fi
}

run_case configured-wins false true configured
run_case fallback absent false fallback
run_case missing absent absent failure
[[ ! -e "$fixture_root/missing/calls" ]]
grep -Fq 'avdmanager is missing' "$fixture_root/missing/output"
run_case selected-failure true false failure
[[ "$(cat "$fixture_root/selected-failure/calls")" == configured ]]
grep -Fq 'synthetic unavailable device' "$fixture_root/selected-failure/output"
printf 'Android emulator SDK tool selection checks passed.\n'
