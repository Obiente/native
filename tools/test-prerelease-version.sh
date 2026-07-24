#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fixture_dir="$(mktemp -d)"
trap 'find "$fixture_dir" -type f -delete; rmdir "$fixture_dir"' EXIT
fixture="$fixture_dir/gradle.properties"

assert_valid() {
    local version_name="$1"
    local version_code="$2"
    local desktop_version="$3"
    local tag="$4"
    printf 'ncVersionName=%s\nncVersionCode=%s\nncDesktopPackageVersion=%s\n' \
        "$version_name" "$version_code" "$desktop_version" >"$fixture"
    NC_VERSION_PROPERTIES="$fixture" "$repo_root/tools/verify-prerelease-version.sh" "$tag" >/dev/null
}

assert_invalid() {
    if assert_valid "$@" 2>/dev/null; then
        echo "Expected prerelease validation to reject $1." >&2
        exit 1
    fi
}

assert_valid "0.1.0-alpha.1" "10000001" "0.1.0" "v0.1.0-alpha.1"
assert_valid "0.2.7-beta.12" "20022012" "0.2.7" "v0.2.7-beta.12"
assert_valid "0.12.3-rc.4" "120011004" "0.12.3" "v0.12.3-rc.4"
assert_invalid "1.0.0" "100000000" "1.0.0" "v1.0.0"
assert_invalid "0.1.0" "10000000" "0.1.0" "v0.1.0"
assert_invalid "0.1.0-alpha.1" "10000002" "0.1.0" "v0.1.0-alpha.1"
assert_invalid "0.1.0-alpha.1" "10000001" "0.1.0" "v0.1.0-alpha.2"

echo "Prerelease version guard tests passed."
