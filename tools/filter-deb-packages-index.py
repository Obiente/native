#!/usr/bin/env python3
from pathlib import Path
import re
import sys


def fail(message: str) -> None:
    raise SystemExit(message)


if len(sys.argv) != 4:
    fail("Usage: filter-deb-packages-index.py SOURCE OUTPUT ARCHITECTURE")

source = Path(sys.argv[1])
output = Path(sys.argv[2])
architecture = sys.argv[3]
if not source.is_file():
    fail(f"DEB package index does not exist: {source}")
if source.resolve() == output.resolve():
    fail("Filtered DEB package index must differ from its source.")
if not re.fullmatch(r"[a-z0-9][a-z0-9-]*", architecture):
    fail(f"Invalid DEB architecture: {architecture}")

contents = source.read_text(encoding="utf-8")
stanzas = [stanza for stanza in re.split(r"\n{2,}", contents.strip()) if stanza]
selected: list[str] = []
for stanza in stanzas:
    architectures = [
        line.removeprefix("Architecture: ")
        for line in stanza.splitlines()
        if line.startswith("Architecture: ")
    ]
    if len(architectures) != 1:
        fail("DEB package index stanza must contain exactly one Architecture field.")
    if architectures[0] in {architecture, "all"}:
        selected.append(stanza)

if not selected:
    fail(f"DEB package index contains no packages for {architecture}.")
output.write_text("\n\n".join(selected) + "\n", encoding="utf-8")
