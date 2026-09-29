#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
source "$project_root/tools/nextcloud-demo.sh" --help >/dev/null
fixture_root="$(mktemp -d)"
trap 'rm -rf -- "$fixture_root"' EXIT
# Override every mutable initialization path before generating a disposable CA.
state_root="$fixture_root/state"
report_root="$fixture_root/reports"
environment_file="$fixture_root/demo.env"
initialize localhost >/dev/null
openssl verify -x509_strict -purpose sslserver \
    -verify_hostname localhost -CAfile "$state_root/tls/ca.crt" \
    "$state_root/tls/server.crt" >/dev/null
openssl verify -x509_strict -purpose sslserver \
    -verify_ip 10.0.2.2 -CAfile "$state_root/tls/ca.crt" \
    "$state_root/tls/server.crt" >/dev/null
if openssl verify -x509_strict -purpose sslserver \
    -verify_hostname unrelated.example.invalid -CAfile "$state_root/tls/ca.crt" \
    "$state_root/tls/server.crt" >/dev/null 2>&1; then
    printf 'Demo certificate unexpectedly accepted an unrelated hostname.\n' >&2
    exit 1
fi
printf 'Demo strict certificate checks passed.\n'
