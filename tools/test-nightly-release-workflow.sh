#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
nightly="$project_root/.github/workflows/nightly.yml"
prerelease="$project_root/.github/workflows/prerelease.yml"
promotion="$project_root/tools/promote-android-update-channel.sh"
temporary_directory="$(mktemp -d)"
trap 'rm -r -- "$temporary_directory"' EXIT

require_text() {
    local file="$1"
    local expected="$2"
    if ! grep -Fq -- "$expected" "$file"; then
        printf '%s is missing required release contract: %s\n' "$file" "$expected" >&2
        exit 1
    fi
}

require_text "$nightly" 'workflow_run:'
require_text "$nightly" 'github.event.workflow_run.conclusion == '\''success'\'''
require_text "$nightly" 'github.event.workflow_run.event == '\''push'\'''
require_text "$nightly" 'github.event.workflow_run.head_branch == '\''main'\'''
require_text "$nightly" 'github.event.workflow_run.head_repository.full_name == github.repository'
require_text "$nightly" 'group: nightly-release-${{ github.event.workflow_run.head_sha }}'
require_text "$nightly" 'group: android-nightly-channel'
require_text "$nightly" 'A curated prerelease already owns this source commit.'
require_text "$nightly" 'ref: ${{ github.event.workflow_run.head_sha }}'
require_text "$nightly" 'environment: prerelease'
require_text "$nightly" 'continue-on-error: true'
require_text "$nightly" 'Require protected Android signing secrets'
require_text "$nightly" 'if [[ -z "${!secret_name}" ]]; then'
require_text "$nightly" 'runner: ubuntu-latest'
require_text "$nightly" 'runner: windows-latest'
require_text "$nightly" 'runner: macos-15-intel'
require_text "$nightly" 'tasks: ":ui:packageDeb :ui:packageRpm"'
require_text "$nightly" 'tasks: ":ui:packageMsi"'
require_text "$nightly" 'tasks: ":ui:packageDmg"'
require_text "$nightly" 'tools/derive-desktop-package-version.sh'
require_text "$nightly" 'source_sequence="$(git rev-list --count "${SOURCE_SHA}")"'
require_text "$nightly" '-PncDesktopPackageVersion="${NIGHTLY_DESKTOP_VERSION}"'
require_text "$nightly" '-PncMacosPackageVersion="${NIGHTLY_DESKTOP_VERSION}"'
require_text "$nightly" 'name: nextcloud-native-${{ matrix.platform }}'
require_text "$nightly" 'name: nextcloud-native-android'
require_text "$nightly" '-PncVersionName="${NIGHTLY_VERSION}"'
require_text "$nightly" '-PncVersionCode="${NIGHTLY_VERSION_CODE}"'
require_text "$nightly" ':androidApp:verifyReleaseLintGate'
require_text "$nightly" ':androidApp:lintVitalDirectApk'
require_text "$nightly" ':androidApp:assembleDirectApk'
require_text "$nightly" 'release/android-signing-certificate.sha256'
require_text "$nightly" 'tools/create-android-update-manifest.sh'
require_text "$nightly" 'tools/verify-android-artifact-metadata.sh'
require_text "$nightly" 'nightly-v1'
require_text "$nightly" 'for platform in android linux windows macos; do'
require_text "$nightly" 'echo "successful=${successful}" >>"${GITHUB_OUTPUT}"'
require_text "$nightly" 'test "${SUCCESSFUL_PLATFORMS}" -ge 3'
require_text "$nightly" 'test "${#assets[@]}" -gt 0'
require_text "$nightly" 'already-published: ${{ steps.release.outputs.already-published }}'
require_text "$nightly" 'available-platforms: ${{ steps.platforms.outputs.available }}'
require_text "$nightly" 'Published nightly ${NIGHTLY_TAG} is immutable; no assets or metadata were changed.'
require_text "$nightly" 'Retaining immutable staged asset ${name}.'
require_text "$nightly" 'canonical-release-assets'
require_text "$nightly" 'if [[ "${SUCCESSFUL_PLATFORMS}" -ge 3 ]]; then'
require_text "$nightly" 'if: needs.stage-assets.outputs.already-published != '\''true'\'''
require_text "$nightly" 'tools/promote-android-update-channel.sh'
require_text "$nightly" 'tools/verify-android-update-manifest-assets.sh'
require_text "$nightly" 'channel-nightly'
require_text "$nightly" '--draft'
require_text "$nightly" '--draft=false'
require_text "$nightly" 'contents: write'
require_text "$nightly" 'actions: read'

if grep -Fq '      - main' "$prerelease"; then
    echo "The curated prerelease workflow must not publish from main pushes." >&2
    exit 1
fi
require_text "$prerelease" 'tools/derive-android-version-code.sh'
require_text "$prerelease" '-PncVersionCode="${RELEASE_VERSION_CODE}"'
require_text "$prerelease" 'group: prerelease-release-${{ github.ref }}'
require_text "$prerelease" 'group: android-prerelease-channel'
require_text "$prerelease" 'tools/derive-desktop-package-version.sh'
require_text "$prerelease" 'source_sequence="$(git rev-list --count "${GITHUB_SHA}")"'
require_text "$prerelease" '-PncDesktopPackageVersion="${RELEASE_DESKTOP_VERSION}"'
require_text "$prerelease" '-PncMacosPackageVersion="${RELEASE_DESKTOP_VERSION}"'
require_text "$prerelease" 'Require protected Android signing secrets'
require_text "$prerelease" 'if [[ -z "${!secret_name}" ]]; then'
require_text "$prerelease" 'tools/verify-android-artifact-metadata.sh'
require_text "$prerelease" 'tools/verify-android-app-bundle-metadata.sh'
require_text "$prerelease" 'bundletool-all-1.18.3.jar'
require_text "$prerelease" 'a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29'
require_text "$prerelease" 'tools/promote-android-update-channel.sh'
require_text "$prerelease" 'tools/verify-android-update-manifest-assets.sh'
require_text "$prerelease" 'channel-prerelease'
require_text "$prerelease" 'already-published: ${{ steps.release.outputs.already-published }}'
require_text "$prerelease" 'Published prerelease ${GITHUB_REF_NAME} is immutable; no assets or metadata were changed.'
require_text "$prerelease" 'Retaining staged asset ${name}.'
require_text "$prerelease" 'canonical-release-assets'
require_text "$prerelease" 'if: needs.stage-assets.outputs.already-published != '\''true'\'''
require_text "$promotion" 'channel-prerelease)'
require_text "$promotion" 'channel-nightly)'
require_text "$promotion" 'current_code >= candidate_code'
require_text "$promotion" 'pointer_state'
require_text "$promotion" '--clobber'
require_text "$promotion" 'test "$release_state" = $'\''false\ttrue\t'\''"$immutable_tag"'
bash -n "$promotion"

if grep -Fq 'cmp "${asset}" "${RUNNER_TEMP}/existing/${name}"' "$nightly"; then
    echo "Draft recovery must retain previously staged package assets." >&2
    exit 1
fi

tag="nightly-20260726-1430-run42-abcdef12"
version_code="$("$project_root/tools/derive-android-version-code.sh" 42 nightly)"
manifest="$temporary_directory/update-manifest.json"
GITHUB_REPOSITORY="Obiente/nc-native" \
    "$project_root/tools/create-android-update-manifest.sh" \
    "$manifest" \
    "nightly-v1" \
    "$tag" \
    "$tag" \
    "$version_code" \
    "nextcloud-native-${tag}-android.apk" \
    "123456" \
    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" \
    '["bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"]'

jq -e --arg tag "$tag" --argjson code "$version_code" '
  .schemaVersion == 1 and
  .channel == "nightly-v1" and
  .versionName == $tag and
  .versionCode == $code and
  .apkUrl ==
    ("https://github.com/Obiente/nc-native/releases/download/" + $tag +
      "/nextcloud-native-" + $tag + "-android.apk") and
  .releaseNotesUrl ==
    ("https://github.com/Obiente/nc-native/releases/tag/" + $tag)
' "$manifest" >/dev/null

"$project_root/tools/test-android-version-code.sh"
"$project_root/tools/test-desktop-package-version.sh"
"$project_root/tools/test-android-artifact-metadata.sh"
"$project_root/tools/test-android-update-manifest-assets.sh"

printf 'Nightly publisher and immutable update-manifest checks passed.\n'
