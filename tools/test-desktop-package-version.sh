#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
derive="$project_root/tools/derive-desktop-package-version.sh"

test "$("$derive" 1 nightly)" = "1.0.11"
test "$("$derive" 1 alpha)" = "1.0.12"
test "$("$derive" 1 beta)" = "1.0.13"
test "$("$derive" 1 rc)" = "1.0.14"
test "$("$derive" 6553 nightly)" = "1.0.65531"
test "$("$derive" 6554 nightly)" = "1.1.5"
test "$("$derive" 1677721 rc)" = "1.255.65534"

if "$derive" 0 nightly >/dev/null 2>&1; then
    echo "A zero CI run number must be rejected." >&2
    exit 1
fi
if "$derive" 1677722 nightly >/dev/null 2>&1; then
    echo "An out-of-range CI run number must be rejected." >&2
    exit 1
fi
if "$derive" 1 stable >/dev/null 2>&1; then
    echo "An unsupported desktop package channel must be rejected." >&2
    exit 1
fi

printf 'Shared desktop package-version allocation checks passed.\n'
