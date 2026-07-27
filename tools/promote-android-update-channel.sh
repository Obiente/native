#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" -ne 5 ]]; then
    printf 'Usage: %s REPOSITORY POINTER_TAG IMMUTABLE_TAG IMMUTABLE_SHA MANIFEST\n' "$0" >&2
    exit 2
fi

repository="$1"
pointer_tag="$2"
immutable_tag="$3"
immutable_sha="$4"
manifest="$5"

[[ "$repository" == "Obiente/nc-native" ]]
[[ "$immutable_sha" =~ ^[a-f0-9]{40}$ ]]
[[ -f "$manifest" ]]

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
        printf 'Unsupported Android update pointer tag: %s\n' "$pointer_tag" >&2
        exit 2
        ;;
esac

candidate_code="$(
    jq -er \
        --arg channel "$expected_channel" \
        --arg tag "$immutable_tag" \
        '
          select(.schemaVersion == 1) |
          select(.channel == $channel) |
          select(.versionCode | type == "number" and . > 0 and floor == .) |
          select(.apkUrl | startswith(
            "https://github.com/Obiente/nc-native/releases/download/" + $tag + "/"
          )) |
          select(.releaseNotesUrl ==
            "https://github.com/Obiente/nc-native/releases/tag/" + $tag
          ) |
          .versionCode
        ' \
        "$manifest"
)"

release_state="$(
    gh release view "$immutable_tag" \
        --repo "$repository" \
        --json isDraft,isPrerelease,tagName \
        --jq '[.isDraft, .isPrerelease, .tagName] | @tsv'
)"
test "$release_state" = $'false\ttrue\t'"$immutable_tag"

temporary="$(mktemp -d)"
trap 'rm -r -- "$temporary"' EXIT
pointer_manifest="$temporary/update-manifest.json"

if gh release view "$pointer_tag" --repo "$repository" >/dev/null 2>&1; then
    pointer_state="$(
        gh release view "$pointer_tag" \
            --repo "$repository" \
            --json isDraft,isPrerelease,tagName \
            --jq '[.isDraft, .isPrerelease, .tagName] | @tsv'
    )"
    test "$pointer_state" = $'false\ttrue\t'"$pointer_tag"
    mkdir -p "$temporary/existing"
    if gh release download "$pointer_tag" \
        --repo "$repository" \
        --pattern update-manifest.json \
        --dir "$temporary/existing" >/dev/null 2>&1; then
        current_manifest="$temporary/existing/update-manifest.json"
        current_code="$(
            jq -er \
                --arg channel "$expected_channel" \
                '
                  select(.schemaVersion == 1) |
                  select(.channel == $channel) |
                  .versionCode |
                  select(type == "number" and . > 0 and floor == .)
                ' \
                "$current_manifest"
        )"
        if (( current_code >= candidate_code )); then
            printf 'Android update pointer %s already has version code %s.\n' \
                "$pointer_tag" "$current_code"
            exit 0
        fi
    fi

    cp "$manifest" "$pointer_manifest"
    gh release upload "$pointer_tag" "$pointer_manifest" \
        --repo "$repository" \
        --clobber
    exit 0
fi

if ! gh api "repos/${repository}/git/ref/tags/${pointer_tag}" >/dev/null 2>&1; then
    gh api --method POST "repos/${repository}/git/refs" \
        -f "ref=refs/tags/${pointer_tag}" \
        -f "sha=${immutable_sha}" >/dev/null
fi

cp "$manifest" "$pointer_manifest"
printf 'This release provides the current signed Android update manifest for the %s channel.\n' \
    "$expected_channel" >"$temporary/notes.md"
gh release create "$pointer_tag" "$pointer_manifest" \
    --repo "$repository" \
    --verify-tag \
    --prerelease \
    --latest=false \
    --title "Nextcloud Native Android ${expected_channel} channel" \
    --notes-file "$temporary/notes.md"
