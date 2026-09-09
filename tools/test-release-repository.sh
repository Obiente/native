#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
for repository in Obiente/native obiente/native Obiente/nc-native; do
    source "$project_root/tools/release-repository.sh"
    [[ "$release_url_repository" == "Obiente/nc-native" ]]
done
for repository in other/native Obiente/native-fork obiente/native/extra ''; do
    if (source "$project_root/tools/release-repository.sh") 2>/dev/null; then
        printf 'Accepted an untrusted execution repository.\n' >&2
        exit 1
    fi
done
printf 'Release execution repository and legacy URL separation checks passed.\n'
