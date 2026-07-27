#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if (($#)); then
  echo "This capture is workstation-only and accepts no device or account arguments." >&2
  exit 2
fi

cd "$repository_root"
./gradlew --no-daemon --max-workers=1 :ui:captureMarketingScreenshots

file website/public/screenshots/desktop-home.png | grep -q "PNG image data, 1440 x 900"
file website/public/screenshots/mobile-home.png | grep -q "PNG image data, 1080 x 2400"
file website/public/screenshots/obsidian-vault-sync.png | grep -q "PNG image data, 1080 x 1000"
file website/public/screenshots/media-backup-queue.png | grep -q "PNG image data, 1080 x 1800"
file website/public/screenshots/adaptive-dynamic-data.png | grep -q "PNG image data, 960 x 360"
file website/public/screenshots/raw-preview-loading-mobile.png | grep -q "PNG image data, 1080 x 1200"
file website/public/screenshots/raw-preview-error-mobile.png | grep -q "PNG image data, 1080 x 1200"
file website/public/screenshots/raw-preview-memories-ready-mobile.png | grep -q "PNG image data, 1080 x 1600"
file website/public/screenshots/raw-preview-high-detail-desktop.png | grep -q "PNG image data, 1440 x 900"
file website/public/screenshots/file-share-user-mobile.png | grep -q "PNG image data, 1080 x 1800"
file website/public/screenshots/file-share-group-desktop.png | grep -q "PNG image data, 1440 x 900"
file website/public/screenshots/file-share-loading-mobile.png | grep -q "PNG image data, 1080 x 1800"
file website/public/screenshots/file-share-error-mobile.png | grep -q "PNG image data, 1080 x 1800"
file website/public/screenshots/transfer-mobile-pending.png | grep -q "PNG image data, 1080 x 1800"
file website/public/screenshots/transfer-mobile-failed-cached.png | grep -q "PNG image data, 1080 x 1800"
file website/public/screenshots/transfer-desktop-active.png | grep -q "PNG image data, 1280 x 800"
file website/public/screenshots/transfer-desktop-completed-page.png | grep -q "PNG image data, 1280 x 800"
file website/public/screenshots/deck-board-desktop.png | grep -q "PNG image data, 1440 x 900"
file website/public/screenshots/deck-board-mobile.png | grep -q "PNG image data, 1080 x 1800"

echo "Workstation Compose captures are ready in website/public/screenshots/."
