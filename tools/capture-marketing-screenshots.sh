#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if (($#)); then
  echo "This capture is workstation-only and accepts no device or account arguments." >&2
  exit 2
fi

cd "$repository_root"
./gradlew --no-daemon --max-workers=1 :ui:captureMarketingScreenshots
npm run --prefix website verify:captures

echo "Workstation Compose captures are ready in website/public/screenshots/."
