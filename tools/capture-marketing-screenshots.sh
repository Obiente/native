#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
desktop_output="$repository_root/website/public/screenshots/desktop-home.png"
mobile_output="$repository_root/website/public/screenshots/mobile-home.png"
android_serial=""
allow_physical=false

while (($#)); do
  case "$1" in
    --android)
      android_serial="${2:?Pass an explicit Android device or emulator serial.}"
      shift 2
      ;;
    --allow-physical-demo-capture)
      allow_physical=true
      shift
      ;;
    *)
      echo "Unknown capture option: $1" >&2
      exit 2
      ;;
  esac
done

java_command="${JAVA_HOME:+$JAVA_HOME/bin/}java"
if ! command -v "$java_command" >/dev/null 2>&1; then
  echo "JDK 21 was not found. Install it and set JAVA_HOME before capturing." >&2
  exit 1
fi
java_version="$("$java_command" -version 2>&1 | awk -F'"' '/version/ { print $2; exit }')"
if [[ "${java_version%%.*}" != "21" ]]; then
  echo "JDK 21 is required. Set JAVA_HOME to a JDK 21 installation." >&2
  exit 1
fi

cd "$repository_root"
./gradlew --no-daemon :ui:captureDesktopMarketingScreenshot

if [[ -z "$android_serial" ]]; then
  echo "Desktop capture written to $desktop_output"
  echo "Pass --android SERIAL to capture the isolated Android demo build."
  exit 0
fi

if [[ -z "${ANDROID_HOME:-}" && -z "${ANDROID_SDK_ROOT:-}" ]]; then
  echo "Set ANDROID_HOME or ANDROID_SDK_ROOT to the Android SDK directory." >&2
  exit 1
fi
command -v adb >/dev/null 2>&1 || {
  echo "adb was not found on PATH." >&2
  exit 1
}

[[ "$android_serial" != *$'\n'* && "$android_serial" != *$'\r'* ]] || {
  echo "Invalid Android serial." >&2
  exit 2
}
adb -s "$android_serial" get-state | grep -qx device
is_emulator="$(adb -s "$android_serial" shell getprop ro.kernel.qemu | tr -d '\r')"
if [[ "$is_emulator" != "1" && "$allow_physical" != true ]]; then
  echo "Physical capture is disabled. Use an emulator, or explicitly allow the isolated demo build." >&2
  exit 3
fi

size_state="$(adb -s "$android_serial" shell wm size | tr -d '\r')"
density_state="$(adb -s "$android_serial" shell wm density | tr -d '\r')"
if grep -q "Override" <<<"$size_state$density_state"; then
  echo "Refusing to replace an existing device viewport override." >&2
  exit 3
fi

restore_viewport() {
  adb -s "$android_serial" shell wm size reset >/dev/null 2>&1 || true
  adb -s "$android_serial" shell wm density reset >/dev/null 2>&1 || true
}
trap restore_viewport EXIT

./gradlew --no-daemon :androidApp:assembleScreenshot
apk="$repository_root/androidApp/build/outputs/apk/screenshot/androidApp-screenshot.apk"
[[ -f "$apk" ]]
adb -s "$android_serial" install -r "$apk" >/dev/null
adb -s "$android_serial" shell wm size 1080x2400
adb -s "$android_serial" shell wm density 420
if ! adb -s "$android_serial" shell dumpsys power | grep -q 'mWakefulness=Awake'; then
  adb -s "$android_serial" shell input keyevent KEYCODE_POWER
fi
adb -s "$android_serial" shell wm dismiss-keyguard
adb -s "$android_serial" shell am force-stop dev.obiente.nextcloudnative.demo
adb -s "$android_serial" shell am start \
  -n dev.obiente.nextcloudnative.demo/dev.obiente.nextcloudnative.MainActivity >/dev/null

for _ in $(seq 1 40); do
  resumed="$(adb -s "$android_serial" shell dumpsys activity activities |
    grep -m1 -E 'mResumedActivity|topResumedActivity' || true)"
  if [[ "$resumed" == *"dev.obiente.nextcloudnative.demo/dev.obiente.nextcloudnative.MainActivity"* ]]; then
    break
  fi
  sleep 0.25
done
[[ "${resumed:-}" == *"dev.obiente.nextcloudnative.demo/dev.obiente.nextcloudnative.MainActivity"* ]] || {
  echo "The isolated demo activity did not become foreground; no screenshot was taken." >&2
  exit 4
}

mkdir -p "$(dirname "$mobile_output")"
temporary_output="${mobile_output}.part"
adb -s "$android_serial" exec-out screencap -p >"$temporary_output"
file "$temporary_output" | grep -q "PNG image data, 1080 x 2400"
mv "$temporary_output" "$mobile_output"
echo "Android capture written to $mobile_output"
