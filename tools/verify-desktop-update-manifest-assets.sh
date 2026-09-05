#!/usr/bin/env bash
set -euo pipefail

manifest="${1:?Desktop update manifest is required.}"
asset_directory="${2:?Release asset directory is required.}"
repository="${3:?Repository is required.}"
tag="${4:?Release tag is required.}"
channel="${5:?Manifest channel is required.}"
version="${6:?Version name is required.}"
version_code="${7:?Version code is required.}"
package_version="${8:?Desktop package version is required.}"

source "$(dirname "${BASH_SOURCE[0]}")/release-repository.sh"
[[ -f "$manifest" && -d "$asset_directory" ]]
jq -e \
    --arg channel "$channel" \
    --arg version "$version" \
    --argjson version_code "$version_code" \
    --arg package_version "$package_version" \
    '
      keys == [
        "assets", "channel", "packageVersion", "releaseNotesUrl",
        "schemaVersion", "versionCode", "versionName"
      ] and
      .schemaVersion == 1 and
      .channel == $channel and
      .versionName == $version and
      .versionCode == $version_code and
      .packageVersion == $package_version and
      (.assets |
        type == "array" and
        length >= 1 and length <= 8 and
        all(.[]; keys == ["architecture", "format", "platform", "sha256", "size", "url"])
      ) and
      (.assets | map([.platform,.format,.architecture] | join(":")) | length == (unique | length))
    ' "$manifest" >/dev/null

while IFS=$'\t' read -r url size digest; do
    name="${url##*/}"
    [[ "$url" == "https://github.com/${release_url_repository}/releases/download/${tag}/${name}" ]]
    asset="$asset_directory/$name"
    [[ -f "$asset" ]]
    [[ "$(stat --format='%s' "$asset")" == "$size" ]]
    [[ "$(sha256sum "$asset" | awk '{print $1}')" == "$digest" ]]
done < <(jq -er '.assets[] | [.url, (.size|tostring), .sha256] | @tsv' "$manifest")

printf 'Desktop update manifest matches immutable release assets.\n'
