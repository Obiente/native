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
    cp "$FAKE_POINTER_MANIFEST" "$destination/update-manifest.json"
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
      versionCode: 2,
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

printf 'Update channel promotion rejects executable version-code strings.\n'
