#!/usr/bin/env bash
set -euo pipefail

artifact_root="${1:?Artifact download directory is required.}"
destination_root="${2:?Release staging directory is required.}"

mkdir -p -- "$artifact_root" "$destination_root"
if find "$destination_root" -mindepth 1 -print -quit | grep -q .; then
    printf 'Nightly staging destination must be empty: %s\n' "$destination_root" >&2
    exit 1
fi

declare -a staged_sources=()
declare -A staged_names=()

register_asset() {
    local source="$1"
    local name
    local canonical_name
    name="$(basename "$source")"
    canonical_name="${name,,}"
    if [[ -n "${staged_names[$canonical_name]:-}" ]]; then
        printf 'Conflicting nightly assets share the case-insensitive name %s.\n' \
            "$name" >&2
        exit 1
    fi
    staged_names["$canonical_name"]="$source"
    staged_sources+=("$source")
}

inspect_artifact() {
    local artifact_directory="$1"
    local platform="$2"
    [[ -d "$artifact_directory" ]] || return 0
    while IFS= read -r -d '' entry; do
        if [[ -L "$entry" ]]; then
            printf 'Nightly artifacts must not contain symlinks: %s\n' "$entry" >&2
            exit 1
        fi
        local name
        name="$(basename "$entry")"
        case "$platform:$name" in
            android:nextcloud-native-*-android.apk|android:update-manifest.json)
                register_asset "$entry"
                ;;
            android:android-apk.sha256|android:android-certificate.sha256)
                ;;
            linux:*.deb|linux:*.rpm|windows:*.msi|macos:*.dmg)
                register_asset "$entry"
                ;;
            *)
                printf 'Unexpected %s nightly artifact: %s\n' "$platform" "$entry" >&2
                exit 1
                ;;
        esac
    done < <(
        find "$artifact_directory" \( -type f -o -type l \) -print0 | sort -z
    )
}

inspect_artifact "$artifact_root/nextcloud-native-android" android
inspect_artifact "$artifact_root/nextcloud-native-linux" linux
inspect_artifact "$artifact_root/nextcloud-native-windows" windows
inspect_artifact "$artifact_root/nextcloud-native-macos" macos

for source in "${staged_sources[@]}"; do
    cp -- "$source" "$destination_root/$(basename "$source")"
done
