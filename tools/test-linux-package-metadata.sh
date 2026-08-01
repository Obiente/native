#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "$0")/.." && pwd)"
metadata="$project_root/release/linux/dev.obiente.nextcloudnative.metainfo.xml"
templates="$project_root/release/linux/jpackage"
screenshots="$project_root/website/public/screenshots"
temporary="$(mktemp -d)"
trap 'rm -r -- "$temporary"' EXIT

python3 - "$metadata" "$screenshots" <<'PY'
import struct
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from urllib.parse import urlparse

root = ET.parse(sys.argv[1]).getroot()
screenshots_directory = Path(sys.argv[2])
assert root.tag == "component"
assert root.attrib.get("type") == "desktop-application"
assert root.findtext("id") == "dev.obiente.nextcloudnative"
assert root.findtext("pkgname") == "nextcloudnative"
assert root.findtext("metadata_license") == "CC0-1.0"
assert root.findtext("project_license") == "AGPL-3.0-or-later"
assert root.find("icon[@type='stock']").text == "dev.obiente.nextcloudnative"
assert root.findtext("launchable") == "nextcloudnative-NextcloudNative.desktop"
assert root.find("url[@type='homepage']").text == "https://nc-native.obiente.dev/"
screenshots = root.findall("./screenshots/screenshot")
assert len(screenshots) >= 3
assert sum(item.attrib.get("type") == "default" for item in screenshots) == 1
for screenshot in screenshots:
    image = screenshot.find("image")
    assert image is not None
    assert image.text.startswith("https://nc-native.obiente.dev/screenshots/")
    local_image = screenshots_directory / Path(urlparse(image.text).path).name
    with local_image.open("rb") as source:
        assert source.read(16) == b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR"
        width, height = struct.unpack(">II", source.read(8))
    assert image.attrib.get("type") == "source"
    assert image.attrib.get("width") == str(width)
    assert image.attrib.get("height") == str(height)
assert "available offline" not in " ".join(root.itertext()).lower()
PY

grep -Fq 'Homepage: https://nc-native.obiente.dev/' "$templates/control"
grep -Fq 'License: AGPL-3.0-or-later' "$templates/nextcloudnative.spec"
grep -Fxq '%global _build_id_links none' "$templates/nextcloudnative.spec"
if grep -Fq 'rm -rf' "$templates/nextcloudnative.spec" \
  "$project_root/tools/repackage-rpm-with-metadata.sh"; then
  printf 'Linux packaging cleanup must not use force-recursive removal.\n' >&2
  exit 1
fi
grep -Fq 'APPSTREAM_XML_BASE64' "$templates/nextcloudnative.spec"
grep -Fxq 'Icon=dev.obiente.nextcloudnative' "$templates/NextcloudNative.desktop"
grep -Fq 'usr/share/applications/nextcloudnative-NextcloudNative.desktop' \
  "$templates/nextcloudnative.spec"
grep -Fq 'usr/share/icons/hicolor/512x512/apps/dev.obiente.nextcloudnative.png' \
  "$templates/nextcloudnative.spec"
grep -Fq 'tools/enrich-deb-appstream.sh' "$project_root/ui/build.gradle.kts"
grep -Fq 'Homepage: https://nc-native.obiente.dev/' \
  "$project_root/tools/enrich-deb-appstream.sh"
grep -Fq 'usr/share/doc/nextcloudnative/copyright' \
  "$project_root/tools/enrich-deb-appstream.sh"
grep -Fq -- '--app-image "$app_image"' \
  "$project_root/tools/repackage-rpm-with-metadata.sh"
grep -Fq 'tools/verify-rpm-package.sh' \
  "$project_root/tools/repackage-rpm-with-metadata.sh"
grep -Fq 'repackageRpmWithMetadata' "$project_root/ui/build.gradle.kts"

printf '%s\n' \
  '#!/usr/bin/env bash' \
  'set -euo pipefail' \
  '[[ "$#" -eq 1 && -f "$1" ]]' \
  'printf payload' >"$temporary/rpm2cpio"
printf '%s\n' \
  '#!/usr/bin/env bash' \
  'set -euo pipefail' \
  'cat >/dev/null' \
  'printf "%s\n" "${MOCK_RPM_FILE_LIST:-}"' >"$temporary/cpio"
chmod +x "$temporary/rpm2cpio" "$temporary/cpio"
touch "$temporary/nextcloudnative.rpm"

valid_rpm_file_list=$'/opt/nextcloudnative/bin/NextcloudNative\n/usr/share/applications/nextcloudnative-NextcloudNative.desktop\n/usr/share/icons/hicolor/512x512/apps/dev.obiente.nextcloudnative.png\n/usr/share/metainfo/dev.obiente.nextcloudnative.metainfo.xml'
PATH="$temporary:$PATH" \
MOCK_RPM_FILE_LIST="$valid_rpm_file_list" \
  bash "$project_root/tools/verify-rpm-package.sh" \
  "$temporary/nextcloudnative.rpm" >/dev/null

if PATH="$temporary:$PATH" \
  MOCK_RPM_FILE_LIST="$valid_rpm_file_list"$'\n/usr/lib/.build-id/aa/bb' \
  bash "$project_root/tools/verify-rpm-package.sh" \
  "$temporary/nextcloudnative.rpm" >"$temporary/verification-error" 2>&1; then
  printf 'RPM verifier accepted a global build-ID link.\n' >&2
  exit 1
fi
grep -Fq '/usr/lib/.build-id/aa/bb' "$temporary/verification-error"

if PATH="$temporary:$PATH" \
  MOCK_RPM_FILE_LIST='/opt/nextcloudnative/bin/NextcloudNative' \
  bash "$project_root/tools/verify-rpm-package.sh" \
  "$temporary/nextcloudnative.rpm" >"$temporary/verification-error" 2>&1; then
  printf 'RPM verifier accepted missing desktop integration assets.\n' >&2
  exit 1
fi
grep -Fq '/usr/share/applications/nextcloudnative-NextcloudNative.desktop' \
  "$temporary/verification-error"
grep -Fq '/usr/share/icons/hicolor/512x512/apps/dev.obiente.nextcloudnative.png' \
  "$temporary/verification-error"

printf 'Linux package metadata contract passed.\n'
