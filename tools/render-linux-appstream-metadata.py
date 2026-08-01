#!/usr/bin/env python3

import re
import sys
import xml.etree.ElementTree as ET
from datetime import date
from pathlib import Path


def fail(message: str) -> None:
    raise SystemExit(message)


if len(sys.argv) != 6:
    fail(
        "Usage: render-linux-appstream-metadata.py "
        "SOURCE OUTPUT PACKAGE_VERSION RELEASE_NAME RELEASE_DATE"
    )

source = Path(sys.argv[1])
output = Path(sys.argv[2])
package_version = sys.argv[3]
release_name = sys.argv[4]
release_date = sys.argv[5]

if not source.is_file():
    fail(f"AppStream source does not exist: {source}")
if source.resolve() == output.resolve():
    fail("AppStream output must differ from its source.")
if not re.fullmatch(r"[0-9]+(?:\.[0-9]+){1,3}", package_version):
    fail(f"Invalid desktop package version: {package_version}")
if not release_name or any(ord(character) < 0x20 for character in release_name):
    fail("Release name must be non-empty text without control characters.")
if not re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}", release_date):
    fail(f"Invalid AppStream release date: {release_date}")
try:
    date.fromisoformat(release_date)
except ValueError:
    fail(f"Invalid AppStream release date: {release_date}")

tree = ET.parse(source)
component = tree.getroot()
if component.tag != "component" or component.attrib.get("type") != "desktop-application":
    fail("AppStream source is not a desktop application component.")

releases = component.find("releases")
if releases is None:
    releases = ET.SubElement(component, "releases")
for existing in list(releases):
    if existing.tag == "release" and existing.attrib.get("version") == package_version:
        releases.remove(existing)

release_type = "development" if "-" in release_name or release_name.startswith("nightly") else "stable"
release = ET.Element(
    "release",
    {
        "version": package_version,
        "date": release_date,
        "type": release_type,
    },
)
description = ET.SubElement(release, "description")
paragraph = ET.SubElement(description, "p")
if release_type == "development":
    paragraph.text = f"Development build {release_name} from verified Nextcloud Native source."
else:
    paragraph.text = f"Nextcloud Native {release_name}."
releases.insert(0, release)

output.parent.mkdir(parents=True, exist_ok=True)
ET.indent(tree, space="  ")
tree.write(output, encoding="UTF-8", xml_declaration=True)
