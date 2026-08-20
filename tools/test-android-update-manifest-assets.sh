#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
temporary_directory="$(mktemp -d)"
trap 'rm -r -- "$temporary_directory"' EXIT

tag="nightly-20260726-1430-run42-abcdef12"
version_code="$("$project_root/tools/derive-android-version-code.sh" 42 nightly)"
apk="$temporary_directory/nextcloud-native-${tag}-android.apk"
manifest="$temporary_directory/update-manifest.json"
printf 'synthetic signed APK fixture\n' >"$apk"
apk_size="$(stat --format='%s' "$apk")"
apk_sha256="$(sha256sum "$apk" | awk '{print $1}')"
max_android_apk_bytes=268435456

GITHUB_REPOSITORY="Obiente/nc-native" \
    "$project_root/tools/create-android-update-manifest.sh" \
    "$manifest" \
    "nightly-v1" \
    "$tag" \
    "$tag" \
    "$version_code" \
    "$(basename "$apk")" \
    "$apk_size" \
    "$apk_sha256" \
    '["bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"]'

"$project_root/tools/verify-android-update-manifest-assets.sh" \
    "$manifest" \
    "$apk" \
    "Obiente/nc-native" \
    "$tag" \
    "nightly-v1" \
    "$tag" \
    "$version_code"

jq -e 'keys == [
  "apkSha256", "apkSize", "apkUrl", "channel", "minimumAndroidSdk",
  "packageName", "releaseNotesUrl", "schemaVersion",
  "signingCertificateSha256Digests", "versionCode", "versionName"
]' "$manifest" >/dev/null

maximum_manifest="$temporary_directory/update-manifest-maximum.json"
GITHUB_REPOSITORY="Obiente/nc-native" \
    "$project_root/tools/create-android-update-manifest.sh" \
    "$maximum_manifest" \
    "nightly-v1" \
    "$tag" \
    "$tag" \
    "$version_code" \
    "$(basename "$apk")" \
    "$max_android_apk_bytes" \
    "$apk_sha256" \
    '["bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"]'

if GITHUB_REPOSITORY="Obiente/nc-native" \
    "$project_root/tools/create-android-update-manifest.sh" \
    "$temporary_directory/update-manifest-oversized.json" \
    "nightly-v1" \
    "$tag" \
    "$tag" \
    "$version_code" \
    "$(basename "$apk")" \
    "$((max_android_apk_bytes + 1))" \
    "$apk_sha256" \
    '["bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"]' \
    >/dev/null 2>&1; then
    echo "The Android manifest creator accepted an APK larger than 256 MiB." >&2
    exit 1
fi

fake_bin="$temporary_directory/bin"
mkdir "$fake_bin"
cat >"$fake_bin/stat" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "${FAKE_APK_SIZE:?}"
EOF
chmod +x "$fake_bin/stat"
PATH="$fake_bin:$PATH" FAKE_APK_SIZE="$max_android_apk_bytes" \
    "$project_root/tools/verify-android-update-manifest-assets.sh" \
    "$maximum_manifest" \
    "$apk" \
    "Obiente/nc-native" \
    "$tag" \
    "nightly-v1" \
    "$tag" \
    "$version_code" >/dev/null

oversized_manifest="$temporary_directory/update-manifest-oversized.json"
jq --argjson size "$((max_android_apk_bytes + 1))" '.apkSize = $size' \
    "$maximum_manifest" >"$oversized_manifest"
if PATH="$fake_bin:$PATH" FAKE_APK_SIZE="$((max_android_apk_bytes + 1))" \
    "$project_root/tools/verify-android-update-manifest-assets.sh" \
    "$oversized_manifest" \
    "$apk" \
    "Obiente/nc-native" \
    "$tag" \
    "nightly-v1" \
    "$tag" \
    "$version_code" >/dev/null 2>&1; then
    echo "The Android asset verifier accepted an APK larger than 256 MiB." >&2
    exit 1
fi

printf 'changed APK bytes\n' >>"$apk"
if "$project_root/tools/verify-android-update-manifest-assets.sh" \
    "$manifest" \
    "$apk" \
    "Obiente/nc-native" \
    "$tag" \
    "nightly-v1" \
    "$tag" \
    "$version_code" >/dev/null 2>&1; then
    echo "A manifest must not validate against different APK bytes." >&2
    exit 1
fi

printf 'Android update manifest asset-integrity checks passed.\n'
