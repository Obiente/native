#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" -ne 7 ]]; then
    printf 'Usage: %s OUTPUT CHANNEL TAG VERSION VERSION_CODE PACKAGE_VERSION ASSET_DIRECTORY\n' "$0" >&2
    exit 2
fi

output="$1"
channel="$2"
tag="$3"
version="$4"
version_code="$5"
package_version="$6"
asset_directory="$7"
repository="${GITHUB_REPOSITORY:-Obiente/nc-native}"

[[ "$repository" == "Obiente/nc-native" ]]
[[ -d "$asset_directory" ]]
case "$channel" in
    prerelease-v1)
        [[ "$version" =~ ^0\.[0-9]+\.[0-9]+-(alpha|beta|rc)\.[1-9][0-9]*$ ]]
        [[ "$tag" == "v${version}" ]]
        ;;
    nightly-v1)
        [[ "$tag" =~ ^nightly-[0-9]{8}-[0-9]{4}-run[1-9][0-9]*-[a-f0-9]{8}$ ]]
        [[ "$version" == "$tag" ]]
        ;;
    *)
        printf 'Unsupported desktop update manifest channel: %s\n' "$channel" >&2
        exit 2
        ;;
esac
[[ "$version_code" =~ ^[1-9][0-9]*$ ]]
[[ "$package_version" =~ ^[1-9][0-9]*\.[0-9]+\.[0-9]+$ ]]

temporary="$(mktemp -d)"
trap 'rm -r -- "$temporary"' EXIT
assets_json="$temporary/assets.jsonl"
: >"$assets_json"

append_asset() {
    local file="$1"
    local platform="$2"
    local format="$3"
    local architecture="$4"
    local name size digest
    name="$(basename "$file")"
    size="$(stat --format='%s' "$file")"
    digest="$(sha256sum "$file" | awk '{print $1}')"
    [[ "$size" =~ ^[1-9][0-9]*$ ]]
    [[ "$digest" =~ ^[a-f0-9]{64}$ ]]
    jq -cn \
        --arg platform "$platform" \
        --arg format "$format" \
        --arg architecture "$architecture" \
        --arg url "https://github.com/${repository}/releases/download/${tag}/${name}" \
        --argjson size "$size" \
        --arg sha256 "$digest" \
        '{platform:$platform,format:$format,architecture:$architecture,url:$url,size:$size,sha256:$sha256}' \
        >>"$assets_json"
}

while IFS= read -r -d '' asset; do
    name="$(basename "$asset")"
    case "$name" in
        *.x86_64.rpm) append_asset "$asset" linux rpm x86_64 ;;
        *.aarch64.rpm) append_asset "$asset" linux rpm aarch64 ;;
        *_amd64.deb) append_asset "$asset" linux deb x86_64 ;;
        *_arm64.deb) append_asset "$asset" linux deb aarch64 ;;
        *.msi) append_asset "$asset" windows msi x86_64 ;;
        *.dmg) append_asset "$asset" macos dmg x86_64 ;;
    esac
done < <(find "$asset_directory" -maxdepth 1 -type f -print0 | sort -z)

assets="$(jq -s '.' "$assets_json")"
jq -e '
  length >= 1 and length <= 8 and
  (map([.platform,.format,.architecture] | join(":")) | length == (unique | length))
' <<<"$assets" >/dev/null

mkdir -p "$(dirname "$output")"
jq -n \
    --argjson schemaVersion 1 \
    --arg channel "$channel" \
    --arg versionName "$version" \
    --argjson versionCode "$version_code" \
    --arg packageVersion "$package_version" \
    --arg releaseNotesUrl "https://github.com/${repository}/releases/tag/${tag}" \
    --argjson assets "$assets" \
    '{schemaVersion:$schemaVersion,channel:$channel,versionName:$versionName,versionCode:$versionCode,packageVersion:$packageVersion,releaseNotesUrl:$releaseNotesUrl,assets:$assets}' \
    >"$output"
