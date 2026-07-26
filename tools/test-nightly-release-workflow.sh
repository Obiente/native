#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
workflow="$project_root/.github/workflows/prerelease.yml"

require_text() {
    local expected="$1"
    if ! grep -Fq -- "$expected" "$workflow"; then
        printf 'Prerelease workflow is missing required nightly contract: %s\n' "$expected" >&2
        exit 1
    fi
}

require_text '      - main'
require_text 'tag="nightly-${timestamp}-run${GITHUB_RUN_NUMBER}-${short_sha}"'
require_text 'existing_sha="$('
require_text 'elif [[ "${existing_sha}" != "${GITHUB_SHA}" ]]; then'
require_text 'pattern: nextcloud-native-*'
require_text 'test "${successful}" -gt 0'
require_text 'if [[ "${CURATED_RELEASE}" == "true" && -f "dist/${apk_name}" ]]; then'
require_text 'test "${SUCCESSFUL_PLATFORMS}" -ge 3'
require_text 'test "${SUCCESSFUL_PLATFORMS}" -ge 1'
require_text ':androidApp:verifyReleaseLintGate'
require_text ':androidApp:lintVitalDirectApk'
require_text ':androidApp:assembleDirectApk'
require_text 'expected_certificate_sha256'
require_text 'contents: read'
require_text 'contents: write'

printf 'Nightly prerelease workflow contract checks passed.\n'
