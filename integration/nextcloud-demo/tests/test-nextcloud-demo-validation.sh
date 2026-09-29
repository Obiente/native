#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
source "$project_root/tools/nextcloud-demo.sh" --help >/dev/null

provider_status=0
podman() {
    [[ "$*" == 'compose --env-file .env.example -f compose.yml config' ]] || return 99
    printf 'Synthetic rendered configuration must remain hidden.\n'
    return "$provider_status"
}
output="$(validate)"
[[ "$output" == 'Nextcloud demo configuration is valid.' ]]

provider_status=23
if output="$(validate)"; then
    printf 'An invalid Compose configuration was reported as valid.\n' >&2
    exit 1
else
    status=$?
fi
[[ "$status" == 23 && -z "$output" ]]
printf 'Demo Compose validation checks passed.\n'
