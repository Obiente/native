#!/usr/bin/env bash
set -euo pipefail

asset_directory="${1:?Release asset directory is required.}"
[[ "$#" -eq 1 && -d "$asset_directory" ]]

deb_count="$(find "$asset_directory" -maxdepth 1 -type f -name '*.deb' | wc -l)"
rpm_count="$(find "$asset_directory" -maxdepth 1 -type f -name '*.rpm' | wc -l)"

[[ "$deb_count" -gt 0 && "$rpm_count" -gt 0 ]]
