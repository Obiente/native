#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if (($#)); then
  echo "This capture is workstation-only and accepts no device or account arguments." >&2
  exit 2
fi

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
./gradlew --no-daemon :ui:captureMarketingScreenshots

file website/public/screenshots/desktop-home.png | grep -q "PNG image data, 1440 x 900"
file website/public/screenshots/mobile-home.png | grep -q "PNG image data, 1080 x 2400"
file website/public/screenshots/obsidian-vault-sync.png | grep -q "PNG image data, 1080 x 1000"
file website/public/screenshots/media-backup-queue.png | grep -q "PNG image data, 1080 x 1800"
file website/public/screenshots/adaptive-dynamic-data.png | grep -q "PNG image data, 960 x 360"

echo "Workstation Compose captures are ready in website/public/screenshots/."
