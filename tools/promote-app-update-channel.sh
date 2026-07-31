#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" -ne 7 ]]; then
    printf 'Usage: %s REPOSITORY POINTER_TAG IMMUTABLE_TAG IMMUTABLE_SHA ANDROID_MANIFEST_OR_DASH DESKTOP_MANIFEST_OR_DASH POINTER_SCHEMA\n' "$0" >&2
    exit 2
fi

repository="$1"
pointer_tag="$2"
immutable_tag="$3"
immutable_sha="$4"
android_manifest="$5"
desktop_manifest="$6"

# The seventh argument is reserved for a future signed pointer inventory version.
pointer_schema="$7"
[[ "$pointer_schema" == "1" ]]
[[ "$repository" == "Obiente/nc-native" ]]
[[ "$immutable_sha" =~ ^[a-f0-9]{40}$ ]]

case "$pointer_tag" in
    channel-prerelease)
        expected_channel="prerelease-v1"
        [[ "$immutable_tag" =~ ^v0\.[0-9]+\.[0-9]+-(alpha|beta|rc)\.[1-9][0-9]*$ ]]
        ;;
    channel-nightly)
        expected_channel="nightly-v1"
        [[ "$immutable_tag" =~ ^nightly-[0-9]{8}-[0-9]{4}-run[1-9][0-9]*-[a-f0-9]{8}$ ]]
        ;;
    *)
        printf 'Unsupported app update pointer tag: %s\n' "$pointer_tag" >&2
        exit 2
        ;;
esac

manifest_code() {
    local manifest="$1"
    local kind="$2"
    local expected_name
    case "$kind" in
        android) expected_name="update-manifest.json" ;;
        desktop) expected_name="desktop-update-manifest.json" ;;
        *) return 2 ;;
    esac
    jq -er \
        --arg channel "$expected_channel" \
        --arg tag "$immutable_tag" \
        --arg expected_name "$expected_name" \
        '
          select(.schemaVersion == 1) |
          select(.channel == $channel) |
          select(.versionCode | type == "number" and . > 0 and floor == .) |
          select(.releaseNotesUrl ==
            "https://github.com/Obiente/nc-native/releases/tag/" + $tag
          ) |
          if $expected_name == "update-manifest.json" then
            select(.apkUrl | startswith(
              "https://github.com/Obiente/nc-native/releases/download/" + $tag + "/"
            ))
          else
            select(.assets | type == "array" and length > 0) |
            select(all(.assets[]; .url | startswith(
              "https://github.com/Obiente/nc-native/releases/download/" + $tag + "/"
            )))
          end |
          .versionCode
        ' "$manifest"
}

declare -A candidates=()
declare -A candidate_codes=()
if [[ "$android_manifest" != "-" ]]; then
    [[ -f "$android_manifest" ]]
    candidates[update-manifest.json]="$android_manifest"
    candidate_codes[update-manifest.json]="$(manifest_code "$android_manifest" android)"
fi
if [[ "$desktop_manifest" != "-" ]]; then
    [[ -f "$desktop_manifest" ]]
    candidates[desktop-update-manifest.json]="$desktop_manifest"
    candidate_codes[desktop-update-manifest.json]="$(manifest_code "$desktop_manifest" desktop)"
fi
[[ "${#candidates[@]}" -gt 0 ]]
if [[ -n "${candidate_codes[update-manifest.json]:-}" ]] &&
    [[ -n "${candidate_codes[desktop-update-manifest.json]:-}" ]]; then
    [[ "${candidate_codes[update-manifest.json]}" == "${candidate_codes[desktop-update-manifest.json]}" ]]
fi

release_state="$(
    gh release view "$immutable_tag" \
        --repo "$repository" \
        --json isDraft,isPrerelease,tagName \
        --jq '[.isDraft, .isPrerelease, .tagName] | @tsv'
)"
test "$release_state" = $'false\ttrue\t'"$immutable_tag"

temporary="$(mktemp -d)"
trap 'rm -r -- "$temporary"' EXIT
existing_release=false
if gh release view "$pointer_tag" --repo "$repository" >/dev/null 2>&1; then
    existing_release=true
    pointer_state="$(
        gh release view "$pointer_tag" \
            --repo "$repository" \
            --json isDraft,isPrerelease,tagName \
            --jq '[.isDraft, .isPrerelease, .tagName] | @tsv'
    )"
    test "$pointer_state" = $'false\ttrue\t'"$pointer_tag"
    mkdir -p "$temporary/existing"
    gh release download "$pointer_tag" \
        --repo "$repository" \
        --pattern '*update-manifest.json' \
        --dir "$temporary/existing" >/dev/null 2>&1 || true
fi

mkdir -p "$temporary/publish"
for name in "${!candidates[@]}"; do
    current="$temporary/existing/$name"
    if [[ -f "$current" ]]; then
        kind="desktop"
        [[ "$name" == "update-manifest.json" ]] && kind="android"
        current_code="$(
            jq -er \
                --arg channel "$expected_channel" \
                'select(.schemaVersion == 1 and .channel == $channel) | .versionCode' \
                "$current"
        )"
        if (( current_code >= candidate_codes[$name] )); then
            printf '%s pointer already has version code %s.\n' "$name" "$current_code"
            continue
        fi
    fi
    cp "${candidates[$name]}" "$temporary/publish/$name"
done

mapfile -d '' publish_assets < <(find "$temporary/publish" -maxdepth 1 -type f -print0 | sort -z)
if [[ "${#publish_assets[@]}" -eq 0 ]]; then
    exit 0
fi

if [[ "$existing_release" == "true" ]]; then
    gh release upload "$pointer_tag" "${publish_assets[@]}" \
        --repo "$repository" \
        --clobber
    exit 0
fi

if ! gh api "repos/${repository}/git/ref/tags/${pointer_tag}" >/dev/null 2>&1; then
    gh api --method POST "repos/${repository}/git/refs" \
        -f "ref=refs/tags/${pointer_tag}" \
        -f "sha=${immutable_sha}" >/dev/null
fi

printf 'This release provides current verified app update metadata for the %s channel.\n' \
    "$expected_channel" >"$temporary/notes.md"
gh release create "$pointer_tag" "${publish_assets[@]}" \
    --repo "$repository" \
    --verify-tag \
    --prerelease \
    --latest=false \
    --title "Nextcloud Native ${expected_channel} update channel" \
    --notes-file "$temporary/notes.md"
