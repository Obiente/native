#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
temporary_directory="$(mktemp -d)"
trap 'rm -rf "$temporary_directory"' EXIT

generated="$temporary_directory/update-manifest.json"
GITHUB_REPOSITORY="Obiente/nc-native" \
  "$project_root/tools/create-prerelease-update-manifest.sh" \
  "$generated" \
  "0.1.0-alpha.1" \
  "1" \
  "nextcloud-native-0.1.0-alpha.1-android.apk" \
  "123456" \
  "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" \
  '["bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"]'

diff -u "$project_root/release/prerelease-v1.fixture.json" "$generated"
grep -Fq 'tools/create-prerelease-update-manifest.sh' \
  "$project_root/.github/workflows/prerelease.yml"
grep -Fq 'update-manifest.json' "$project_root/.github/workflows/prerelease.yml"
grep -Fq \
  'https://api.github.com/repos/Obiente/nc-native/releases?per_page=20' \
  "$project_root/ui/src/commonMain/kotlin/dev/obiente/nextcloudnative/app/ProjectNewsAndUpdates.kt"

printf 'Prerelease update producer and consumer contract checks passed.\n'
