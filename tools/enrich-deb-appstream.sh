#!/usr/bin/env bash
set -euo pipefail

package_directory="${1:?DEB package directory is required.}"
metadata="${2:?AppStream metadata file is required.}"
license="${3:?Project license file is required.}"
icon="${4:?Application icon file is required.}"

mapfile -d '' packages < <(find "$package_directory" -maxdepth 1 -type f -name '*.deb' -print0)
if [[ "${#packages[@]}" -ne 1 ]]; then
    printf 'Expected exactly one DEB package in %s, found %s.\n' \
        "$package_directory" "${#packages[@]}" >&2
    exit 1
fi
[[ -f "$metadata" ]]
[[ -f "$license" ]]
[[ -f "$icon" ]]

temporary="$(mktemp -d)"
trap 'rm -r -- "$temporary"' EXIT
root="$temporary/root"
rebuilt="$temporary/$(basename "${packages[0]}")"

dpkg-deb --raw-extract "${packages[0]}" "$root"
install -D -m 0644 "$metadata" \
    "$root/usr/share/metainfo/dev.obiente.nextcloudnative.metainfo.xml"
install -D -m 0644 "$license" "$root/usr/share/doc/nextcloudnative/copyright"
control="$root/DEBIAN/control"
if ! grep -q '^Depends:' "$control"; then
    sed -i '/^Description:/i Depends: libsecret-tools' "$control"
elif ! sed -n '/^Depends:/p' "$control" |
    tr ',' '\n' |
    sed 's/^[[:space:]]*//; s/[[:space:]]*$//' |
    grep -Eq '^libsecret-tools([[:space:](]|$)'; then
    sed -i '/^Depends:/ s/$/, libsecret-tools/' "$control"
fi
if grep -q '^Homepage:' "$control"; then
    sed -i 's|^Homepage:.*|Homepage: https://nc-native.obiente.dev/|' "$control"
else
    sed -i '/^Description:/i Homepage: https://nc-native.obiente.dev/' "$control"
fi
mapfile -d '' desktop_entries < <(
    find "$root" -type f -name 'nextcloudnative-NextcloudNative.desktop' -print0
)
[[ "${#desktop_entries[@]}" -eq 1 ]]
desktop_entry="${desktop_entries[0]}"
sed -i \
    -e 's|^Comment=.*|Comment=One native client for your complete Nextcloud account|' \
    -e 's|^Categories=.*|Categories=Network;FileTransfer;Utility;|' \
    -e 's|^Icon=.*|Icon=dev.obiente.nextcloudnative|' \
    "$desktop_entry"
if ! grep -q '^Keywords=' "$desktop_entry"; then
    printf '%s\n' 'Keywords=Nextcloud;cloud;files;photos;sync;collaboration;' >>"$desktop_entry"
fi
install -D -m 0644 "$desktop_entry" \
    "$root/usr/share/applications/nextcloudnative-NextcloudNative.desktop"
install -D -m 0644 "$icon" \
    "$root/usr/share/icons/hicolor/512x512/apps/dev.obiente.nextcloudnative.png"
(
    cd "$root"
    find . -path ./DEBIAN -prune -o -type f -print0 |
        sort -z |
        xargs -0 md5sum |
        sed 's|  \./|  |' >DEBIAN/md5sums
)
dpkg-deb --build --root-owner-group "$root" "$rebuilt"
mv -- "$rebuilt" "${packages[0]}"
dpkg-deb --field "${packages[0]}" Depends |
    tr ',' '\n' |
    sed 's/^[[:space:]]*//; s/[[:space:]]*$//' |
    grep -Eq '^libsecret-tools([[:space:](]|$)'
