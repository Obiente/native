#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
demo_root="$project_root/integration/nextcloud-demo"
helper="$project_root/tools/nextcloud-demo.sh"
manifest="$demo_root/apps/representative.tsv"

bash -n "$helper"
"$helper" --help | grep -Fq 'reset --confirm'
"$helper" --help | grep -Fq 'credentials, and certificates for reinitialization'
"$helper" --help | grep -Fq 'stage-catalog'
"$helper" --help | grep -Fq 'android-session <instance> [server-url]'

invalid_app_ids="$(
    awk 'NF && $1 !~ /^#/ { print $1 }' "$manifest" |
        grep -Ev '^[a-z0-9][a-z0-9_]{0,63}$' || true
)"
[[ -z "$invalid_app_ids" ]] || {
    printf 'Invalid representative app IDs:\n%s\n' "$invalid_app_ids" >&2
    exit 1
}

duplicate_app_ids="$(
    awk 'NF && $1 !~ /^#/ { print $1 }' "$manifest" |
        sort |
        uniq -d
)"
[[ -z "$duplicate_app_ids" ]] || {
    printf 'Duplicate representative app IDs:\n%s\n' "$duplicate_app_ids" >&2
    exit 1
}

grep -Fq 'nextcloud:34.0.3-apache' "$demo_root/compose.yml"
grep -Fq 'condition: service_healthy' "$demo_root/compose.yml"
grep -Fq '127.0.0.1:${NC_DEMO_HTTP_PORT:-18080}:80' "$demo_root/compose.yml"
grep -Fq './.state/tls:/etc/caddy/tls:ro,Z' "$demo_root/compose.yml"
grep -Fq './.state/tls/ca-bundle.crt:/etc/ssl/certs/ca-certificates.crt:ro,Z' "$demo_root/compose.yml"
grep -Fq '/opt/obiente_native_bridge:ro,Z' "$demo_root/compose.yml"
grep -Fq 'hooks/pre-installation/10-volume-permissions.sh' "$demo_root/compose.yml"
grep -Fq 'ensure_ca_bundle' "$helper"
grep -Fq 'rm -rf -- "$state_root/tls"' "$helper"
grep -Fq 'Run `tools/nextcloud-demo.sh init [host]` before starting a' "$demo_root/README.md"
grep -Fq 'readiness_url="$(local_server_url)"' "$helper"
grep -Fq 'validated_android_server_url' "$helper"
grep -Fq 'openssl x509 -in "$state_root/tls/server.crt" -noout -checkip' "$helper"
grep -Fq "'.serverUrl = \$serverUrl'" "$helper"
if grep -Fq 'before-starting/10-demo-runtime.sh' "$demo_root/compose.yml" ||
    grep -Fq 'update-ca-certificates' "$demo_root/hooks/pre-installation/10-volume-permissions.sh"; then
    printf 'The demo must not mutate the container CA bundle from an unprivileged startup hook.\n' >&2
    exit 1
fi

if command -v podman >/dev/null 2>&1; then
    "$helper" validate >/dev/null
fi

printf 'Nextcloud demo contract checks passed.\n'
