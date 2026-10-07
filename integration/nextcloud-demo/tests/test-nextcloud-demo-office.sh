#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
source "$project_root/tools/nextcloud-demo.sh" --help >/dev/null

# Stub only the process boundary: do not read credentials or contact a running instance.
state_root='/synthetic-demo-state'
server_url() { printf 'https://10.0.2.2:8443'; }
local_server_url() { printf 'https://localhost:8443'; }
apps_enabled=true
app_is_enabled() { [[ "$apps_enabled" == true ]]; }
commands=()
internal_probe=''
public_probe=''
occ() { commands+=("$*"); }
compose() {
    [[ "$1" == exec && "$3" == nextcloud && "$4" == curl ]]
    internal_probe="${!#}"
}
curl() {
    [[ "$1" == --config && "$2" == "$state_root/curl.conf" ]]
    [[ " $* " != *' --insecure '* && " $* " != *' -k '* ]]
    public_probe="${!#}"
}

configure_office
code_path='/custom_apps/richdocumentscode/proxy.php?req='
[[ "$internal_probe" == "http://localhost$code_path/hosting/discovery" ]]
[[ "$public_probe" == "https://localhost:8443$code_path/hosting/discovery" ]]
[[ "${commands[0]}" == 'config:system:set default_certificates_bundle_path --value=/etc/ssl/certs/ca-certificates.crt' ]]
[[ "${commands[1]}" == "richdocuments:activate-config --wopi-url=http://localhost$code_path --callback-url=http://localhost" ]]
[[ "${commands[2]}" == "config:app:set richdocuments public_wopi_url --value=https://10.0.2.2:8443$code_path" ]]
[[ "${#commands[@]}" == 3 ]]

apps_enabled=false
commands=()
internal_probe=''
public_probe=''
configure_office
[[ "${#commands[@]}" == 0 && -z "$internal_probe" && -z "$public_probe" ]]
printf 'Demo Office origin checks passed.\n'
