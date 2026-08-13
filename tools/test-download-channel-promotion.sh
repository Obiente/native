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
        printf '%s\n' 'nextcloud-native-macos-intel.dmg'
    fi
    exit 0
fi
if [[ "$1 $2" == "release delete-asset" ]]; then
    printf '%s\n' "$4" >>"$FAKE_DELETED"
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

PATH="$temporary/bin:$PATH" \
    FAKE_DELETED="$temporary/deleted" \
    FAKE_UPLOADED="$temporary/uploaded" \
    "$project_root/tools/promote-download-channel.sh" \
    Obiente/nc-native \
    channel-nightly \
    nightly-20260813-0800-run1-01234567 \
    "$temporary/assets"

grep -Fxq nextcloud-native-android.apk "$temporary/uploaded"
grep -Fxq nextcloud-native-linux-amd64.deb "$temporary/uploaded"
grep -Fxq nextcloud-native-windows-x86_64.msi "$temporary/uploaded"
grep -Fxq nextcloud-native-macos-intel.dmg "$temporary/deleted"
if grep -Fq nextcloud-native-macos-intel.dmg "$temporary/uploaded"; then
    echo "A missing platform retained a stale channel alias." >&2
    exit 1
fi

printf 'Nightly download promotion publishes stable aliases and removes stale platforms.\n'
