#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_root"

generated_pattern='(^|/)(build|target|\.gradle|\.kotlin)/|(^|/)(local\.properties|[^/]+\.hprof)$'
machine_pattern='(/home/[^/[:space:]]+|/Users/[^/[:space:]]+|[A-Za-z]:\\Users\\|192\.168\.[0-9]+\.[0-9]+)'
credential_pattern='BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY|github_pat_[A-Za-z0-9_]{20,}|gh[pousr]_[A-Za-z0-9_]{20,}|AIza[0-9A-Za-z_-]{35}|xox[baprs]-[A-Za-z0-9-]+'

mapfile -d '' candidate_files < <(git ls-files -z --cached --others --exclude-standard)
if [[ "${#candidate_files[@]}" -eq 0 ]]; then
    printf 'No repository files were found.\n' >&2
    exit 1
fi

# Report every missing prerequisite before any slow check starts.
missing_tools=()
for tool in rustc node python3 jq; do
    command -v "$tool" >/dev/null 2>&1 || missing_tools+=("$tool")
done
if [[ "${#missing_tools[@]}" -gt 0 ]]; then
    printf 'Repository checks need these tools on PATH: %s\n' "${missing_tools[*]}" >&2
    printf 'See CONTRIBUTING.md for the development prerequisites.\n' >&2
    exit 1
fi

temporary_directory="$(mktemp -d)"
trap 'rm -rf -- "$temporary_directory"' EXIT

bash tools/test-text-hygiene.sh
node --test tools/test-ci-source-scopes.mjs
bash tools/test-kotlin-architecture.sh
bash tools/check-kotlin-architecture.sh
rustc --edition=2021 tools/text-hygiene.rs \
    -o "$temporary_directory/text-hygiene"
printf '%s\0' "${candidate_files[@]}" |
    "$temporary_directory/text-hygiene" --null

generated="$(printf '%s\n' "${candidate_files[@]}" | grep -E "$generated_pattern" || true)"
if [[ -n "$generated" ]]; then
    printf 'Generated or machine-local files are tracked:\n%s\n' "$generated" >&2
    exit 1
fi

# Scan in batches: one grep process per file is very slow on Windows.
scan_files=()
for file in "${candidate_files[@]}"; do
    [[ -f "$file" && "$file" != "tools/check-repository.sh" ]] && scan_files+=("$file")
done
scan_matches() {
    printf '%s\0' "${scan_files[@]}" | xargs -0 grep -H -n -I -E -- "$1" || true
}
machine_matches="$(scan_matches "$machine_pattern")"
if [[ -n "$machine_matches" ]]; then
    printf '%s\n' "$machine_matches"
    printf 'Machine-specific paths or LAN addresses are present in the files above.\n' >&2
    exit 1
fi
credential_matches="$(scan_matches "$credential_pattern")"
if [[ -n "$credential_matches" ]]; then
    printf '%s\n' "$credential_matches"
    printf 'A credential-shaped value is present in the files above.\n' >&2
    exit 1
fi

bash tools/test-apksigner-certificate-parser.sh
bash tools/test-android-emulator.sh
bash tools/test-build-jvm-criteria.sh
node tools/changelog-fragments.mjs validate
node tools/check-markdown-links.mjs
node --test tools/check-markdown-links.test.mjs
node --test tools/changelog-fragments.test.mjs
node --test tools/legacy-update-manifest-compatibility.test.mjs
node --test tools/nightly-release-notes.test.mjs
node --test tools/release-download-table.test.mjs
bash tools/test-desktop-package-version.sh
bash tools/test-release-repository.sh
bash tools/test-android-update-manifest-assets.sh
bash tools/test-nightly-release-workflow.sh
bash tools/test-marketing-capture-workflow.sh
bash tools/test-update-channel-promotion.sh
bash tools/test-download-channel-promotion.sh
bash tools/test-linux-package-metadata.sh
bash tools/test-desktop-update-manifest.sh

bash tools/test-jq-output-portability.sh

printf 'Repository hygiene checks passed.\n'
