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
declare -A published_aliases=()
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
        published_aliases["${aliases[$platform]}"]=true
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

existing_assets="$(
    gh release view "$pointer_tag" --repo "$repository" --json assets --jq '.assets[].name'
)"
for alias in "${aliases[@]}"; do
    if [[ -z "${published_aliases[$alias]:-}" ]] && grep -Fxq "$alias" <<<"$existing_assets"; then
        gh release delete-asset "$pointer_tag" "$alias" --repo "$repository" --yes
    fi
done
gh release upload "$pointer_tag" "${uploads[@]}" --repo "$repository" --clobber
