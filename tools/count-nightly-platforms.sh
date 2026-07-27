#!/usr/bin/env bash
set -euo pipefail

staged_assets="${1:?Staged asset directory is required.}"
existing_asset_names="${2:?Existing release asset list is required.}"

successful=0
available=()

platform_available() {
    local platform="$1"
    local current=false
    local existing=false
    case "$platform" in
        android)
            mapfile -d '' current_apks < <(
                find "$staged_assets" -maxdepth 1 -type f \
                    -name 'nextcloud-native-*-android.apk' -print0
            )
            current_manifest=false
            [[ -f "$staged_assets/update-manifest.json" ]] && current_manifest=true
            if [[ "${#current_apks[@]}" -gt 0 || "$current_manifest" = true ]]; then
                if [[ "${#current_apks[@]}" -ne 1 || "$current_manifest" != true ]]; then
                    echo "Android nightly assets must contain exactly one APK and its update manifest." >&2
                    exit 1
                fi
                current=true
            fi
            existing_apks="$(
                grep -Ec '^nextcloud-native-.*-android\.apk$' "$existing_asset_names" || true
            )"
            existing_manifests="$(
                grep -Fxc 'update-manifest.json' "$existing_asset_names" || true
            )"
            if [[ "$existing_apks" -gt 0 || "$existing_manifests" -gt 0 ]]; then
                if [[ "$existing_apks" -ne 1 || "$existing_manifests" -ne 1 ]]; then
                    if [[ "$current" != true ]]; then
                        echo "Existing Android nightly assets are incomplete or ambiguous." >&2
                        exit 1
                    fi
                else
                    existing=true
                fi
            fi
            ;;
        linux)
            current_deb="$(
                find "$staged_assets" -maxdepth 1 -type f -name '*.deb' | wc -l
            )"
            current_rpm="$(
                find "$staged_assets" -maxdepth 1 -type f -name '*.rpm' | wc -l
            )"
            [[ "$current_deb" -gt 0 && "$current_rpm" -gt 0 ]] && current=true
            existing_deb="$(grep -Ec '\.deb$' "$existing_asset_names" || true)"
            existing_rpm="$(grep -Ec '\.rpm$' "$existing_asset_names" || true)"
            [[ "$existing_deb" -gt 0 && "$existing_rpm" -gt 0 ]] && existing=true
            ;;
        windows)
            current_msi="$(
                find "$staged_assets" -maxdepth 1 -type f -name '*.msi' | wc -l
            )"
            existing_msi="$(grep -Ec '\.msi$' "$existing_asset_names" || true)"
            [[ "$current_msi" -eq 1 ]] && current=true
            [[ "$existing_msi" -eq 1 ]] && existing=true
            ;;
        macos)
            current_dmg="$(
                find "$staged_assets" -maxdepth 1 -type f -name '*.dmg' | wc -l
            )"
            existing_dmg="$(grep -Ec '\.dmg$' "$existing_asset_names" || true)"
            [[ "$current_dmg" -eq 1 ]] && current=true
            [[ "$existing_dmg" -eq 1 ]] && existing=true
            ;;
        *)
            printf 'Unsupported nightly platform: %s\n' "$platform" >&2
            exit 1
            ;;
    esac
    [[ "$current" = true || "$existing" = true ]]
}

for platform in android linux windows macos; do
    if platform_available "$platform"; then
        successful=$((successful + 1))
        available+=("$platform")
    fi
done

printf 'successful=%s\n' "$successful"
printf 'available=%s\n' "$(IFS=,; echo "${available[*]}")"
