#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fixture_dir="$(mktemp -d)"
trap 'rmdir "$fixture_dir"' EXIT
partial_keystore="$fixture_dir/missing-release.keystore"

set +e
output="$(
    env \
        -u NC_ANDROID_KEYSTORE_PASSWORD \
        -u NC_ANDROID_KEY_ALIAS \
        -u NC_ANDROID_KEY_PASSWORD \
        NC_ANDROID_KEYSTORE_PATH="$partial_keystore" \
        NC_ANDROID_KEYSTORE_PASSWORD="" \
        "$repo_root/gradlew" --no-daemon --quiet :androidApp:validateReleaseSigning 2>&1
)"
status=$?
set -e

if [[ "$status" -eq 0 ]]; then
    printf 'Expected partial Android release signing input to fail validation.\n' >&2
    exit 1
fi

expected='Android release signing is incomplete. Missing: NC_ANDROID_KEYSTORE_PASSWORD, NC_ANDROID_KEY_ALIAS, NC_ANDROID_KEY_PASSWORD.'
if [[ "$output" != *"$expected"* ]]; then
    printf 'Release signing validation did not report the missing inputs clearly.\n%s\n' "$output" >&2
    exit 1
fi

printf 'Android release signing configuration guard test passed.\n'
