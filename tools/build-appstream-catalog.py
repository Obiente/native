#!/usr/bin/env python3

import copy
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def fail(message: str) -> None:
    raise SystemExit(message)


if len(sys.argv) < 5 or len(sys.argv[3:]) % 2 != 0:
    fail(
        "Usage: build-appstream-catalog.py OUTPUT ORIGIN "
        "VERSION METAINFO [VERSION METAINFO ...]"
    )

output = Path(sys.argv[1])
origin = sys.argv[2]
records = list(zip(sys.argv[3::2], sys.argv[4::2], strict=True))
if not origin or any(ord(character) < 0x20 for character in origin):
    fail("AppStream catalog origin must be non-empty text without control characters.")

components = []
for package_version, metadata_name in records:
    metadata = Path(metadata_name)
    if not metadata.is_file():
        fail(f"AppStream metadata does not exist: {metadata}")
    component = ET.parse(metadata).getroot()
    if component.tag != "component" or component.attrib.get("type") != "desktop-application":
        fail(f"AppStream metadata is not a desktop application: {metadata}")
    if component.findtext("pkgname") != "nextcloudnative":
        fail(f"AppStream metadata has an unexpected package name: {metadata}")
    if not any(
        release.attrib.get("version") == package_version
        for release in component.findall("./releases/release")
    ):
        fail(f"AppStream metadata does not describe package version {package_version}: {metadata}")
    components.append(component)

component_ids = {component.findtext("id") for component in components}
if component_ids != {"dev.obiente.nextcloudnative"}:
    fail("AppStream metadata contains unexpected or conflicting component IDs.")

catalog_component = copy.deepcopy(components[-1])
metadata_license = catalog_component.find("metadata_license")
if metadata_license is not None:
    catalog_component.remove(metadata_license)
catalog_releases = catalog_component.find("releases")
if catalog_releases is None:
    catalog_releases = ET.SubElement(catalog_component, "releases")
catalog_releases.clear()
seen_versions = set()
for component in reversed(components):
    for release in component.findall("./releases/release"):
        version = release.attrib.get("version")
        if not version or version in seen_versions:
            continue
        catalog_releases.append(copy.deepcopy(release))
        seen_versions.add(version)

catalog = ET.Element("components", {"version": "1.0", "origin": origin})
catalog.append(catalog_component)
tree = ET.ElementTree(catalog)
ET.indent(tree, space="  ")
output.parent.mkdir(parents=True, exist_ok=True)
tree.write(output, encoding="UTF-8", xml_declaration=True)
