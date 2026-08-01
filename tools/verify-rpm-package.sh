#!/usr/bin/env bash
set -euo pipefail

package="${1:?RPM package is required.}"

if [[ "$#" -ne 1 || ! -f "$package" || "$package" != *.rpm ]]; then
    printf 'Expected exactly one existing RPM package.\n' >&2
    exit 2
fi
for required_command in rpm2cpio cpio; do
    if ! command -v "$required_command" >/dev/null 2>&1; then
        printf '%s is required to verify the RPM package.\n' "$required_command" >&2
        exit 2
    fi
done

package_files="$(rpm2cpio "$package" | cpio -it --quiet | sed 's#^\./#/#')"
build_id_paths="$({
    printf '%s\n' "$package_files" |
        grep -E '^/usr/lib/\.build-id(/|$)' || true
})"

if [[ -n "$build_id_paths" ]]; then
    printf 'RPM packages must not claim global build-ID paths:\n%s\n' \
        "$build_id_paths" >&2
    exit 1
fi

printf 'Verified readable RPM payload without global build-ID paths: %s\n' "$package"
