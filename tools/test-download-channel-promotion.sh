#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
temporary="$(mktemp -d)"
trap 'rm -r -- "$temporary"' EXIT
mkdir -p "$temporary/bin" "$temporary/assets"

cat >"$temporary/bin/gh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [[ "$1 $2" == "release view" ]]; then
    tag="$3"
    if [[ " $* " == *" --json isDraft,isPrerelease,tagName "* ]]; then
        printf 'false\ttrue\t%s\n' "$tag"
    fi
    exit 0
fi
if [[ "$1 $2" == "release download" ]]; then
    [[ -n "${FAKE_POINTER_METADATA:-}" ]] || exit 1
    destination=""
    while [[ "$#" -gt 0 ]]; do
        if [[ "$1" == "--dir" ]]; then destination="$2"; break; fi
        shift
    done
    cp "$FAKE_POINTER_METADATA" "$destination/download-channel.json"
    exit 0
fi
if [[ "$1 $2" == "release upload" ]]; then
    shift 3
    while [[ "$1" != "--repo" ]]; do
        basename "$1" >>"$FAKE_UPLOADED"
        shift
    done
    exit 0
fi
printf 'Unexpected fake gh invocation: %s\n' "$*" >&2
exit 1
EOF
chmod +x "$temporary/bin/gh"
printf android >"$temporary/assets/nextcloud-native-nightly-android.apk"
printf deb >"$temporary/assets/nextcloudnative_1.2.3_amd64.deb"
printf msi >"$temporary/assets/NextcloudNative-1.2.3.msi"
jq -n '{versionCode: 2}' >"$temporary/assets/update-manifest.json"

PATH="$temporary/bin:$PATH" \
    FAKE_UPLOADED="$temporary/uploaded" \
    "$project_root/tools/promote-download-channel.sh" \
    Obiente/nc-native \
    channel-nightly \
    nightly-20260813-0800-run1-01234567 \
    "$temporary/assets"

grep -Fxq nextcloud-native-android.apk "$temporary/uploaded"
grep -Fxq nextcloud-native-linux-amd64.deb "$temporary/uploaded"
grep -Fxq nextcloud-native-windows-x86_64.msi "$temporary/uploaded"
grep -Fxq download-channel.json "$temporary/uploaded"
if grep -Fq nextcloud-native-macos-intel.dmg "$temporary/uploaded"; then
    echo "A missing platform unexpectedly published an alias." >&2
    exit 1
fi

newer="$temporary/newer.json"
jq -n '{
  schemaVersion: 1,
  channel: "nightly-v1",
  versionName: "nightly-20260814-0800-run2-abcdef12",
  versionCode: 3,
  releaseNotesUrl: "https://github.com/Obiente/nc-native/releases/tag/nightly-20260814-0800-run2-abcdef12"
}' >"$newer"
: >"$temporary/downgrade-uploaded"
PATH="$temporary/bin:$PATH" \
    FAKE_POINTER_METADATA="$newer" \
    FAKE_UPLOADED="$temporary/downgrade-uploaded" \
    "$project_root/tools/promote-download-channel.sh" \
    Obiente/nc-native \
    channel-nightly \
    nightly-20260813-0800-run1-01234567 \
    "$temporary/assets"
test ! -s "$temporary/downgrade-uploaded"

printf 'Nightly download promotion is monotonic and retains missing-platform aliases.\n'
