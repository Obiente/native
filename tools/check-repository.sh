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

if ! command -v rustc >/dev/null 2>&1; then
    printf 'Rust stable is required to run repository hygiene checks.\n' >&2
    exit 1
fi
if ! command -v node >/dev/null 2>&1; then
    printf 'Node.js is required to validate changelog fragments.\n' >&2
    exit 1
fi

temporary_directory="$(mktemp -d)"
trap 'rm -rf -- "$temporary_directory"' EXIT

bash tools/test-text-hygiene.sh
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

for file in "${candidate_files[@]}"; do
    [[ -f "$file" ]] || continue
    [[ "$file" == "tools/check-repository.sh" ]] && continue
    if grep -n -I -E "$machine_pattern" -- "$file"; then
        printf 'Machine-specific paths or LAN addresses are present in %s.\n' "$file" >&2
        exit 1
    fi
    if grep -n -I -E "$credential_pattern" -- "$file"; then
        printf 'A credential-shaped value is present in %s.\n' "$file" >&2
        exit 1
    fi
done

bash tools/test-apksigner-certificate-parser.sh
bash tools/test-build-jvm-criteria.sh
node tools/changelog-fragments.mjs validate
node tools/check-markdown-links.mjs
node --test tools/check-markdown-links.test.mjs
node --test tools/changelog-fragments.test.mjs
node --test tools/update-changelog.test.mjs
node --test tools/nightly-release-notes.test.mjs
node --test tools/release-download-table.test.mjs
bash tools/test-desktop-package-version.sh
bash tools/test-android-update-manifest-assets.sh
bash tools/test-nightly-release-workflow.sh
bash tools/test-marketing-capture-workflow.sh
bash tools/test-update-channel-promotion.sh
bash tools/test-download-channel-promotion.sh
bash tools/test-linux-package-metadata.sh
bash tools/test-desktop-update-manifest.sh

printf 'Repository hygiene checks passed.\n'
