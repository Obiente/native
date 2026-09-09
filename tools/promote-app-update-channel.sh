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
source "$(dirname "${BASH_SOURCE[0]}")/release-repository.sh"
[[ "$immutable_sha" =~ ^[a-f0-9]{40}$ ]]
max_android_apk_bytes=268435456

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

android_manifest_code() {
    local manifest="$1"
    local required_tag="$2"
    jq -er \
        --arg channel "$expected_channel" \
        --arg required_tag "$required_tag" \
        --argjson maximum_apk_size "$max_android_apk_bytes" \
        '
          select(keys == [
            "apkSha256", "apkSize", "apkUrl", "channel", "minimumAndroidSdk",
            "packageName", "releaseNotesUrl", "schemaVersion",
            "signingCertificateSha256Digests", "versionCode", "versionName"
          ]) |
          select(.schemaVersion == 1 and .channel == $channel) |
          select(
            (.versionName | type == "string" and length > 0 and length <= 64) and
            if $channel == "prerelease-v1" then
              (.versionName | test("^0\\.[0-9]+\\.[0-9]+-(alpha|beta|rc)\\.[0-9]+$"))
            else
              (.versionName | test("^nightly-[0-9]{8}-[0-9]{4}-run[1-9][0-9]*-[a-f0-9]{8}$"))
            end
          ) |
          select(.versionCode | type == "number" and . > 0 and floor == .) |
          select(.packageName == "dev.obiente.nextcloudnative") |
          select(.minimumAndroidSdk |
            type == "number" and . >= 26 and . <= 64 and floor == .
          ) |
          select(.apkSize |
            type == "number" and . > 0 and . <= $maximum_apk_size and floor == .
          ) |
          select(.apkSha256 | type == "string" and test("^[a-f0-9]{64}$")) |
          select(
            .signingCertificateSha256Digests |
            type == "array" and length >= 1 and length <= 8 and
            length == (unique | length) and
            all(.[]; type == "string" and test("^[a-f0-9]{64}$"))
          ) |
          (if $channel == "prerelease-v1" then
            "v" + .versionName
          else
            .versionName
          end) as $tag |
          select($required_tag == "" or $tag == $required_tag) |
          select(.releaseNotesUrl ==
            "https://github.com/Obiente/nc-native/releases/tag/" + $tag
          ) |
          ("https://github.com/Obiente/nc-native/releases/download/" + $tag + "/") as $apk_prefix |
          select(.apkUrl | type == "string" and startswith($apk_prefix) and endswith(".apk")) |
          select(.apkUrl | ltrimstr($apk_prefix) |
            test("^[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?$")) |
          .versionCode
        ' "$manifest"
}

desktop_manifest_code() {
    local manifest="$1"
    jq -er \
        --arg channel "$expected_channel" \
        --arg tag "$immutable_tag" \
        '
          select(keys == [
            "assets", "channel", "packageVersion", "releaseNotesUrl",
            "schemaVersion", "versionCode", "versionName"
          ]) |
          select(.schemaVersion == 1 and .channel == $channel) |
          select(.versionCode | type == "number" and . > 0 and floor == .) |
          select(.releaseNotesUrl ==
            "https://github.com/Obiente/nc-native/releases/tag/" + $tag
          ) |
          select(.assets | type == "array" and length > 0) |
          select(all(.assets[];
            keys == ["architecture", "format", "platform", "sha256", "size", "url"]
          )) |
          select(all(.assets[]; .url | startswith(
            "https://github.com/Obiente/nc-native/releases/download/" + $tag + "/"
          ))) |
          .versionCode
        ' "$manifest"
}

desktop_pointer_state() {
    local manifest="$1"
    local candidate_code="$2"
    jq -er \
        --arg channel "$expected_channel" \
        --argjson candidate "$candidate_code" \
        '
          . as $manifest |
          select(keys == [
            "assets", "channel", "packageVersion", "releaseNotesUrl", "schemaVersion",
            "versionCode", "versionName"
          ]) |
          select(.schemaVersion == 1 and .channel == $channel) |
          select(.versionName | type == "string" and length > 0) |
          select(.versionCode | type == "number" and . > 0 and floor == .) |
          select(.packageVersion | type == "string" and test("^[1-9][0-9]*\\.[0-9]+\\.[0-9]+$")) |
          select(
            .releaseNotesUrl | type == "string" and
            startswith("https://github.com/Obiente/nc-native/releases/tag/")
          ) |
          (.releaseNotesUrl | ltrimstr("https://github.com/Obiente/nc-native/releases/tag/")) as $tag |
          select(
            if $channel == "prerelease-v1" then
              ($tag | test("^v0\\.[0-9]+\\.[0-9]+-(alpha|beta|rc)\\.[1-9][0-9]*$")) and
              ("v" + .versionName == $tag)
            else
              ($tag | test("^nightly-[0-9]{8}-[0-9]{4}-run[1-9][0-9]*-[a-f0-9]{8}$")) and
              (.versionName == $tag)
            end
          ) |
          ("https://github.com/Obiente/nc-native/releases/download/" + $tag + "/") as $asset_prefix |
          select(.assets | type == "array" and length >= 1 and length <= 8) |
          select(all(.assets[]; . as $asset |
            (keys == ["architecture", "format", "platform", "sha256", "size", "url"]) and
            (
              (.platform == "linux" and (.format == "deb" or .format == "rpm")) or
              (.platform == "windows" and .format == "msi") or
              (.platform == "macos" and .format == "dmg")
            ) and
            (.architecture == "x86_64" or .architecture == "aarch64") and
            (.size | type == "number" and . > 0 and . <= 536870912 and floor == .) and
            (.sha256 | type == "string" and test("^[a-f0-9]{64}$")) and
            (.url | type == "string" and startswith($asset_prefix) and
              endswith("." + $asset.format) and
              (ltrimstr($asset_prefix) | test("^[A-Za-z0-9][A-Za-z0-9._+-]*$")))
          )) |
          select(
            .assets | map([.platform, .format, .architecture] | join(":")) |
            length == (unique | length)
          ) |
          [
            (if .versionCode >= $candidate then "keep" else "replace" end),
            (.versionCode | tostring)
          ] |
          @tsv
        ' \
        "$manifest"
}

declare -A candidates=()
declare -A candidate_codes=()
if [[ "$android_manifest" != "-" ]]; then
    [[ -f "$android_manifest" ]]
    candidates[update-manifest.json]="$android_manifest"
    candidate_codes[update-manifest.json]="$(android_manifest_code "$android_manifest" "$immutable_tag")"
fi
if [[ "$desktop_manifest" != "-" ]]; then
    [[ -f "$desktop_manifest" ]]
    candidates[desktop-update-manifest.json]="$desktop_manifest"
    candidate_codes[desktop-update-manifest.json]="$(desktop_manifest_code "$desktop_manifest")"
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
        if [[ "$name" == "desktop-update-manifest.json" ]]; then
            if ! current_state="$(
                desktop_pointer_state "$current" "${candidate_codes[$name]}" 2>/dev/null
            )"; then
                current_state=$'replace\tinvalid'
            fi
        else
            if ! current_code="$(android_manifest_code "$current" "" 2>/dev/null)"; then
                current_state=$'replace\tinvalid'
            else
                current_state="$(
                    jq -nr \
                        --argjson current "$current_code" \
                        --argjson candidate "${candidate_codes[$name]}" \
                        '[
                          (if $current >= $candidate then "keep" else "replace" end),
                          ($current | tostring)
                        ] | @tsv'
                )"
            fi
        fi
        IFS=$'\t' read -r promotion_action current_code <<<"$current_state"
        if [[ "$promotion_action" == "keep" ]]; then
            printf '%s pointer already has version code %s.\n' "$name" "$current_code"
            continue
        fi
        [[ "$promotion_action" == "replace" ]]
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
    --title "nati.ve ${expected_channel} update channel" \
    --notes-file "$temporary/notes.md"
