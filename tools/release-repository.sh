#!/usr/bin/env bash

# GitHub API operations use the execution repository. Installed clients still
# require the original repository URLs in the frozen v1 update manifests.
case "${repository,,}" in
    obiente/native|obiente/nc-native) ;;
    *) printf 'Unsupported release repository: %s\n' "$repository" >&2; exit 2 ;;
esac
release_url_repository="Obiente/nc-native"

# Native Windows jq emits CRLF. Normalize only line terminators, not metadata,
# and preserve jq failures with pipefail. This also supports Unix jq 1.6.
release_jq() (
    set -o pipefail
    jq "$@" | sed 's/\r$//'
)
