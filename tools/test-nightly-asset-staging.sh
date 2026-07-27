#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
temporary_directory="$(mktemp -d)"
trap 'rm -r -- "$temporary_directory"' EXIT

artifacts="$temporary_directory/artifacts"
staged="$temporary_directory/staged"
existing="$temporary_directory/existing-assets.txt"

mkdir -p \
    "$artifacts/nextcloud-native-android/nested" \
    "$artifacts/nextcloud-native-linux/deb" \
    "$artifacts/nextcloud-native-linux/rpm/deeper" \
    "$artifacts/nextcloud-native-windows/package" \
    "$artifacts/nextcloud-native-macos/package"
printf 'apk' >"$artifacts/nextcloud-native-android/nested/nextcloud-native-test-android.apk"
printf 'manifest' >"$artifacts/nextcloud-native-android/update-manifest.json"
printf 'ignored' >"$artifacts/nextcloud-native-android/android-apk.sha256"
printf 'deb' >"$artifacts/nextcloud-native-linux/deb/nextcloud-native.deb"
printf 'rpm' >"$artifacts/nextcloud-native-linux/rpm/deeper/nextcloud-native.rpm"
printf 'msi' >"$artifacts/nextcloud-native-windows/package/nextcloud-native.msi"
printf 'dmg' >"$artifacts/nextcloud-native-macos/package/nextcloud-native.dmg"
: >"$existing"

"$project_root/tools/stage-nightly-assets.sh" "$artifacts" "$staged"

test -f "$staged/nextcloud-native-test-android.apk"
test -f "$staged/update-manifest.json"
test -f "$staged/nextcloud-native.deb"
test -f "$staged/nextcloud-native.rpm"
test -f "$staged/nextcloud-native.msi"
test -f "$staged/nextcloud-native.dmg"
test ! -e "$staged/android-apk.sha256"

mapfile -t staged_state < <(
    "$project_root/tools/count-nightly-platforms.sh" "$staged" "$existing"
)
test "${staged_state[0]}" = "successful=4"
test "${staged_state[1]}" = "available=android,linux,windows,macos"

printf '%s\n' 'update-manifest.json' >"$existing"
mapfile -t healed_state < <(
    "$project_root/tools/count-nightly-platforms.sh" "$staged" "$existing"
)
test "${healed_state[0]}" = "successful=4"
test "${healed_state[1]}" = "available=android,linux,windows,macos"
: >"$existing"

rm -- "$staged/update-manifest.json"
if "$project_root/tools/count-nightly-platforms.sh" \
    "$staged" "$existing" >/dev/null 2>&1; then
    echo "Nightly accounting accepted a staged APK without its manifest." >&2
    exit 1
fi
rm -- "$staged/nextcloud-native-test-android.apk"
mapfile -t desktop_only_state < <(
    "$project_root/tools/count-nightly-platforms.sh" "$staged" "$existing"
)
test "${desktop_only_state[0]}" = "successful=3"
test "${desktop_only_state[1]}" = "available=linux,windows,macos"

printf '%s\n' \
    'nextcloud-native-existing-android.apk' \
    'update-manifest.json' >"$existing"
mapfile -t recovered_state < <(
    "$project_root/tools/count-nightly-platforms.sh" "$staged" "$existing"
)
test "${recovered_state[0]}" = "successful=4"
test "${recovered_state[1]}" = "available=android,linux,windows,macos"

collision_artifacts="$temporary_directory/collision-artifacts"
collision_staged="$temporary_directory/collision-staged"
mkdir -p \
    "$collision_artifacts/nextcloud-native-linux/first" \
    "$collision_artifacts/nextcloud-native-linux/second"
printf 'first' >"$collision_artifacts/nextcloud-native-linux/first/same.deb"
printf 'second' >"$collision_artifacts/nextcloud-native-linux/second/same.deb"
if "$project_root/tools/stage-nightly-assets.sh" \
    "$collision_artifacts" "$collision_staged" >/dev/null 2>&1; then
    echo "Nightly staging accepted conflicting asset names." >&2
    exit 1
fi

case_collision_artifacts="$temporary_directory/case-collision-artifacts"
case_collision_staged="$temporary_directory/case-collision-staged"
mkdir -p \
    "$case_collision_artifacts/nextcloud-native-linux/first" \
    "$case_collision_artifacts/nextcloud-native-linux/second"
printf 'first' >"$case_collision_artifacts/nextcloud-native-linux/first/package.deb"
printf 'second' >"$case_collision_artifacts/nextcloud-native-linux/second/PACKAGE.DEB"
if "$project_root/tools/stage-nightly-assets.sh" \
    "$case_collision_artifacts" "$case_collision_staged" >/dev/null 2>&1; then
    echo "Nightly staging accepted a case-only asset-name collision." >&2
    exit 1
fi

unexpected_artifacts="$temporary_directory/unexpected-artifacts"
unexpected_staged="$temporary_directory/unexpected-staged"
mkdir -p "$unexpected_artifacts/nextcloud-native-linux"
printf 'not a package' >"$unexpected_artifacts/nextcloud-native-linux/build.log"
if "$project_root/tools/stage-nightly-assets.sh" \
    "$unexpected_artifacts" "$unexpected_staged" >/dev/null 2>&1; then
    echo "Nightly staging accepted an unexpected artifact." >&2
    exit 1
fi

symlink_artifacts="$temporary_directory/symlink-artifacts"
symlink_staged="$temporary_directory/symlink-staged"
mkdir -p "$symlink_artifacts/nextcloud-native-windows"
ln -s /dev/null "$symlink_artifacts/nextcloud-native-windows/package.msi"
if "$project_root/tools/stage-nightly-assets.sh" \
    "$symlink_artifacts" "$symlink_staged" >/dev/null 2>&1; then
    echo "Nightly staging accepted a symlink." >&2
    exit 1
fi

partial_android="$temporary_directory/partial-android"
partial_existing="$temporary_directory/partial-existing.txt"
mkdir -p "$partial_android"
printf 'apk' >"$partial_android/nextcloud-native-partial-android.apk"
: >"$partial_existing"
if "$project_root/tools/count-nightly-platforms.sh" \
    "$partial_android" "$partial_existing" >/dev/null 2>&1; then
    echo "Nightly accounting accepted an APK without an update manifest." >&2
    exit 1
fi
rm -- "$partial_android/nextcloud-native-partial-android.apk"
printf 'manifest' >"$partial_android/update-manifest.json"
if "$project_root/tools/count-nightly-platforms.sh" \
    "$partial_android" "$partial_existing" >/dev/null 2>&1; then
    echo "Nightly accounting accepted an update manifest without an APK." >&2
    exit 1
fi

printf 'Nightly nested-asset staging and platform accounting checks passed.\n'
