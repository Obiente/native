#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" -ne 7 ]]; then
    printf 'Usage: %s MANIFEST APK REPOSITORY TAG CHANNEL VERSION_NAME VERSION_CODE\n' "$0" >&2
    exit 2
fi

manifest="$1"
apk="$2"
repository="$3"
tag="$4"
channel="$5"
version_name="$6"
version_code="$7"

[[ -f "$manifest" ]]
[[ -f "$apk" ]]
[[ "$repository" == "Obiente/nc-native" ]]
[[ "$tag" =~ ^(v0\.[0-9]+\.[0-9]+-(alpha|beta|rc)\.[1-9][0-9]*|nightly-[0-9]{8}-[0-9]{4}-run[1-9][0-9]*-[a-f0-9]{8})$ ]]
[[ "$channel" == "prerelease-v1" || "$channel" == "nightly-v1" ]]
[[ -n "$version_name" && "$version_name" != *$'\n'* ]]
[[ "$version_code" =~ ^[1-9][0-9]*$ ]]

apk_name="$(basename "$apk")"
apk_size="$(stat --format='%s' "$apk")"
apk_sha256="$(sha256sum "$apk" | awk '{print $1}')"
apk_url="https://github.com/${repository}/releases/download/${tag}/${apk_name}"
release_notes_url="https://github.com/${repository}/releases/tag/${tag}"

jq -e \
    --arg channel "$channel" \
    --arg version_name "$version_name" \
    --argjson version_code "$version_code" \
    --arg apk_url "$apk_url" \
    --argjson apk_size "$apk_size" \
    --arg apk_sha256 "$apk_sha256" \
    --arg release_notes_url "$release_notes_url" \
    '
      keys == [
        "apkSha256", "apkSize", "apkUrl", "channel", "minimumAndroidSdk",
        "packageName", "releaseNotesUrl", "schemaVersion",
        "signingCertificateSha256Digests", "versionCode", "versionName"
      ] and
      .schemaVersion == 1 and
      .channel == $channel and
      .versionName == $version_name and
      .versionCode == $version_code and
      .packageName == "dev.obiente.nextcloudnative" and
      .minimumAndroidSdk == 26 and
      .apkUrl == $apk_url and
      .apkSize == $apk_size and
      .apkSha256 == $apk_sha256 and
      .releaseNotesUrl == $release_notes_url and
      (.signingCertificateSha256Digests | type == "array" and length > 0) and
      all(
        .signingCertificateSha256Digests[];
        type == "string" and test("^[a-f0-9]{64}$")
      )
    ' \
    "$manifest" >/dev/null

printf 'Verified Android update manifest against staged APK: %s\n' "$apk"
