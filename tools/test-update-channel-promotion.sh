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
max_android_apk_bytes=268435456
candidate="$temporary_directory/candidate.json"
jq -n \
    --arg tag "$immutable_tag" \
    --argjson apk_size "$max_android_apk_bytes" \
    '{
      schemaVersion: 1,
      channel: "nightly-v1",
      versionName: $tag,
      versionCode: 2,
      packageName: "dev.obiente.nextcloudnative",
      minimumAndroidSdk: 26,
      apkSize: $apk_size,
      apkSha256: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      signingCertificateSha256Digests: ["bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"],
      releaseNotesUrl: ("https://github.com/Obiente/nc-native/releases/tag/" + $tag),
      apkUrl: ("https://github.com/Obiente/nc-native/releases/download/" + $tag + "/app.apk")
    }' >"$candidate"

oversized_candidate="$temporary_directory/oversized-candidate.json"
jq --argjson size "$((max_android_apk_bytes + 1))" '.apkSize = $size' \
    "$candidate" >"$oversized_candidate"
if PATH="$fake_bin:$PATH" \
    FAKE_POINTER_MANIFEST="$candidate" \
    FAKE_UPLOADED_MANIFEST="$temporary_directory/unexpected-oversized-upload.json" \
    "$project_root/tools/promote-app-update-channel.sh" \
    Obiente/native \
    channel-nightly \
    "$immutable_tag" \
    0123456789abcdef0123456789abcdef01234567 \
    "$oversized_candidate" \
    - \
    1 >/dev/null 2>&1; then
    echo "An Android promotion candidate larger than 256 MiB was accepted." >&2
    exit 1
fi

execution_marker="$temporary_directory/untrusted-version-code-executed"
malicious_code='array[$(touch '"$execution_marker"')]'
existing="$temporary_directory/existing.json"
jq -n --arg code "$malicious_code" \
    '{schemaVersion: 1, channel: "nightly-v1", versionCode: $code}' >"$existing"

if PATH="$fake_bin:$PATH" FAKE_POINTER_MANIFEST="$existing" \
    "$project_root/tools/promote-app-update-channel.sh" \
    Obiente/native \
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

while IFS='^' read -r corruption mutation; do
    pointer="$temporary_directory/android-pointer-${corruption}.json"
    uploaded="$temporary_directory/android-upload-${corruption}.json"
    jq "$mutation" "$candidate" >"$pointer"
    PATH="$fake_bin:$PATH" \
        FAKE_POINTER_MANIFEST="$pointer" \
        FAKE_UPLOADED_MANIFEST="$uploaded" \
        "$project_root/tools/promote-app-update-channel.sh" \
        Obiente/native \
        channel-nightly \
        "$immutable_tag" \
        0123456789abcdef0123456789abcdef01234567 \
        "$candidate" \
        - \
        1 >/dev/null
    cmp "$candidate" "$uploaded"
done <<EOF
unknown-field^.versionCode = 2 | .changes = []
schema^.versionCode = 3 | .schemaVersion = 2
channel^.versionCode = 3 | .channel = "stable-v1"
version-name^.versionCode = 3 | .versionName = "nightly-invalid"
version-code-type^.versionCode = "3"
package-name^.versionCode = 3 | .packageName = "dev.invalid.app"
minimum-sdk-low^.versionCode = 3 | .minimumAndroidSdk = 25
minimum-sdk-high^.versionCode = 3 | .minimumAndroidSdk = 65
apk-size-type^.versionCode = 3 | .apkSize = "268435456"
apk-size-high^.versionCode = 3 | .apkSize = $((max_android_apk_bytes + 1))
apk-sha256^.versionCode = 3 | .apkSha256 = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
release-notes-url^.versionCode = 3 | .releaseNotesUrl = "https://github.com/Obiente/nc-native/releases/tag/wrong"
apk-url^.versionCode = 3 | .apkUrl = "https://github.com/Obiente/nc-native/releases/download/wrong/app.apk"
signers-empty^.versionCode = 3 | .signingCertificateSha256Digests = []
signers-duplicate^.versionCode = 3 | .signingCertificateSha256Digests = ["bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"]
signers-invalid^.versionCode = 3 | .signingCertificateSha256Digests = ["BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"]
EOF

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
expanded_desktop_candidate="$temporary_directory/expanded-desktop-candidate.json"
jq '.assets[0].futureField = "unsupported"' \
    "$desktop_candidate" >"$expanded_desktop_candidate"
if PATH="$fake_bin:$PATH" \
    FAKE_POINTER_MANIFEST="$desktop_candidate" \
    FAKE_POINTER_MANIFEST_NAME=desktop-update-manifest.json \
    FAKE_UPLOADED_MANIFEST="$temporary_directory/unexpected-desktop-upload.json" \
    "$project_root/tools/promote-app-update-channel.sh" \
    Obiente/native \
    channel-nightly \
    "$immutable_tag" \
    0123456789abcdef0123456789abcdef01234567 \
    - \
    "$expanded_desktop_candidate" \
    1 >/dev/null 2>&1; then
    echo "A desktop promotion candidate with an unknown asset field was accepted." >&2
    exit 1
fi
malformed_desktop="$temporary_directory/malformed-desktop.json"
jq '.versionCode = 3 | .assets[0].sha256 = "invalid"' \
    "$desktop_candidate" >"$malformed_desktop"
uploaded_desktop="$temporary_directory/uploaded-desktop.json"

PATH="$fake_bin:$PATH" \
    FAKE_POINTER_MANIFEST="$malformed_desktop" \
    FAKE_POINTER_MANIFEST_NAME=desktop-update-manifest.json \
    FAKE_UPLOADED_MANIFEST="$uploaded_desktop" \
    "$project_root/tools/promote-app-update-channel.sh" \
    Obiente/native \
    channel-nightly \
    "$immutable_tag" \
    0123456789abcdef0123456789abcdef01234567 \
    - \
    "$desktop_candidate" \
    1 >/dev/null

cmp "$desktop_candidate" "$uploaded_desktop"

printf 'Update channel promotion rejects executable codes and repairs invalid desktop pointers.\n'
