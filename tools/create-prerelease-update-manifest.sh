#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" -ne 7 ]]; then
    printf 'Usage: %s OUTPUT VERSION VERSION_CODE APK_NAME APK_SIZE APK_SHA256 SIGNER_DIGESTS_JSON\n' "$0" >&2
    exit 2
fi

output="$1"
version="$2"
version_code="$3"
apk_name="$4"
apk_size="$5"
apk_sha256="$6"
signer_digests_json="$7"
repository="${GITHUB_REPOSITORY:-Obiente/nc-native}"

[[ "$repository" == "Obiente/nc-native" ]]
[[ "$version" =~ ^0\.[0-9]+\.[0-9]+-(alpha|beta|rc)\.[0-9]+$ ]]
[[ "$version_code" =~ ^[1-9][0-9]*$ ]]
[[ "$apk_name" =~ ^nextcloud-native-${version}-android\.apk$ ]]
[[ "$apk_size" =~ ^[1-9][0-9]*$ ]]
[[ "$apk_sha256" =~ ^[a-f0-9]{64}$ ]]
jq -e '
  type == "array" and
  length >= 1 and
  length <= 8 and
  length == (unique | length) and
  all(.[]; type == "string" and test("^[a-f0-9]{64}$"))
' <<<"$signer_digests_json" >/dev/null

tag="v${version}"
mkdir -p "$(dirname "$output")"
jq -n \
  --argjson schemaVersion 1 \
  --arg channel "prerelease-v1" \
  --arg versionName "$version" \
  --argjson versionCode "$version_code" \
  --arg packageName "dev.obiente.nextcloudnative" \
  --argjson minimumAndroidSdk 26 \
  --arg apkUrl "https://github.com/Obiente/nc-native/releases/download/${tag}/${apk_name}" \
  --argjson apkSize "$apk_size" \
  --arg apkSha256 "$apk_sha256" \
  --argjson signingCertificateSha256Digests "$signer_digests_json" \
  --arg releaseNotesUrl "https://github.com/Obiente/nc-native/releases/tag/${tag}" \
  '{
    schemaVersion: $schemaVersion,
    channel: $channel,
    versionName: $versionName,
    versionCode: $versionCode,
    packageName: $packageName,
    minimumAndroidSdk: $minimumAndroidSdk,
    apkUrl: $apkUrl,
    apkSize: $apkSize,
    apkSha256: $apkSha256,
    signingCertificateSha256Digests: $signingCertificateSha256Digests,
    releaseNotesUrl: $releaseNotesUrl
  }' >"$output"
