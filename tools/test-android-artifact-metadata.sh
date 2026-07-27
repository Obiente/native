#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
temporary_directory="$(mktemp -d)"
trap 'rm -r -- "$temporary_directory"' EXIT

artifact="$temporary_directory/app.aab"
analyzer="$temporary_directory/apkanalyzer"
bundletool="$temporary_directory/bundletool.jar"
java_command="$temporary_directory/java"
touch "$artifact"
touch "$bundletool"

cat >"$analyzer" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[[ "$1" == "manifest" ]]
case "$2" in
    application-id) printf '%s\n' 'dev.obiente.nextcloudnative' ;;
    version-name) printf '%s\n' '0.1.0-alpha.2' ;;
    version-code) printf '%s\n' "${FAKE_VERSION_CODE:-20000102}" ;;
    min-sdk) printf '%s\n' '26' ;;
    *) exit 2 ;;
esac
EOF
chmod +x "$analyzer"

cat >"$java_command" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
xpath="${*: -1}"
case "$xpath" in
    --xpath=/manifest/@package) printf '%s\n' 'dev.obiente.nextcloudnative' ;;
    --xpath=/manifest/@android:versionName) printf '%s\n' '0.1.0-alpha.2' ;;
    --xpath=/manifest/@android:versionCode) printf '%s\n' "${FAKE_VERSION_CODE:-20000102}" ;;
    --xpath=/manifest/uses-sdk/@android:minSdkVersion) printf '%s\n' '26' ;;
    *) exit 2 ;;
esac
EOF
chmod +x "$java_command"

"$project_root/tools/verify-android-artifact-metadata.sh" \
    "$analyzer" \
    "$artifact" \
    "dev.obiente.nextcloudnative" \
    "0.1.0-alpha.2" \
    "20000102" \
    "26"

if FAKE_VERSION_CODE=20000101 \
    "$project_root/tools/verify-android-artifact-metadata.sh" \
        "$analyzer" \
        "$artifact" \
        "dev.obiente.nextcloudnative" \
        "0.1.0-alpha.2" \
        "20000102" \
        "26" \
        >"$temporary_directory/unexpected-success.log" 2>&1; then
    printf 'Artifact metadata verification accepted a mismatched version code.\n' >&2
    exit 1
fi

"$project_root/tools/verify-android-app-bundle-metadata.sh" \
    "$java_command" \
    "$bundletool" \
    "$artifact" \
    "dev.obiente.nextcloudnative" \
    "0.1.0-alpha.2" \
    "20000102" \
    "26"

if FAKE_VERSION_CODE=20000101 \
    "$project_root/tools/verify-android-app-bundle-metadata.sh" \
        "$java_command" \
        "$bundletool" \
        "$artifact" \
        "dev.obiente.nextcloudnative" \
        "0.1.0-alpha.2" \
        "20000102" \
        "26" \
        >"$temporary_directory/unexpected-bundle-success.log" 2>&1; then
    printf 'App bundle metadata verification accepted a mismatched version code.\n' >&2
    exit 1
fi

printf 'Android APK and app-bundle metadata verification checks passed.\n'
