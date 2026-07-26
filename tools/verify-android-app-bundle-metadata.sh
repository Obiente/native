#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" -ne 7 ]]; then
    printf 'Usage: %s JAVA BUNDLETOOL_JAR AAB PACKAGE VERSION_NAME VERSION_CODE MINIMUM_SDK\n' "$0" >&2
    exit 2
fi

java_command="$1"
bundletool_jar="$2"
artifact="$3"
expected_package="$4"
expected_version_name="$5"
expected_version_code="$6"
expected_minimum_sdk="$7"

[[ -x "$java_command" ]]
[[ -f "$bundletool_jar" ]]
[[ -f "$artifact" ]]
[[ "$expected_package" =~ ^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$ ]]
[[ -n "$expected_version_name" && "$expected_version_name" != *$'\n'* ]]
[[ "$expected_version_code" =~ ^[1-9][0-9]*$ ]]
[[ "$expected_minimum_sdk" =~ ^[1-9][0-9]*$ ]]

read_manifest_attribute() {
    local xpath="$1"
    "$java_command" -jar "$bundletool_jar" dump manifest \
        "--bundle=$artifact" \
        "--xpath=$xpath" \
        2>/dev/null
}

actual_package="$(read_manifest_attribute '/manifest/@package')"
actual_version_name="$(read_manifest_attribute '/manifest/@android:versionName')"
actual_version_code="$(read_manifest_attribute '/manifest/@android:versionCode')"
actual_minimum_sdk="$(read_manifest_attribute '/manifest/uses-sdk/@android:minSdkVersion')"

if [[ "$actual_package" != "$expected_package" ]]; then
    printf 'Android app bundle package is %s, expected %s.\n' \
        "$actual_package" "$expected_package" >&2
    exit 1
fi
if [[ "$actual_version_name" != "$expected_version_name" ]]; then
    printf 'Android app bundle version name is %s, expected %s.\n' \
        "$actual_version_name" "$expected_version_name" >&2
    exit 1
fi
if [[ "$actual_version_code" != "$expected_version_code" ]]; then
    printf 'Android app bundle version code is %s, expected %s.\n' \
        "$actual_version_code" "$expected_version_code" >&2
    exit 1
fi
if [[ "$actual_minimum_sdk" != "$expected_minimum_sdk" ]]; then
    printf 'Android app bundle minimum SDK is %s, expected %s.\n' \
        "$actual_minimum_sdk" "$expected_minimum_sdk" >&2
    exit 1
fi

printf 'Verified Android app bundle metadata: %s\n' "$artifact"
