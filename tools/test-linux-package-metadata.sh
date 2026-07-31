#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "$0")/.." && pwd)"
metadata="$project_root/release/linux/dev.obiente.nextcloudnative.metainfo.xml"
templates="$project_root/release/linux/jpackage"

python3 - "$metadata" <<'PY'
import sys
import xml.etree.ElementTree as ET

root = ET.parse(sys.argv[1]).getroot()
assert root.tag == "component"
assert root.attrib.get("type") == "desktop-application"
assert root.findtext("id") == "dev.obiente.nextcloudnative"
assert root.findtext("metadata_license") == "CC0-1.0"
assert root.findtext("project_license") == "AGPL-3.0-or-later"
assert root.findtext("launchable") == "nextcloudnative-NextcloudNative.desktop"
assert root.find("url[@type='homepage']").text == "https://nc-native.obiente.dev/"
screenshots = root.findall("./screenshots/screenshot")
assert len(screenshots) >= 3
assert all(item.findtext("image", "").startswith("https://nc-native.obiente.dev/screenshots/") for item in screenshots)
assert "available offline" not in " ".join(root.itertext()).lower()
PY

grep -Fq 'Homepage: https://nc-native.obiente.dev/' "$templates/control"
grep -Fq 'License: AGPL-3.0-or-later' "$templates/nextcloudnative.spec"
grep -Fq 'APPSTREAM_XML_BASE64' "$templates/nextcloudnative.spec"
grep -Fq 'Categories=Network;FileTransfer;Utility;' "$templates/NextcloudNative.desktop"
grep -Fq 'tools/enrich-deb-appstream.sh' "$project_root/ui/build.gradle.kts"
grep -Fq 'Homepage: https://nc-native.obiente.dev/' \
  "$project_root/tools/enrich-deb-appstream.sh"
grep -Fq 'usr/share/doc/nextcloudnative/copyright' \
  "$project_root/tools/enrich-deb-appstream.sh"
grep -Fq -- '--app-image "$app_image"' \
  "$project_root/tools/repackage-rpm-with-metadata.sh"
grep -Fq 'repackageRpmWithMetadata' "$project_root/ui/build.gradle.kts"

printf 'Linux package metadata contract passed.\n'
