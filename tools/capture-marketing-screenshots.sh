#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if (($#)); then
  echo "This capture is workstation-only and accepts no device or account arguments." >&2
  exit 2
fi

cd "$repository_root"
./gradlew --no-daemon :ui:captureMarketingScreenshots

file website/public/screenshots/desktop-home.png | grep -q "PNG image data, 1440 x 900"
file website/public/screenshots/mobile-home.png | grep -q "PNG image data, 1080 x 2400"
file website/public/screenshots/obsidian-vault-sync.png | grep -q "PNG image data, 1080 x 1000"
file website/public/screenshots/media-backup-queue.png | grep -q "PNG image data, 1080 x 1800"
file website/public/screenshots/adaptive-dynamic-data.png | grep -q "PNG image data, 960 x 360"

echo "Workstation Compose captures are ready in website/public/screenshots/."
