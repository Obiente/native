#!/usr/bin/env python3

import copy
import re
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

def version_key(version: str) -> tuple[tuple[int, object], ...]:
    if not re.fullmatch(r"[0-9A-Za-z][0-9A-Za-z.+_~-]*", version):
        fail(f"AppStream package version is invalid: {version}")
    return tuple(
        (1, int(part)) if part.isdigit() else (0, part.lower())
        for part in re.split(r"([0-9]+)", version)
        if part
    )


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
    components.append((package_version, component))

component_ids = {component.findtext("id") for _, component in components}
if component_ids != {"dev.obiente.nextcloudnative"}:
    fail("AppStream metadata contains unexpected or conflicting component IDs.")

components.sort(key=lambda record: version_key(record[0]))
catalog_component = copy.deepcopy(components[-1][1])
metadata_license = catalog_component.find("metadata_license")
if metadata_license is not None:
    catalog_component.remove(metadata_license)
catalog_releases = catalog_component.find("releases")
if catalog_releases is None:
    catalog_releases = ET.SubElement(catalog_component, "releases")
catalog_releases.clear()
seen_versions = set()
releases = []
for _, component in components:
    for release in component.findall("./releases/release"):
        version = release.attrib.get("version")
        if not version or version in seen_versions:
            continue
        releases.append((version, copy.deepcopy(release)))
        seen_versions.add(version)
for _, release in sorted(releases, key=lambda record: version_key(record[0]), reverse=True):
    catalog_releases.append(release)

catalog = ET.Element("components", {"version": "1.0", "origin": origin})
catalog.append(catalog_component)
tree = ET.ElementTree(catalog)
ET.indent(tree, space="  ")
output.parent.mkdir(parents=True, exist_ok=True)
tree.write(output, encoding="UTF-8", xml_declaration=True)
