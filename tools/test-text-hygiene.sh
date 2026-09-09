#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
temporary_directory="$(mktemp -d)"
# MSYS converts command-line arguments for native executables, but not paths on stdin.
# The scanner receives these fixture paths through its NUL-delimited input stream.
if command -v cygpath >/dev/null 2>&1; then
    temporary_directory="$(cygpath -m "$temporary_directory")"
fi
trap 'rm -rf -- "$temporary_directory"' EXIT

rustc --edition=2021 --test \
    "$project_root/tools/text-hygiene.rs" \
    -o "$temporary_directory/text-hygiene-tests"

"$temporary_directory/text-hygiene-tests"

rustc --edition=2021 \
    "$project_root/tools/text-hygiene.rs" \
    -o "$temporary_directory/text-hygiene"

printf 'Normal UTF-8 letters remain valid: Caf\303\251.\n' \
    >"$temporary_directory/accepted file.txt"
printf '%s\0' "$temporary_directory/accepted file.txt" |
    "$temporary_directory/text-hygiene" --null

printf 'Bad punctuation: \342\200\234quote\342\200\235 and wait\342\200\246\n' \
    >"$temporary_directory/rejected.txt"
if printf '%s\0' "$temporary_directory/rejected.txt" |
    "$temporary_directory/text-hygiene" --null \
        >"$temporary_directory/rejected.out" 2>&1; then
    printf 'Text hygiene scanner accepted a forbidden-character fixture.\n' >&2
    exit 1
fi

grep -q 'U+201C' "$temporary_directory/rejected.out"
grep -q 'U+201D' "$temporary_directory/rejected.out"
grep -q 'U+2026' "$temporary_directory/rejected.out"
