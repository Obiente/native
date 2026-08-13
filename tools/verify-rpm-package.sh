#!/usr/bin/env bash
set -euo pipefail

package="${1:?RPM package is required.}"

if [[ "$#" -ne 1 || ! -f "$package" || "$package" != *.rpm ]]; then
    printf 'Expected exactly one existing RPM package.\n' >&2
    exit 2
fi
for required_command in rpm rpm2cpio cpio python3; do
    if ! command -v "$required_command" >/dev/null 2>&1; then
        printf '%s is required to verify the RPM package.\n' "$required_command" >&2
        exit 2
    fi
done

if ! rpm -qp --requires "$package" |
    grep -Eq '^libsecret([[:space:](]|$)'; then
    printf 'RPM package must require libsecret for secure credential storage.\n' >&2
    exit 1
fi

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

temporary="$(mktemp -d)"
trap 'rm -r -- "$temporary"' EXIT
metadata_path=/usr/share/metainfo/dev.obiente.nextcloudnative.metainfo.xml
desktop_path=/usr/share/applications/nextcloudnative-NextcloudNative.desktop
icon_path=/usr/share/icons/hicolor/512x512/apps/dev.obiente.nextcloudnative.png
rpm2cpio "$package" |
    cpio -i --quiet --to-stdout ".${metadata_path}" >"$temporary/metainfo.xml"
rpm2cpio "$package" |
    cpio -i --quiet --to-stdout ".${desktop_path}" >"$temporary/application.desktop"
rpm2cpio "$package" |
    cpio -i --quiet --to-stdout ".${icon_path}" >"$temporary/icon.png"
package_version="$(rpm -qp --queryformat '%{VERSION}' "$package")"

python3 - \
    "$temporary/metainfo.xml" \
    "$temporary/application.desktop" \
    "$temporary/icon.png" \
    "$package_version" <<'PY'
import struct
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

metadata_path, desktop_path, icon_path, package_version = sys.argv[1:]
root = ET.parse(metadata_path).getroot()
assert root.tag == "component"
assert root.attrib.get("type") == "desktop-application"
assert root.findtext("id") == "dev.obiente.nextcloudnative"
assert root.findtext("pkgname") == "nextcloudnative"
assert root.findtext("name") == "Nextcloud Native"
assert root.findtext("launchable") == "nextcloudnative-NextcloudNative.desktop"
assert root.find("icon[@type='stock']").text == "dev.obiente.nextcloudnative"
assert root.find("icon[@type='remote']").text == "https://nc-native.obiente.dev/icon-512.png"
assert any(
    release.attrib.get("version") == package_version
    for release in root.findall("./releases/release")
)
assert len(root.findall("./screenshots/screenshot")) >= 3

desktop_lines = set(Path(desktop_path).read_text(encoding="UTF-8").splitlines())
assert "Name=Nextcloud Native" in desktop_lines
assert "Icon=dev.obiente.nextcloudnative" in desktop_lines

with Path(icon_path).open("rb") as icon:
    assert icon.read(16) == b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR"
    width, height = struct.unpack(">II", icon.read(8))
assert width == 512 and height == 512
PY

printf 'Verified RPM payload metadata, artwork, release identity, and global build-ID safety: %s\n' \
    "$package"
