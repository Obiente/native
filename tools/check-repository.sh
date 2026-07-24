#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_root"

generated_pattern='(^|/)(build|target|\.gradle|\.kotlin)/|(^|/)(local\.properties|[^/]+\.hprof)$'
machine_pattern='(/home/[^/[:space:]]+|/Users/[^/[:space:]]+|[A-Za-z]:\\Users\\|192\.168\.[0-9]+\.[0-9]+)'
credential_pattern='BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY|github_pat_[A-Za-z0-9_]{20,}|gh[pousr]_[A-Za-z0-9_]{20,}|AIza[0-9A-Za-z_-]{35}|xox[baprs]-[A-Za-z0-9-]+'

gradle_caching="$(
    awk -F= '
        /^[[:space:]]*org\.gradle\.caching[[:space:]]*=/ {
            value = $2
            gsub(/[[:space:]]/, "", value)
        }
        END { print value }
    ' gradle.properties
)"
if [[ "$gradle_caching" != "true" ]]; then
    printf 'Gradle task-output caching must stay enabled in gradle.properties.\n' >&2
    exit 1
fi

mapfile -d '' candidate_files < <(git ls-files -z --cached --others --exclude-standard)
if [[ "${#candidate_files[@]}" -eq 0 ]]; then
    printf 'No repository files were found.\n' >&2
    exit 1
fi

generated="$(printf '%s\n' "${candidate_files[@]}" | grep -E "$generated_pattern" || true)"
if [[ -n "$generated" ]]; then
    printf 'Generated or machine-local files are tracked:\n%s\n' "$generated" >&2
    exit 1
fi

for file in "${candidate_files[@]}"; do
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

printf 'Repository hygiene checks passed.\n'
