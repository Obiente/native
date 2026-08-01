#!/usr/bin/env bash
set -euo pipefail

package="${1:?RPM package is required.}"

if [[ "$#" -ne 1 || ! -f "$package" || "$package" != *.rpm ]]; then
    printf 'Expected exactly one existing RPM package.\n' >&2
    exit 2
fi
for required_command in rpm2cpio cpio; do
    if ! command -v "$required_command" >/dev/null 2>&1; then
        printf '%s is required to verify the RPM package.\n' "$required_command" >&2
        exit 2
    fi
done

package_files="$(rpm2cpio "$package" | cpio -it --quiet | sed 's#^\./#/#')"
build_id_paths="$({
    printf '%s\n' "$package_files" |
        grep -E '^/usr/lib/\.build-id(/|$)' || true
})"

if [[ -n "$build_id_paths" ]]; then
    printf 'RPM packages must not claim global build-ID paths:\n%s\n' \
        "$build_id_paths" >&2
    exit 1
fi

required_desktop_assets=(
    /usr/share/applications/nextcloudnative-NextcloudNative.desktop
    /usr/share/icons/hicolor/512x512/apps/dev.obiente.nextcloudnative.png
    /usr/share/metainfo/dev.obiente.nextcloudnative.metainfo.xml
)
missing_desktop_assets=()
for asset in "${required_desktop_assets[@]}"; do
    if ! grep -Fxq "$asset" <<<"$package_files"; then
        missing_desktop_assets+=("$asset")
    fi
done

if [[ "${#missing_desktop_assets[@]}" -gt 0 ]]; then
    printf 'RPM package is missing required desktop integration assets:\n' >&2
    printf '%s\n' "${missing_desktop_assets[@]}" >&2
    exit 1
fi

printf 'Verified RPM payload desktop integration and global build-ID safety: %s\n' \
    "$package"
