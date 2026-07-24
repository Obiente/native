#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
package_name="dev.obiente.nextcloudnative"
activity_name="$package_name/.MainActivity"
system_image="${NC_NATIVE_EMULATOR_IMAGE:-system-images;android-36;default;x86_64}"
device_profile="${NC_NATIVE_EMULATOR_DEVICE:-pixel_8}"
memory_mb="${NC_NATIVE_EMULATOR_MEMORY_MB:-2560}"
cpu_cores="${NC_NATIVE_EMULATOR_CORES:-2}"
gpu_mode="${NC_NATIVE_EMULATOR_GPU:-host}"
boot_timeout_seconds="${NC_NATIVE_EMULATOR_BOOT_TIMEOUT_SECONDS:-180}"
session_import_timeout_seconds="${NC_NATIVE_SESSION_IMPORT_TIMEOUT_SECONDS:-45}"

usage() {
    cat <<'EOF'
Usage:
  tools/android-emulator.sh start <instance> <slot> [--fresh] [--headless]
  tools/android-emulator.sh stop <instance>
  tools/android-emulator.sh status <instance>
  tools/android-emulator.sh serial <instance>
  tools/android-emulator.sh install <instance> [apk]
  tools/android-emulator.sh reuse-desktop-session <instance>
  tools/android-emulator.sh smoke <instance> [apk]

Each instance has isolated writable Android state. Slots 0 through 63 map to
the emulator's supported even console ports, beginning at 5554. Assign a
different slot to every concurrently running agent. Automated runs are
visible by default; --headless disables the interactive emulator window.
EOF
}

fail() {
    printf 'error: %s\n' "$*" >&2
    exit 1
}

sdk_root() {
    if [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
        printf '%s\n' "$ANDROID_SDK_ROOT"
    elif [[ -n "${ANDROID_HOME:-}" ]]; then
        printf '%s\n' "$ANDROID_HOME"
    elif [[ -d /opt/android-sdk ]]; then
        printf '%s\n' /opt/android-sdk
    else
        fail "set ANDROID_SDK_ROOT or ANDROID_HOME to an Android SDK containing the emulator"
    fi
}

require_instance() {
    local candidate="${1:-}"
    [[ "$candidate" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$ ]] ||
        fail "instance names may contain only letters, numbers, dots, underscores, and hyphens"
}

state_root() {
    printf '%s\n' "${NC_NATIVE_EMULATOR_STATE_ROOT:-/var/tmp/nc-native-emulators-${UID}}"
}

instance_dir() {
    printf '%s/%s\n' "$(state_root)" "$1"
}

read_instance_value() {
    local instance="$1"
    local filename="$2"
    local value_file
    value_file="$(instance_dir "$instance")/$filename"
    [[ -f "$value_file" ]] || fail "emulator instance '$instance' is not running"
    tr -d '\r\n' <"$value_file"
}

adb_bin() {
    local root
    root="$(sdk_root)"
    [[ -x "$root/platform-tools/adb" ]] ||
        fail "Android platform-tools are missing from $root"
    printf '%s\n' "$root/platform-tools/adb"
}

serial_for() {
    local port
    port="$(read_instance_value "$1" port)"
    [[ "$port" =~ ^[0-9]+$ ]] || fail "invalid saved emulator port for '$1'"
    printf 'emulator-%s\n' "$port"
}

ensure_avd() {
    local instance="$1"
    local root="$2"
    local directory="$3"
    local avd_name="nc_native_${instance//[^A-Za-z0-9_]/_}"
    local avd_home="$directory/avd"
    local android_user_home="$directory/android-user"
    local avdmanager="$root/cmdline-tools/latest/bin/avdmanager"
    local emulator="$root/emulator/emulator"

    [[ -x "$avdmanager" ]] || fail "avdmanager is missing from $root/cmdline-tools/latest/bin"
    [[ -x "$emulator" ]] || fail "Android Emulator is missing from $root/emulator"
    local system_image_directory="$root/${system_image//;/\/}"
    [[ -d "$system_image_directory" ]] ||
        fail "system image '$system_image' is not installed in $root"

    mkdir -p "$avd_home" "$android_user_home"
    if ! ANDROID_AVD_HOME="$avd_home" \
        ANDROID_USER_HOME="$android_user_home" \
        "$emulator" -list-avds | grep -Fxq "$avd_name"; then
        if ! printf 'no\n' |
            ANDROID_AVD_HOME="$avd_home" \
            ANDROID_USER_HOME="$android_user_home" \
            "$avdmanager" create avd \
                --force \
                --name "$avd_name" \
                --package "$system_image" \
                --device "$device_profile" \
                >"$directory/avdmanager.log" 2>&1; then
            tail -80 "$directory/avdmanager.log" >&2 || true
            fail "could not create emulator definition for '$instance'"
        fi
    fi
    printf '%s\n' "$avd_name"
}

launcher_is_alive() {
    local directory="$1"
    if [[ -f "$directory/systemd.unit" ]]; then
        local unit
        unit="$(tr -d '\r\n' <"$directory/systemd.unit")"
        systemctl --user is-active --quiet "$unit"
    elif [[ -f "$directory/launcher.pid" ]]; then
        local launcher_pid
        launcher_pid="$(tr -d '\r\n' <"$directory/launcher.pid")"
        [[ "$launcher_pid" =~ ^[0-9]+$ ]] && kill -0 "$launcher_pid" 2>/dev/null
    else
        return 1
    fi
}

start_instance() {
    local instance="${1:-}"
    local slot="${2:-}"
    shift "$(( $# >= 2 ? 2 : $# ))"
    require_instance "$instance"
    [[ "$slot" =~ ^[0-9]+$ ]] || fail "slot must be a number from 0 through 63"
    ((slot <= 63)) || fail "slot must be a number from 0 through 63"

    local fresh=false
    local visible=true
    while [[ "$#" -gt 0 ]]; do
        case "$1" in
            --fresh) fresh=true ;;
            --headless) visible=false ;;
            --visible) visible=true ;;
            *) fail "unknown start option: $1" ;;
        esac
        shift
    done
    [[ -e /dev/kvm ]] || fail "/dev/kvm is unavailable; enable hardware virtualization"
    [[ "$memory_mb" =~ ^[0-9]+$ ]] || fail "NC_NATIVE_EMULATOR_MEMORY_MB must be numeric"
    [[ "$cpu_cores" =~ ^[0-9]+$ ]] || fail "NC_NATIVE_EMULATOR_CORES must be numeric"

    local available_memory_kb
    local required_memory_kb=$(((memory_mb + 512) * 1024))
    available_memory_kb="$(awk '/^MemAvailable:/ { print $2 }' /proc/meminfo)"
    if ((available_memory_kb < required_memory_kb)) &&
        [[ "${NC_NATIVE_EMULATOR_IGNORE_MEMORY_PRESSURE:-0}" != "1" ]]; then
        fail \
            "insufficient memory for a ${memory_mb} MB emulator; stop another instance or set NC_NATIVE_EMULATOR_IGNORE_MEMORY_PRESSURE=1"
    fi

    local root
    local directory
    local avd_name
    local emulator
    local adb
    local port=$((5554 + slot * 2))
    local serial="emulator-$port"
    local lock_file="/var/tmp/nc-native-emulator-${UID}-port-${port}.lock"
    root="$(sdk_root)"
    directory="$(instance_dir "$instance")"
    emulator="$root/emulator/emulator"
    adb="$(adb_bin)"

    mkdir -p "$directory"
    if "$adb" -s "$serial" get-state >/dev/null 2>&1; then
        fail "slot $slot is already occupied by $serial"
    fi
    avd_name="$(ensure_avd "$instance" "$root" "$directory")"

    local -a fresh_args=()
    if [[ "$fresh" == true ]]; then
        fresh_args+=("-wipe-data")
    fi
    local -a window_args=()
    if [[ "$visible" == false ]]; then
        window_args+=("-no-window")
    fi
    local -a display_environment=()
    if [[ "$visible" == true && -n "${DISPLAY:-}" ]]; then
        display_environment+=("QT_QPA_PLATFORM=xcb" "DISPLAY=$DISPLAY")
    fi

    printf '%s\n' "$port" >"$directory/port"
    printf '%s\n' "$slot" >"$directory/slot"
    printf '%s\n' "$visible" >"$directory/visible"
    local -a emulator_command=(
        flock -n "$lock_file"
        env
        "ANDROID_AVD_HOME=$directory/avd"
        "ANDROID_USER_HOME=$directory/android-user"
        "${display_environment[@]}"
        "$emulator"
        -avd "$avd_name"
        -port "$port"
        "${window_args[@]}"
        -no-audio
        -no-boot-anim
        -no-snapshot
        -gpu "$gpu_mode"
        -feature -Vulkan
        -memory "$memory_mb"
        -cores "$cpu_cores"
        "${fresh_args[@]}"
    )
    if command -v systemd-run >/dev/null &&
        systemctl --user show-environment >/dev/null 2>&1; then
        local unit="nc-native-emulator-${UID}-${avd_name}.service"
        systemctl --user stop "$unit" >/dev/null 2>&1 || true
        systemd-run \
            --user \
            --quiet \
            --collect \
            --unit "$unit" \
            --property KillMode=mixed \
            --property "StandardOutput=append:$directory/emulator.log" \
            --property "StandardError=append:$directory/emulator.log" \
            -- \
            "${emulator_command[@]}"
        printf '%s\n' "$unit" >"$directory/systemd.unit"
    else
        nohup "${emulator_command[@]}" >"$directory/launcher.log" 2>&1 &
        local launcher_pid=$!
        printf '%s\n' "$launcher_pid" >"$directory/launcher.pid"
    fi

    local deadline=$((SECONDS + boot_timeout_seconds))
    while ((SECONDS < deadline)); do
        if ! launcher_is_alive "$directory"; then
            [[ ! -f "$directory/emulator.log" ]] ||
                tail -80 "$directory/emulator.log" >&2
            fail "emulator '$instance' exited before boot completed"
        fi
        if [[ "$("$adb" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]] &&
            "$adb" -s "$serial" shell cmd package list packages >/dev/null 2>&1; then
            "$adb" -s "$serial" shell settings put global window_animation_scale 0
            "$adb" -s "$serial" shell settings put global transition_animation_scale 0
            "$adb" -s "$serial" shell settings put global animator_duration_scale 0
            printf '%s\n' "$serial"
            return
        fi
        sleep 2
    done

    [[ ! -f "$directory/emulator.log" ]] ||
        tail -80 "$directory/emulator.log" >&2
    fail "emulator '$instance' did not boot within ${boot_timeout_seconds}s"
}

stop_instance() {
    local instance="${1:-}"
    require_instance "$instance"
    local directory
    local serial
    local adb
    directory="$(instance_dir "$instance")"
    serial="$(serial_for "$instance")"
    adb="$(adb_bin)"

    "$adb" -s "$serial" emu kill >/dev/null 2>&1 || true
    if [[ -f "$directory/systemd.unit" ]]; then
        local unit
        unit="$(tr -d '\r\n' <"$directory/systemd.unit")"
        systemctl --user stop "$unit" >/dev/null 2>&1 || true
    elif [[ -f "$directory/launcher.pid" ]]; then
        local launcher_pid
        launcher_pid="$(tr -d '\r\n' <"$directory/launcher.pid")"
        if [[ "$launcher_pid" =~ ^[0-9]+$ ]]; then
            for _ in {1..20}; do
                kill -0 "$launcher_pid" 2>/dev/null || break
                sleep 0.25
            done
            if kill -0 "$launcher_pid" 2>/dev/null; then
                kill "$launcher_pid"
            fi
        fi
    fi
    printf 'stopped %s\n' "$instance"
}

status_instance() {
    local instance="${1:-}"
    require_instance "$instance"
    local serial
    local adb
    serial="$(serial_for "$instance")"
    adb="$(adb_bin)"
    if "$adb" -s "$serial" get-state >/dev/null 2>&1; then
        printf '%s running as %s\n' "$instance" "$serial"
    else
        printf '%s stopped\n' "$instance"
        return 1
    fi
}

install_apk() {
    local instance="${1:-}"
    local apk_path="${2:-$project_root/androidApp/build/outputs/apk/debug/androidApp-debug.apk}"
    require_instance "$instance"
    [[ -f "$apk_path" ]] || fail "APK not found: $apk_path"
    local serial
    serial="$(serial_for "$instance")"
    "$(adb_bin)" -s "$serial" install -r -t "$apk_path"
}

reuse_desktop_session() {
    local instance="${1:-}"
    require_instance "$instance"
    command -v java >/dev/null || fail "Java is required to read the desktop account store"
    command -v secret-tool >/dev/null || fail "secret-tool is required to read the desktop keyring"
    [[ "$session_import_timeout_seconds" =~ ^[0-9]+$ ]] ||
        fail "NC_NATIVE_SESSION_IMPORT_TIMEOUT_SECONDS must be numeric"

    local serial
    local adb
    serial="$(serial_for "$instance")"
    adb="$(adb_bin)"
    "$adb" -s "$serial" get-state >/dev/null 2>&1 ||
        fail "emulator instance '$instance' is not running"
    "$adb" -s "$serial" shell run-as "$package_name" true >/dev/null 2>&1 ||
        fail "install a debuggable Nextcloud Native APK on '$instance' first"

    java "$project_root/tools/DesktopSessionExport.java" |
        "$adb" -s "$serial" exec-in \
            run-as "$package_name" \
            sh -c 'umask 077; cat > files/nc-native-test-session.json'
    "$adb" -s "$serial" shell am force-stop "$package_name"
    "$adb" -s "$serial" shell am start -W -n "$activity_name" >/dev/null

    local deadline=$((SECONDS + session_import_timeout_seconds))
    while ((SECONDS < deadline)); do
        if "$adb" -s "$serial" shell run-as "$package_name" \
            sh -c 'test ! -e files/nc-native-test-session.json' >/dev/null 2>&1; then
            if "$adb" -s "$serial" shell run-as "$package_name" \
                sh -c 'grep -q emulator_test_read_only shared_prefs/nextcloud_native.xml' \
                >/dev/null 2>&1; then
                printf '%s\n' \
                    "Desktop session imported into $instance in enforced read-only mode." \
                    "Cloud writes and destructive requests are blocked for this emulator session."
                return
            fi
            fail "the emulator removed the session import without enabling read-only mode"
        fi
        sleep 1
    done
    fail "the emulator did not consume the desktop session import"
}

smoke_test() {
    local instance="${1:-}"
    local apk_path="${2:-$project_root/androidApp/build/outputs/apk/debug/androidApp-debug.apk}"
    require_instance "$instance"
    local serial
    local adb
    local report_dir="$project_root/build/reports/android-emulator/$instance"
    serial="$(serial_for "$instance")"
    adb="$(adb_bin)"
    mkdir -p "$report_dir"

    install_apk "$instance" "$apk_path"
    "$adb" -s "$serial" shell pm clear "$package_name" >/dev/null
    "$adb" -s "$serial" logcat -c
    "$adb" -s "$serial" shell am start -W -n "$activity_name" >"$report_dir/launch.txt"

    local rotation
    for rotation in 0 1; do
        "$adb" -s "$serial" shell settings put system accelerometer_rotation 0
        "$adb" -s "$serial" shell settings put system user_rotation "$rotation"
        sleep 1
        "$adb" -s "$serial" shell uiautomator dump /sdcard/nc-native-window.xml >/dev/null
        "$adb" -s "$serial" exec-out cat /sdcard/nc-native-window.xml \
            >"$report_dir/window-rotation-${rotation}.xml"
        "$adb" -s "$serial" exec-out screencap -p \
            >"$report_dir/screenshot-rotation-${rotation}.png"
    done
    "$adb" -s "$serial" shell settings put system accelerometer_rotation 1
    "$adb" -s "$serial" logcat -d >"$report_dir/logcat.txt"

    if rg -n \
        'FATAL EXCEPTION|The coroutine scope left the composition|Required value was null' \
        "$report_dir/logcat.txt" \
        "$report_dir"/window-rotation-*.xml; then
        fail "Android smoke test found a crash or leaked internal error; see $report_dir"
    fi
    printf 'smoke test passed on %s; report: %s\n' "$serial" "$report_dir"
}

command="${1:-}"
case "$command" in
    start)
        shift
        start_instance "$@"
        ;;
    stop)
        shift
        [[ "$#" -eq 1 ]] || { usage >&2; exit 2; }
        stop_instance "$1"
        ;;
    status)
        shift
        [[ "$#" -eq 1 ]] || { usage >&2; exit 2; }
        status_instance "$1"
        ;;
    serial)
        shift
        [[ "$#" -eq 1 ]] || { usage >&2; exit 2; }
        serial_for "$1"
        ;;
    install)
        shift
        [[ "$#" -ge 1 && "$#" -le 2 ]] || { usage >&2; exit 2; }
        install_apk "$@"
        ;;
    reuse-desktop-session)
        shift
        [[ "$#" -eq 1 ]] || { usage >&2; exit 2; }
        reuse_desktop_session "$1"
        ;;
    smoke)
        shift
        [[ "$#" -ge 1 && "$#" -le 2 ]] || { usage >&2; exit 2; }
        smoke_test "$@"
        ;;
    *)
        usage >&2
        exit 2
        ;;
esac
