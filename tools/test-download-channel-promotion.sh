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
    elif [[ " $* " == *" --json assets "* ]]; then
        find "$FAKE_RELEASE_ASSETS" -maxdepth 1 -type f -printf '%f\n' | sort
    fi
    exit 0
fi
if [[ "$1 $2" == "release download" ]]; then
    pattern=""
    destination=""
    while [[ "$#" -gt 0 ]]; do
        if [[ "$1" == "--pattern" ]]; then pattern="$2"; shift 2; continue; fi
        if [[ "$1" == "--dir" ]]; then destination="$2"; shift 2; continue; fi
        shift
    done
    [[ -f "$FAKE_RELEASE_ASSETS/$pattern" ]] || exit 1
    cp "$FAKE_RELEASE_ASSETS/$pattern" "$destination/$pattern"
    exit 0
fi
if [[ "$1 $2" == "release upload" ]]; then
    shift 3
    while [[ "$1" != "--repo" ]]; do
        name="$(basename "$1")"
        rm -f "$FAKE_RELEASE_ASSETS/$name"
        if [[ -n "${FAKE_FAIL_UPLOAD_ONCE:-}" && "$name" == "$FAKE_FAIL_UPLOAD_ONCE" && ! -e "$FAKE_FAILURE_MARKER" ]]; then
            : >"$FAKE_FAILURE_MARKER"
            exit 1
        fi
        cp "$1" "$FAKE_RELEASE_ASSETS/$name"
        printf '%s\n' "$name" >>"$FAKE_UPLOADED"
        shift
    done
    exit 0
fi
if [[ "$1 $2" == "release delete-asset" ]]; then
    rm -f "$FAKE_RELEASE_ASSETS/$4"
    exit 0
fi
printf 'Unexpected fake gh invocation: %s\n' "$*" >&2
exit 1
EOF
chmod +x "$temporary/bin/gh"
mkdir -p "$temporary/release-assets"
printf android >"$temporary/assets/nextcloud-native-nightly-android.apk"
printf deb >"$temporary/assets/nextcloudnative_1.2.3_amd64.deb"
printf msi >"$temporary/assets/NextcloudNative-1.2.3.msi"
jq -n '{versionCode: 2}' >"$temporary/assets/update-manifest.json"

PATH="$temporary/bin:$PATH" \
    FAKE_RELEASE_ASSETS="$temporary/release-assets" \
    FAKE_FAILURE_MARKER="$temporary/upload-failed" \
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
cp "$newer" "$temporary/release-assets/download-channel.json"
: >"$temporary/downgrade-uploaded"
PATH="$temporary/bin:$PATH" \
    FAKE_RELEASE_ASSETS="$temporary/release-assets" \
    FAKE_FAILURE_MARKER="$temporary/upload-failed" \
    FAKE_UPLOADED="$temporary/downgrade-uploaded" \
    "$project_root/tools/promote-download-channel.sh" \
    Obiente/nc-native \
    channel-nightly \
    nightly-20260813-0800-run1-01234567 \
    "$temporary/assets"
test ! -s "$temporary/downgrade-uploaded"

rm -f "$temporary/release-assets"/* "$temporary/upload-failed"
cp "$newer" "$temporary/release-assets/download-channel.json"
printf old-android >"$temporary/release-assets/nextcloud-native-android.apk"
printf old-deb >"$temporary/release-assets/nextcloud-native-linux-amd64.deb"
printf old-msi >"$temporary/release-assets/nextcloud-native-windows-x86_64.msi"
jq '.versionCode = 4 | .versionName = "nightly-20260815-0800-run3-fedcba98" | .releaseNotesUrl = "https://github.com/Obiente/nc-native/releases/tag/nightly-20260815-0800-run3-fedcba98"' \
    "$newer" >"$temporary/assets/update-manifest.json"
: >"$temporary/failed-uploaded"
if PATH="$temporary/bin:$PATH" \
    FAKE_RELEASE_ASSETS="$temporary/release-assets" \
    FAKE_FAILURE_MARKER="$temporary/upload-failed" \
    FAKE_FAIL_UPLOAD_ONCE="nextcloud-native-windows-x86_64.msi" \
    FAKE_UPLOADED="$temporary/failed-uploaded" \
    "$project_root/tools/promote-download-channel.sh" \
    Obiente/nc-native \
    channel-nightly \
    nightly-20260815-0800-run3-fedcba98 \
    "$temporary/assets"; then
    echo "A failed alias upload unexpectedly succeeded." >&2
    exit 1
fi
cmp -s "$newer" "$temporary/release-assets/download-channel.json"
grep -Fxq old-android "$temporary/release-assets/nextcloud-native-android.apk"
grep -Fxq old-deb "$temporary/release-assets/nextcloud-native-linux-amd64.deb"
grep -Fxq old-msi "$temporary/release-assets/nextcloud-native-windows-x86_64.msi"

printf 'Nightly download promotion is monotonic, retains missing platforms, and rolls back failed uploads.\n'
