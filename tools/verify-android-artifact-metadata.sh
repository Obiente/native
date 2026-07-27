#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" -ne 6 ]]; then
    printf 'Usage: %s APKANALYZER ARTIFACT PACKAGE VERSION_NAME VERSION_CODE MINIMUM_SDK\n' "$0" >&2
    exit 2
fi

apkanalyzer="$1"
artifact="$2"
expected_package="$3"
expected_version_name="$4"
expected_version_code="$5"
expected_minimum_sdk="$6"

[[ -x "$apkanalyzer" ]]
[[ -f "$artifact" ]]
[[ "$expected_package" =~ ^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$ ]]
[[ -n "$expected_version_name" && "$expected_version_name" != *$'\n'* ]]
[[ "$expected_version_code" =~ ^[1-9][0-9]*$ ]]
[[ "$expected_minimum_sdk" =~ ^[1-9][0-9]*$ ]]

actual_package="$("$apkanalyzer" manifest application-id "$artifact")"
actual_version_name="$("$apkanalyzer" manifest version-name "$artifact")"
actual_version_code="$("$apkanalyzer" manifest version-code "$artifact")"
actual_minimum_sdk="$("$apkanalyzer" manifest min-sdk "$artifact")"

if [[ "$actual_package" != "$expected_package" ]]; then
    printf 'Android artifact package is %s, expected %s.\n' \
        "$actual_package" "$expected_package" >&2
    exit 1
fi
if [[ "$actual_version_name" != "$expected_version_name" ]]; then
    printf 'Android artifact version name is %s, expected %s.\n' \
        "$actual_version_name" "$expected_version_name" >&2
    exit 1
fi
if [[ "$actual_version_code" != "$expected_version_code" ]]; then
    printf 'Android artifact version code is %s, expected %s.\n' \
        "$actual_version_code" "$expected_version_code" >&2
    exit 1
fi
if [[ "$actual_minimum_sdk" != "$expected_minimum_sdk" ]]; then
    printf 'Android artifact minimum SDK is %s, expected %s.\n' \
        "$actual_minimum_sdk" "$expected_minimum_sdk" >&2
    exit 1
fi

printf 'Verified Android artifact metadata: %s\n' "$artifact"
