#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
temporary_directory="$(mktemp -d)"
trap 'rm -r -- "$temporary_directory"' EXIT

fake_bin="$temporary_directory/bin"
mkdir -p "$fake_bin"
fake_gh="$fake_bin/gh"
cat >"$fake_gh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

if [[ "$1 $2" == "release view" ]]; then
    tag="$3"
    if [[ " $* " == *" --json "* ]]; then
        printf 'false\ttrue\t%s\n' "$tag"
    fi
    exit 0
fi
if [[ "$1 $2" == "release download" ]]; then
    destination=""
    while [[ "$#" -gt 0 ]]; do
        if [[ "$1" == "--dir" ]]; then
            destination="$2"
            break
        fi
        shift
    done
    [[ -n "$destination" ]]
    cp "$FAKE_POINTER_MANIFEST" "$destination/${FAKE_POINTER_MANIFEST_NAME:-update-manifest.json}"
    exit 0
fi
if [[ "$1 $2" == "release upload" ]]; then
    cp "$4" "$FAKE_UPLOADED_MANIFEST"
    exit 0
fi

printf 'Unexpected fake gh invocation: %s\n' "$*" >&2
exit 1
EOF
chmod +x "$fake_gh"

immutable_tag="nightly-20260731-2200-run400-abcdef12"
candidate="$temporary_directory/candidate.json"
jq -n \
    --arg tag "$immutable_tag" \
    '{
      schemaVersion: 1,
      channel: "nightly-v1",
      versionName: $tag,
      versionCode: 2,
      packageName: "dev.obiente.nextcloudnative",
      minimumAndroidSdk: 26,
      apkSize: 1234,
      apkSha256: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      signingCertificateSha256Digests: ["bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"],
      releaseNotesUrl: ("https://github.com/Obiente/nc-native/releases/tag/" + $tag),
      apkUrl: ("https://github.com/Obiente/nc-native/releases/download/" + $tag + "/app.apk")
    }' >"$candidate"

execution_marker="$temporary_directory/untrusted-version-code-executed"
malicious_code='array[$(touch '"$execution_marker"')]'
existing="$temporary_directory/existing.json"
jq -n --arg code "$malicious_code" \
    '{schemaVersion: 1, channel: "nightly-v1", versionCode: $code}' >"$existing"

if PATH="$fake_bin:$PATH" FAKE_POINTER_MANIFEST="$existing" \
    "$project_root/tools/promote-app-update-channel.sh" \
    Obiente/nc-native \
    channel-nightly \
    "$immutable_tag" \
    0123456789abcdef0123456789abcdef01234567 \
    "$candidate" \
    - \
    1 >/dev/null 2>&1; then
    echo "A pointer manifest with a non-numeric version code was accepted." >&2
    exit 1
fi

if [[ -e "$execution_marker" ]]; then
    echo "Untrusted pointer manifest data reached shell arithmetic." >&2
    exit 1
fi

expanded="$temporary_directory/expanded.json"
uploaded_android="$temporary_directory/uploaded-android.json"
jq '.versionCode = 3 | .changes = []' "$candidate" >"$expanded"
PATH="$fake_bin:$PATH" \
    FAKE_POINTER_MANIFEST="$expanded" \
    FAKE_UPLOADED_MANIFEST="$uploaded_android" \
    "$project_root/tools/promote-app-update-channel.sh" \
    Obiente/nc-native \
    channel-nightly \
    "$immutable_tag" \
    0123456789abcdef0123456789abcdef01234567 \
    "$candidate" \
    - \
    1 >/dev/null
cmp "$candidate" "$uploaded_android"

desktop_candidate="$temporary_directory/desktop-candidate.json"
jq -n \
    --arg tag "$immutable_tag" \
    '{
      schemaVersion: 1,
      channel: "nightly-v1",
      versionName: $tag,
      versionCode: 2,
      packageVersion: "2.0.0",
      releaseNotesUrl: ("https://github.com/Obiente/nc-native/releases/tag/" + $tag),
      assets: [{
        platform: "linux",
        format: "deb",
        architecture: "x86_64",
        url: ("https://github.com/Obiente/nc-native/releases/download/" + $tag + "/nextcloud-native_amd64.deb"),
        size: 1234,
        sha256: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
      }]
    }' >"$desktop_candidate"
malformed_desktop="$temporary_directory/malformed-desktop.json"
jq '.versionCode = 3 | .assets[0].sha256 = "invalid"' \
    "$desktop_candidate" >"$malformed_desktop"
uploaded_desktop="$temporary_directory/uploaded-desktop.json"

PATH="$fake_bin:$PATH" \
    FAKE_POINTER_MANIFEST="$malformed_desktop" \
    FAKE_POINTER_MANIFEST_NAME=desktop-update-manifest.json \
    FAKE_UPLOADED_MANIFEST="$uploaded_desktop" \
    "$project_root/tools/promote-app-update-channel.sh" \
    Obiente/nc-native \
    channel-nightly \
    "$immutable_tag" \
    0123456789abcdef0123456789abcdef01234567 \
    - \
    "$desktop_candidate" \
    1 >/dev/null

cmp "$desktop_candidate" "$uploaded_desktop"

printf 'Update channel promotion rejects executable codes and repairs invalid desktop pointers.\n'
