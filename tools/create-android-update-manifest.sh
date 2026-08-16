#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" -ne 9 ]]; then
    printf 'Usage: %s OUTPUT CHANNEL TAG VERSION VERSION_CODE APK_NAME APK_SIZE APK_SHA256 SIGNER_DIGESTS_JSON\n' "$0" >&2
    exit 2
fi

output="$1"
channel="$2"
tag="$3"
version="$4"
version_code="$5"
apk_name="$6"
apk_size="$7"
apk_sha256="$8"
signer_digests_json="$9"
repository="${GITHUB_REPOSITORY:-Obiente/nc-native}"

[[ "$repository" == "Obiente/nc-native" ]]
case "$channel" in
    prerelease-v1)
        [[ "$version" =~ ^0\.[0-9]+\.[0-9]+-(alpha|beta|rc)\.[0-9]+$ ]]
        [[ "$tag" == "v${version}" ]]
        ;;
    nightly-v1)
        [[ "$tag" =~ ^nightly-[0-9]{8}-[0-9]{4}-run[1-9][0-9]*-[a-f0-9]{8}$ ]]
        [[ "$version" == "$tag" ]]
        ;;
    *)
        printf 'Unsupported Android update manifest channel: %s\n' "$channel" >&2
        exit 2
        ;;
esac
[[ "$version_code" =~ ^[1-9][0-9]*$ ]]
[[ "$apk_name" == "nextcloud-native-${version}-android.apk" ]]
[[ "$apk_size" =~ ^[1-9][0-9]*$ ]]
[[ "$apk_sha256" =~ ^[a-f0-9]{64}$ ]]
jq -e '
  type == "array" and
  length >= 1 and
  length <= 8 and
  length == (unique | length) and
  all(.[]; type == "string" and test("^[a-f0-9]{64}$"))
' <<<"$signer_digests_json" >/dev/null
changes="$(node "$(dirname "$0")/update-changelog.mjs" "$version_code")"

mkdir -p "$(dirname "$output")"
jq -n \
  --argjson schemaVersion 1 \
  --arg channel "$channel" \
  --arg versionName "$version" \
  --argjson versionCode "$version_code" \
  --arg packageName "dev.obiente.nextcloudnative" \
  --argjson minimumAndroidSdk 26 \
  --arg apkUrl "https://github.com/${repository}/releases/download/${tag}/${apk_name}" \
  --argjson apkSize "$apk_size" \
  --arg apkSha256 "$apk_sha256" \
  --argjson signingCertificateSha256Digests "$signer_digests_json" \
  --arg releaseNotesUrl "https://github.com/${repository}/releases/tag/${tag}" \
  --argjson changes "$changes" \
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
    releaseNotesUrl: $releaseNotesUrl,
    changes: $changes
  }' >"$output"
