#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
properties_file="${NC_VERSION_PROPERTIES:-$repo_root/gradle.properties}"
expected_tag="${1:-${GITHUB_REF_NAME:-}}"

read_property() {
    local key="$1"
    local values
    values="$(awk -F= -v key="$key" '$1 == key { print substr($0, index($0, "=") + 1) }' "$properties_file")"
    if [[ -z "$values" || "$(wc -l <<<"$values")" -ne 1 ]]; then
        echo "Expected exactly one $key entry in $properties_file." >&2
        return 1
    fi
    printf '%s' "$values"
}

version_name="$(read_property ncVersionName)"
version_code="$(read_property ncVersionCode)"
desktop_version="$(read_property ncDesktopPackageVersion)"

version_pattern='^0\.([0-9]+)\.([0-9]+)-(alpha|beta|rc)\.([1-9][0-9]*)$'
if [[ ! "$version_name" =~ $version_pattern ]]; then
    echo "Release version must be a 0.x.y alpha, beta, or rc prerelease." >&2
    exit 1
fi

minor="${BASH_REMATCH[1]}"
patch="${BASH_REMATCH[2]}"
phase="${BASH_REMATCH[3]}"
serial="${BASH_REMATCH[4]}"
if (( 10#$minor > 199 || 10#$patch > 999 || 10#$serial > 999 )); then
    echo "Version components exceed the prerelease version-code allocation." >&2
    exit 1
fi

case "$phase" in
    alpha) phase_offset=0 ;;
    beta) phase_offset=1000 ;;
    rc) phase_offset=2000 ;;
esac

expected_code=$((10#$minor * 10000000 + 10#$patch * 3000 + phase_offset + 10#$serial))
if [[ ! "$version_code" =~ ^[1-9][0-9]*$ || "$version_code" -ne "$expected_code" ]]; then
    echo "ncVersionCode must be $expected_code for $version_name." >&2
    exit 1
fi

base_version="0.$((10#$minor)).$((10#$patch))"
if [[ "$desktop_version" != "$base_version" ]]; then
    echo "ncDesktopPackageVersion must be $base_version for $version_name." >&2
    exit 1
fi

if [[ -n "$expected_tag" && "$expected_tag" != "v$version_name" ]]; then
    echo "Release tag must be v$version_name, not $expected_tag." >&2
    exit 1
fi

printf 'Validated prerelease %s (version code %s).\n' "$version_name" "$version_code"
