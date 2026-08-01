#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
prepare_workflow="$project_root/.github/workflows/refresh-marketing-captures.yml"
commit_workflow="$project_root/.github/workflows/commit-marketing-captures.yml"
ci="$project_root/.github/workflows/ci.yml"

require_text() {
    local file="$1"
    local expected="$2"
    if ! grep -Fq -- "$expected" "$file"; then
        printf '%s is missing required capture automation: %s\n' "$file" "$expected" >&2
        exit 1
    fi
}

require_text "$prepare_workflow" 'pull_request:'
require_text "$prepare_workflow" 'contents: read'
require_text "$prepare_workflow" 'persist-credentials: false'
require_text "$prepare_workflow" '.github/workflows/refresh-marketing-captures.yml'
require_text "$prepare_workflow" 'repository: ${{ github.event.pull_request.head.repo.full_name }}'
require_text "$prepare_workflow" 'ref: ${{ github.event.pull_request.head.sha }}'
require_text "$prepare_workflow" 'HEAD_AUTHOR: ${{ github.event.pull_request.user.login }}'
require_text "$prepare_workflow" 'HEAD_REPOSITORY: ${{ github.event.pull_request.head.repo.full_name }}'
require_text "$prepare_workflow" 'website/public/demo-media/**'
require_text "$prepare_workflow" 'if [[ -n "${outside_status}" ]]; then'
require_text "$prepare_workflow" 'elif [[ "${HEAD_REPOSITORY}" != "${GITHUB_REPOSITORY}" || "${HEAD_AUTHOR}" == '\''dependabot[bot]'\'' ]]; then'
require_text "$prepare_workflow" 'git status --porcelain --untracked-files=all -- website/public/screenshots'
require_text "$prepare_workflow" 'node tools/changelog-fragments.mjs check-diff'
require_text "$prepare_workflow" 'git diff --binary --full-index -- website/public/screenshots'
require_text "$prepare_workflow" 'name: marketing-capture-refresh'

require_text "$commit_workflow" 'workflow_run:'
require_text "$commit_workflow" 'actions: read'
require_text "$commit_workflow" 'contents: read'
require_text "$commit_workflow" 'pull-requests: read'
require_text "$commit_workflow" 'name: Validate marketing captures'
require_text "$commit_workflow" "if: needs.inspect.outputs.changed == 'true'"
require_text "$commit_workflow" 'environment: capture-automation'
require_text "$commit_workflow" 'github.event.workflow_run.head_repository.full_name == github.repository'
require_text "$commit_workflow" 'actions/create-github-app-token@bcd2ba49218906704ab6c1aa796996da409d3eb1'
require_text "$commit_workflow" 'app-id: ${{ vars.OBIENTE_AUTOMATIONS_APP_ID }}'
require_text "$commit_workflow" 'private-key: ${{ secrets.OBIENTE_AUTOMATIONS_PRIVATE_KEY }}'
require_text "$commit_workflow" 'repositories: ${{ github.event.repository.name }}'
require_text "$commit_workflow" 'permission-contents: write'
require_text "$commit_workflow" 'git apply --index --binary "${patch}"'
require_text "$commit_workflow" 'if [[ "${path}" != website/public/screenshots/* ]]; then'
require_text "$commit_workflow" 'git config user.name "${APP_SLUG}[bot]"'
require_text "$commit_workflow" "git commit -m 'chore(website): refresh marketing captures'"
require_text "$commit_workflow" 'git push origin "HEAD:refs/heads/${HEAD_REF}"'

for stale_gate in \
    'Verify marketing capture freshness' \
    'Verify deterministic marketing capture regeneration' \
    'git diff --exit-code -- website/public/screenshots' \
    "steps.changes.outputs.capture_inputs == 'true'"; do
    if grep -Fq -- "$stale_gate" "$ci"; then
        printf 'Build and test must not block on stale marketing captures: %s\n' "$stale_gate" >&2
        exit 1
    fi
done

if grep -Fq 'pull_request_target:' "$prepare_workflow" "$commit_workflow"; then
    printf 'Capture automation must not execute pull request code with pull_request_target.\n' >&2
    exit 1
fi

for secret_reference in \
    'OBIENTE_AUTOMATIONS_APP_ID' \
    'OBIENTE_AUTOMATIONS_PRIVATE_KEY' \
    'actions/create-github-app-token'; do
    if grep -Fq -- "$secret_reference" "$prepare_workflow"; then
        printf 'Pull request capture preparation must not access bot credentials: %s\n' "$secret_reference" >&2
        exit 1
    fi
done

for untrusted_execution in './gradlew' 'npm ' 'node '; do
    if grep -Fq -- "$untrusted_execution" "$commit_workflow"; then
        printf 'Privileged capture commit workflow must not execute pull request code: %s\n' "$untrusted_execution" >&2
        exit 1
    fi
done

guard_line="$(grep -nF 'if [[ "${path}" != website/public/screenshots/* ]]; then' "$commit_workflow" | cut -d: -f1)"
push_line="$(grep -nF 'git push origin "HEAD:refs/heads/${HEAD_REF}"' "$commit_workflow" | cut -d: -f1)"
if [[ -z "$guard_line" || -z "$push_line" || "$guard_line" -ge "$push_line" ]]; then
    printf 'The capture path guard must execute before bot pushes.\n' >&2
    exit 1
fi

printf 'Marketing capture workflow contract passed.\n'
