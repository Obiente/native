#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
nightly="$project_root/.github/workflows/nightly.yml"
prerelease="$project_root/.github/workflows/prerelease.yml"
nightly_notes="$project_root/tools/nightly-release-notes.mjs"
promotion="$project_root/tools/promote-app-update-channel.sh"
msi_repackager="$project_root/tools/repackage-msi-with-uninstall-cleanup.ps1"
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
require_text "$nightly" 'group: app-nightly-channel'
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
require_text "$nightly" 'name: Verify unsigned Windows MSI'
require_text "$nightly" 'tools/verify-windows-package.ps1'
require_text "$nightly" 'uses: actions/attest@508db95dd578ae2727ebd6217d5ba78e4fbda05d # v4.2.1'
require_text "$nightly" 'subject-path: ui/build/compose/binaries/main/msi/*.msi'
require_text "$nightly" 'artifact-metadata: write'
require_text "$nightly" 'attestations: write'
require_text "$nightly" 'id-token: write'
require_text "$nightly" 'tasks: ":ui:packageDmg"'
require_text "$nightly" 'tools/derive-desktop-package-version.sh'
require_text "$nightly" 'source_sequence="$(git rev-list --count "${SOURCE_SHA}")"'
require_text "$nightly" 'release_date="$(date --utc --date="${SOURCE_STARTED_AT}" +%F)"'
require_text "$nightly" 'release-date: ${{ steps.source.outputs.release_date }}'
require_text "$nightly" '-PncAppStreamReleaseDate="${{ needs.source.outputs.release-date }}"'
require_text "$nightly" '-PncDesktopPackageVersion="${NIGHTLY_DESKTOP_VERSION}"'
require_text "$nightly" '-PncMacosPackageVersion="${NIGHTLY_DESKTOP_VERSION}"'
require_text "$nightly" '-PncDesktopReleaseBuild=true'
require_text "$nightly" '-PncDirectDesktopPackageUpdates="${{ matrix.direct_updates }}"'
require_text "$nightly" 'direct_updates: "true"'
require_text "$nightly" 'name: nextcloud-native-${{ matrix.platform }}'
require_text "$nightly" 'name: nextcloud-native-android'
require_text "$nightly" 'tools/stage-nightly-assets.sh artifacts dist'
require_text "$nightly" 'tools/count-nightly-platforms.sh'
require_text "$nightly" 'node tools/nightly-release-notes.mjs'
require_text "$nightly" '--available "${available_platforms}"'
require_text "$nightly" '--source-sha "${NIGHTLY_SHA}"'
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
require_text "$nightly" 'successful-platforms: ${{ steps.release.outputs.successful }}'
require_text "$nightly" 'available-platforms: ${{ steps.release.outputs.available }}'
require_text "$nightly" 'Published nightly ${NIGHTLY_TAG} is immutable; no assets or metadata were changed.'
require_text "$nightly" 'tag_ref_response="${RUNNER_TEMP}/nightly-tag-ref.json"'
require_text "$nightly" 'elif jq -e '\''.status == 404 or .status == "404"'\'''
require_text "$nightly" 'cat "${tag_ref_error}" >&2'
require_text "$nightly" 'canonical-release-assets'
require_text "$nightly" 'if [[ "${canonical_successful}" -ge 3 ]]; then'
require_text "$nightly" 'if: needs.stage-assets.outputs.already-published != '\''true'\'''
require_text "$nightly" 'tools/promote-app-update-channel.sh'
require_text "$nightly" 'tools/verify-android-update-manifest-assets.sh'
require_text "$nightly" 'tools/verify-desktop-update-manifest-assets.sh'
require_text "$nightly_notes" 'The Windows MSI is currently unsigned.'
require_text "$nightly" 'if tools/has-direct-desktop-update-assets.sh "${canonical}"; then'
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
require_text "$prerelease" 'group: app-prerelease-channel'
require_text "$prerelease" 'tools/derive-desktop-package-version.sh'
require_text "$prerelease" 'source_sequence="$(git rev-list --count "${GITHUB_SHA}")"'
require_text "$prerelease" 'gh api "repos/${GITHUB_REPOSITORY}/actions/runs/${GITHUB_RUN_ID}"'
require_text "$prerelease" 'release_date="$(date --utc --date="${release_started_at}" +%F)"'
require_text "$prerelease" 'release-date: ${{ steps.version-code.outputs.release_date }}'
require_text "$prerelease" '-PncAppStreamReleaseDate="${{ needs.authorize-signing.outputs.release-date }}"'
require_text "$prerelease" '-PncDesktopPackageVersion="${RELEASE_DESKTOP_VERSION}"'
require_text "$prerelease" '-PncMacosPackageVersion="${RELEASE_DESKTOP_VERSION}"'
require_text "$prerelease" '-PncDirectDesktopPackageUpdates="${{ matrix.direct_updates }}"'
require_text "$prerelease" 'name: Verify unsigned Windows MSI'
require_text "$prerelease" 'tools/verify-windows-package.ps1'
require_text "$prerelease" 'uses: actions/attest@508db95dd578ae2727ebd6217d5ba78e4fbda05d # v4.2.1'
require_text "$prerelease" 'subject-path: ui/build/compose/binaries/main/msi/*.msi'
require_text "$prerelease" 'artifact-metadata: write'
require_text "$prerelease" 'attestations: write'
require_text "$prerelease" 'id-token: write'
require_text "$prerelease" 'Require protected Android signing secrets'
require_text "$prerelease" 'if [[ -z "${!secret_name}" ]]; then'
require_text "$prerelease" 'tools/verify-android-artifact-metadata.sh'
require_text "$prerelease" 'tools/verify-android-app-bundle-metadata.sh'
require_text "$prerelease" 'bundletool-all-1.18.3.jar'
require_text "$prerelease" 'a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29'
require_text "$prerelease" 'tools/promote-app-update-channel.sh'
require_text "$prerelease" 'tools/verify-android-update-manifest-assets.sh'
require_text "$prerelease" 'tools/verify-desktop-update-manifest-assets.sh'
require_text "$prerelease" 'The Windows MSI is currently unsigned.'
require_text "$prerelease" 'tools/create-winget-manifests.ps1'
require_text "$prerelease" 'name: winget-manifest-candidate-${{ github.ref_name }}'
require_text "$prerelease" 'if tools/has-direct-desktop-update-assets.sh "${canonical}"; then'
require_text "$prerelease" 'channel-prerelease'
require_text "$prerelease" 'already-published: ${{ steps.release.outputs.already-published }}'
require_text "$prerelease" 'Published prerelease ${GITHUB_REF_NAME} is immutable; no assets or metadata were changed.'
require_text "$prerelease" 'Retaining staged asset ${name}.'
require_text "$prerelease" 'canonical-release-assets'
require_text "$prerelease" 'if: needs.stage-assets.outputs.already-published != '\''true'\'''
require_text "$promotion" 'channel-prerelease)'
require_text "$promotion" 'channel-nightly)'
require_text "$promotion" 'if .versionCode >= $candidate then "keep" else "replace" end'
if grep -Eq '\(\([^)]*current_code|\(\([^)]*candidate_codes' "$promotion"; then
    echo "Update pointer version codes must not reach shell arithmetic." >&2
    exit 1
fi
require_text "$promotion" 'pointer_state'
require_text "$promotion" '--clobber'
require_text "$promotion" 'test "$release_state" = $'\''false\ttrue\t'\''"$immutable_tag"'
require_text "$msi_repackager" 'Join-Path $AppImage "app/.jpackage.xml"'
if grep -Fq 'Join-Path $AppImage "lib/app/.jpackage.xml"' "$msi_repackager"; then
    echo "Windows MSI repackaging must use the Windows jpackage metadata layout." >&2
    exit 1
fi
bash -n "$promotion"

if [[ -e "$project_root/tools/sign-windows-package.ps1" ]]; then
    echo "The unsigned Windows release path must not retain a PFX signing helper." >&2
    exit 1
fi
for workflow in "$nightly" "$prerelease"; do
    if grep -Fq 'WINDOWS_SIGNING_CERTIFICATE_' "$workflow"; then
        echo "Unsigned Windows releases must not require unavailable certificate secrets." >&2
        exit 1
    fi
done

for workflow in "$nightly" "$prerelease"; do
    [[ "$(grep -Fc 'direct_updates: "true"' "$workflow")" -eq 2 ]]
    [[ "$(grep -Fc 'direct_updates: "false"' "$workflow")" -eq 1 ]]
done

if grep -Fq 'cmp "${asset}" "${RUNNER_TEMP}/existing/${name}"' "$nightly"; then
    echo "Draft recovery must retain previously staged package assets." >&2
    exit 1
fi
if grep -Fq -- '--jq '\''.object.sha'\'' 2>/dev/null || true' "$nightly"; then
    echo "Missing tag references must not turn GitHub 404 JSON into a commit SHA." >&2
    exit 1
fi
if grep -Fq 'find artifacts -mindepth 2 -maxdepth 2 -type f' "$nightly"; then
    echo "Nightly staging must not assume that every package is exactly one directory deep." >&2
    exit 1
fi

stage_assets="$(
    sed -n '/^  stage-assets:/,/^  release-quorum:/p' "$nightly"
)"
if ! grep -Fq 'uses: actions/checkout@' <<<"$stage_assets" ||
    ! grep -Fq 'ref: ${{ needs.source.outputs.sha }}' <<<"$stage_assets" ||
    ! grep -Fq 'persist-credentials: false' <<<"$stage_assets"; then
    echo "Nightly asset verification requires the exact tested repository source." >&2
    exit 1
fi
checkout_line="$(
    grep -n -m1 'uses: actions/checkout@' <<<"$stage_assets" | cut -d: -f1
)"
download_line="$(
    grep -n -m1 'uses: actions/download-artifact@' <<<"$stage_assets" | cut -d: -f1
)"
verify_line="$(
    grep -n -m1 'tools/verify-android-update-manifest-assets.sh' <<<"$stage_assets" |
        cut -d: -f1
)"
if [[ "$checkout_line" -ge "$download_line" || "$checkout_line" -ge "$verify_line" ]]; then
    echo "The exact-source checkout must precede downloads and repository tools." >&2
    exit 1
fi
if grep -Fq 'successful-platforms: ${{ steps.platforms.outputs.successful }}' \
    <<<"$stage_assets"; then
    echo "Nightly quorum must use the canonical release inventory." >&2
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
"$project_root/tools/test-nightly-asset-staging.sh"
node --test "$project_root/tools/nightly-release-notes.test.mjs"

printf 'Nightly publisher and immutable update-manifest checks passed.\n'
