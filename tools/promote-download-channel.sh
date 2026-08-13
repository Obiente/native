#!/usr/bin/env bash
set -euo pipefail

repository="${1:?Repository is required.}"
pointer_tag="${2:?Pointer tag is required.}"
immutable_tag="${3:?Immutable tag is required.}"
asset_directory="${4:?Asset directory is required.}"

[[ "$repository" == "Obiente/nc-native" ]]
[[ "$pointer_tag" == "channel-nightly" ]]
[[ "$immutable_tag" =~ ^nightly-[0-9]{8}-[0-9]{4}-run[1-9][0-9]*-[a-f0-9]{8}$ ]]
[[ -d "$asset_directory" ]]

declare -A aliases=(
    [android]="nextcloud-native-android.apk"
    [linux-deb]="nextcloud-native-linux-amd64.deb"
    [linux-rpm]="nextcloud-native-linux-x86_64.rpm"
    [windows]="nextcloud-native-windows-x86_64.msi"
    [macos]="nextcloud-native-macos-intel.dmg"
)

find_single() {
    local pattern="$1"
    mapfile -d '' matches < <(find "$asset_directory" -maxdepth 1 -type f -name "$pattern" -print0)
    [[ "${#matches[@]}" -le 1 ]]
    [[ "${#matches[@]}" -eq 1 ]] || return 1
    printf '%s\n' "${matches[0]}"
}

temporary="$(mktemp -d)"
trap 'rm -r -- "$temporary"' EXIT
declare -a uploads=()
for platform in android linux-deb linux-rpm windows macos; do
    case "$platform" in
        android) pattern='nextcloud-native-*-android.apk' ;;
        linux-deb) pattern='*.deb' ;;
        linux-rpm) pattern='*.rpm' ;;
        windows) pattern='*.msi' ;;
        macos) pattern='*.dmg' ;;
    esac
    if source="$(find_single "$pattern")"; then
        destination="$temporary/${aliases[$platform]}"
        cp -- "$source" "$destination"
        uploads+=("$destination")
    fi
done
[[ "${#uploads[@]}" -gt 0 ]]

release_state="$(
    gh release view "$immutable_tag" --repo "$repository" \
        --json isDraft,isPrerelease,tagName \
        --jq '[.isDraft, .isPrerelease, .tagName] | @tsv'
)"
test "$release_state" = $'false\ttrue\t'"$immutable_tag"
pointer_state="$(
    gh release view "$pointer_tag" --repo "$repository" \
        --json isDraft,isPrerelease,tagName \
        --jq '[.isDraft, .isPrerelease, .tagName] | @tsv'
)"
test "$pointer_state" = $'false\ttrue\t'"$pointer_tag"

mapfile -t candidate_codes < <(
    find "$asset_directory" -maxdepth 1 -type f \
        \( -name 'update-manifest.json' -o -name 'desktop-update-manifest.json' \) \
        -print0 |
        sort -z |
        xargs -0 -r -n1 jq -er '.versionCode | select(type == "number" and floor == . and . > 0)'
)
[[ "${#candidate_codes[@]}" -gt 0 ]]
candidate_code="${candidate_codes[0]}"
for code in "${candidate_codes[@]}"; do
    [[ "$code" == "$candidate_code" ]]
done
[[ "$candidate_code" =~ ^[1-9][0-9]*$ ]]

mkdir -p "$temporary/existing"
if gh release download "$pointer_tag" \
    --repo "$repository" \
    --pattern download-channel.json \
    --dir "$temporary/existing" >/dev/null 2>&1; then
    current_code="$(
        jq -er \
            --arg channel nightly-v1 \
            '
              select(keys == ["channel", "releaseNotesUrl", "schemaVersion", "versionCode", "versionName"]) |
              select(.schemaVersion == 1 and .channel == $channel) |
              select(.versionCode | type == "number" and floor == . and . > 0) |
              select(.versionName | test("^nightly-[0-9]{8}-[0-9]{4}-run[1-9][0-9]*-[a-f0-9]{8}$")) |
              select(.releaseNotesUrl == "https://github.com/Obiente/nc-native/releases/tag/" + .versionName) |
              .versionCode
            ' \
            "$temporary/existing/download-channel.json"
    )"
    [[ "$current_code" =~ ^[1-9][0-9]*$ ]]
    if (( current_code > candidate_code )); then
        printf 'Download channel already has newer version code %s.\n' "$current_code"
        exit 0
    fi
fi

jq -n \
    --arg version_name "$immutable_tag" \
    --argjson version_code "$candidate_code" \
    '{
      schemaVersion: 1,
      channel: "nightly-v1",
      versionName: $version_name,
      versionCode: $version_code,
      releaseNotesUrl: ("https://github.com/Obiente/nc-native/releases/tag/" + $version_name)
    }' >"$temporary/download-channel.json"

# gh implements --clobber by deleting the old asset before uploading its
# replacement. Back up every affected pointer asset so a failed publication can
# restore the complete previously verified channel rather than leaving a stable
# download URL missing or partially advanced.
publish_files=("$temporary/download-channel.json" "${uploads[@]}")
rollback_directory="$temporary/rollback"
mkdir -p "$rollback_directory"
mapfile -t pointer_assets < <(
    gh release view "$pointer_tag" --repo "$repository" --json assets --jq '.assets[].name'
)
declare -A had_existing=()
for file in "${publish_files[@]}"; do
    name="$(basename "$file")"
    if printf '%s\n' "${pointer_assets[@]}" | grep -Fxq -- "$name"; then
        had_existing["$name"]=1
        gh release download "$pointer_tag" \
            --repo "$repository" \
            --pattern "$name" \
            --dir "$rollback_directory" >/dev/null
    else
        had_existing["$name"]=0
    fi
done

if ! gh release upload "$pointer_tag" "${publish_files[@]}" \
    --repo "$repository" --clobber; then
    restore_failed=0
    for file in "${publish_files[@]}"; do
        name="$(basename "$file")"
        if [[ "${had_existing[$name]}" == 1 ]]; then
            if ! gh release upload "$pointer_tag" "$rollback_directory/$name" \
                --repo "$repository" --clobber; then
                restore_failed=1
            fi
        else
            gh release delete-asset "$pointer_tag" "$name" \
                --repo "$repository" --yes >/dev/null 2>&1 || true
        fi
    done
    if (( restore_failed )); then
        printf 'Download channel upload failed and at least one previous alias could not be restored.\n' >&2
    else
        printf 'Download channel upload failed; previous aliases were restored.\n' >&2
    fi
    exit 1
fi
