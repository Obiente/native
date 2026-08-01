#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
workflow="$project_root/.github/workflows/refresh-marketing-captures.yml"
ci="$project_root/.github/workflows/ci.yml"

require_text() {
    local file="$1"
    local expected="$2"
    if ! grep -Fq -- "$expected" "$file"; then
        printf '%s is missing required capture automation: %s\n' "$file" "$expected" >&2
        exit 1
    fi
}

require_text "$workflow" 'pull_request:'
require_text "$workflow" 'actions: write'
require_text "$workflow" 'contents: write'
require_text "$workflow" '.github/workflows/refresh-marketing-captures.yml'
require_text "$workflow" 'repository: ${{ github.event.pull_request.head.repo.full_name }}'
require_text "$workflow" 'ref: ${{ github.event.pull_request.head.sha }}'
require_text "$workflow" 'HEAD_AUTHOR: ${{ github.event.pull_request.user.login }}'
require_text "$workflow" 'HEAD_REPOSITORY: ${{ github.event.pull_request.head.repo.full_name }}'
require_text "$workflow" 'website/public/demo-media/**'
require_text "$workflow" 'if [[ "${HEAD_REPOSITORY}" != "${GITHUB_REPOSITORY}" || "${HEAD_AUTHOR}" == '\''dependabot[bot]'\'' ]]; then'
require_text "$workflow" 'git status --porcelain --untracked-files=all -- website/public/screenshots'
require_text "$workflow" "git diff --quiet -- . ':(exclude)website/public/screenshots/**'"
require_text "$workflow" 'node tools/changelog-fragments.mjs check-diff'
require_text "$workflow" "git commit -m 'chore(website): refresh marketing captures'"
require_text "$workflow" 'git push origin "HEAD:${HEAD_REF}"'
require_text "$workflow" 'gh workflow run ci.yml'
require_text "$ci" 'Verify deterministic marketing capture regeneration'
require_text "$ci" 'website/public/demo-media/**'
require_text "$ci" 'git diff --exit-code -- website/public/screenshots'
require_text "$ci" 'steps.changes.outputs.capture_inputs == '\''true'\'''

if grep -Fq 'pull_request_target:' "$workflow"; then
    printf 'Capture automation must not execute pull request code with pull_request_target.\n' >&2
    exit 1
fi

guard_line="$(grep -nF 'if [[ "${HEAD_REPOSITORY}" != "${GITHUB_REPOSITORY}" || "${HEAD_AUTHOR}" == '\''dependabot[bot]'\'' ]]; then' "$workflow" | cut -d: -f1)"
push_line="$(grep -nF 'git push origin "HEAD:${HEAD_REF}"' "$workflow" | cut -d: -f1)"
if [[ -z "$guard_line" || -z "$push_line" || "$guard_line" -ge "$push_line" ]]; then
    printf 'The same-repository guard must execute before capture automation pushes.\n' >&2
    exit 1
fi

printf 'Marketing capture workflow contract passed.\n'
