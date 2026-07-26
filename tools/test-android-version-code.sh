#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
derive="$project_root/tools/derive-android-version-code.sh"

nightly="$("$derive" 41 nightly)"
alpha="$("$derive" 41 alpha)"
beta="$("$derive" 41 beta)"
next_nightly="$("$derive" 42 nightly)"

test "$nightly" -eq 20000411
test "$alpha" -eq 20000412
test "$beta" -eq 20000413
test "$nightly" -lt "$alpha"
test "$alpha" -lt "$beta"
test "$beta" -lt "$next_nightly"

if "$derive" 0 nightly >/dev/null 2>&1; then
    echo "A zero CI run number must be rejected." >&2
    exit 1
fi
if "$derive" 41 stable >/dev/null 2>&1; then
    echo "An unallocated channel must be rejected." >&2
    exit 1
fi

printf 'Shared Android version-code allocation checks passed.\n'
