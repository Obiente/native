#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
demo_root="$project_root/integration/nextcloud-demo"
compose_file="$demo_root/compose.yml"
environment_file="$demo_root/.env"
state_root="$demo_root/.state"
report_root="$demo_root/reports"
app_manifest="$demo_root/apps/representative.tsv"
default_host="10.0.2.2"
default_http_port="18080"
default_port="8443"

usage() {
    cat <<'EOF'
Usage: tools/nextcloud-demo.sh <command> [arguments]

Commands:
  init [host]                     Create private credentials and a local TLS CA.
  up                              Start the pinned Nextcloud stack and wait for it.
  provision                       Create the test account, enable the representative
                                  app suite, create an app password, and seed fixtures.
  install-suite                   Install and enable the representative app suite.
  install-app <app-id>            Install and enable one compatible App Store app.
  stage-catalog [limit]           Download every compatible App Store package but
                                  keep packages disabled; write a result report.
  seed                            Recreate only the bounded synthetic DAV fixtures.
  status                          Show container, server, and enabled-app status.
  credentials-path                Print the path to the private session import JSON.
  android-ca <instance>           Copy the demo CA to an isolated emulator for manual
                                  installation through Android security settings.
  android-session <instance> [server-url]
                                  Import the demo account into a debuggable build in
                                  enforced read-only mode. An alternate URL must use
                                  the configured port and a name in the demo certificate.
  android-write-scope <instance> <path-prefix>
                                  Authorize one exact app API subtree for writes.
  android-clear-write-scope <instance>
                                  Remove the emulator's scoped write authorization.
  logs [service]                  Follow raw private container logs. Review and redact
                                  them before sharing any excerpt.
  down                            Stop containers while preserving all volumes.
  reset --confirm                 Delete this demo stack's containers, volumes,
                                  credentials, and certificates for reinitialization.
  validate                        Validate scripts, manifests, and Compose expansion.

The instance is disposable, but ordinary down/up commands preserve its data.
Only reset --confirm removes volumes and private initialization state. Reports
and reusable App Store metadata stay under ignored integration/nextcloud-demo paths.
EOF
}

fail() {
    printf 'error: %s\n' "$*" >&2
    exit 1
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || fail "$1 is required"
}

require_initialized() {
    [[ -f "$environment_file" ]] || fail "run tools/nextcloud-demo.sh init first"
    [[ -f "$state_root/tls/ca.crt" ]] || fail "the demo CA is missing; run init again after preserving any needed state"
    [[ -f "$state_root/tls/server.crt" ]] || fail "the demo server certificate is missing"
}

validate_host() {
    local host="$1"
    [[ "$host" =~ ^[A-Za-z0-9][A-Za-z0-9.-]{0,252}$ ]] ||
        fail "host must be an IPv4 address or DNS name without a port"
    [[ "$host" != *..* && "$host" != *. && "$host" != *- ]] ||
        fail "host is malformed"
}

read_environment_value() {
    local key="$1"
    local line
    line="$(grep -m1 -E "^${key}=" "$environment_file" || true)"
    [[ -n "$line" ]] || fail "$key is missing from $environment_file"
    printf '%s\n' "${line#*=}"
}

ensure_ca_bundle() {
    local source_bundle=""
    local candidate
    for candidate in \
        /etc/ssl/certs/ca-certificates.crt \
        /etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem \
        /etc/pki/tls/certs/ca-bundle.crt; do
        if [[ -r "$candidate" ]]; then
            source_bundle="$candidate"
            break
        fi
    done
    [[ -n "$source_bundle" ]] || fail "no readable system CA bundle was found"
    local temporary_bundle
    temporary_bundle="$(mktemp "$state_root/tls/.ca-bundle.XXXXXX")"
    cp "$source_bundle" "$temporary_bundle"
    printf '\n' >>"$temporary_bundle"
    cat "$state_root/tls/ca.crt" >>"$temporary_bundle"
    chmod 644 "$temporary_bundle"
    mv "$temporary_bundle" "$state_root/tls/ca-bundle.crt"
}

compose() {
    require_command podman
    ensure_ca_bundle
    (
        cd "$demo_root"
        export POSTGRES_PASSWORD="$(read_environment_value NC_DEMO_DATABASE_PASSWORD)"
        export NEXTCLOUD_ADMIN_PASSWORD="$(read_environment_value NC_DEMO_ADMIN_PASSWORD)"
        podman compose --env-file "$environment_file" -f "$compose_file" "$@" </dev/null
    )
}

compose_down_for_reset() {
    require_command podman
    (
        cd "$demo_root"
        export POSTGRES_PASSWORD="$(grep -m1 '^NC_DEMO_DATABASE_PASSWORD=' "$environment_file" || true)"
        export POSTGRES_PASSWORD="${POSTGRES_PASSWORD#*=}"
        export POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-reset-only-placeholder}"
        export NEXTCLOUD_ADMIN_PASSWORD="$(grep -m1 '^NC_DEMO_ADMIN_PASSWORD=' "$environment_file" || true)"
        export NEXTCLOUD_ADMIN_PASSWORD="${NEXTCLOUD_ADMIN_PASSWORD#*=}"
        export NEXTCLOUD_ADMIN_PASSWORD="${NEXTCLOUD_ADMIN_PASSWORD:-reset-only-placeholder}"
        podman compose --env-file "$environment_file" -f "$compose_file" \
            down --volumes --remove-orphans </dev/null
    )
}

compose_with_app_timeout() {
    local timeout_seconds="${NC_DEMO_APP_OPERATION_TIMEOUT_SECONDS:-600}"
    [[ "$timeout_seconds" =~ ^[1-9][0-9]{0,3}$ ]] ||
        fail "NC_DEMO_APP_OPERATION_TIMEOUT_SECONDS must be between 1 and 9999"
    require_command timeout
    require_command podman
    ensure_ca_bundle
    (
        cd "$demo_root"
        export POSTGRES_PASSWORD="$(read_environment_value NC_DEMO_DATABASE_PASSWORD)"
        export NEXTCLOUD_ADMIN_PASSWORD="$(read_environment_value NC_DEMO_ADMIN_PASSWORD)"
        timeout --foreground --kill-after=30 "$timeout_seconds" \
            podman compose --env-file "$environment_file" -f "$compose_file" "$@" </dev/null
    )
}

occ() {
    compose exec -T --user www-data nextcloud php occ "$@"
}

wait_for_server() {
    local attempt
    for attempt in $(seq 1 120); do
        if occ status --output=json 2>/dev/null |
            jq -e '.installed == true and .needsDbUpgrade == false' >/dev/null; then
            return
        fi
        sleep 2
    done
    compose ps >&2 || true
    fail "Nextcloud did not become ready within four minutes"
}

initialize() {
    local host="${1:-$default_host}"
    validate_host "$host"
    require_command openssl
    require_command jq
    umask 077
    mkdir -p "$state_root/tls" "$report_root"
    if [[ -e "$environment_file" ]]; then
        fail "$environment_file already exists; preserve it or run reset --confirm before reinitializing"
    fi

    local database_password
    local admin_password
    local test_password
    database_password="$(openssl rand -hex 32)"
    admin_password="$(openssl rand -hex 32)"
    test_password="$(openssl rand -hex 32)"
    local trusted_domains="localhost 127.0.0.1 10.0.2.2"
    local bind_address="127.0.0.1"
    if [[ "$host" != "localhost" && "$host" != "127.0.0.1" && "$host" != "10.0.2.2" ]]; then
        trusted_domains="$trusted_domains $host"
        bind_address="0.0.0.0"
    fi

    {
        printf 'NC_DEMO_HOST=%s\n' "$host"
        printf 'NC_DEMO_HTTP_PORT=%s\n' "$default_http_port"
        printf 'NC_DEMO_HTTPS_PORT=%s\n' "$default_port"
        printf 'NC_DEMO_BIND_ADDRESS=%s\n' "$bind_address"
        printf 'NC_DEMO_TRUSTED_DOMAINS=%s\n' "$trusted_domains"
        printf 'NC_DEMO_DATABASE_NAME=nextcloud\n'
        printf 'NC_DEMO_DATABASE_USER=nextcloud\n'
        printf 'NC_DEMO_DATABASE_PASSWORD=%s\n' "$database_password"
        printf 'NC_DEMO_ADMIN_USER=nc-native-admin\n'
        printf 'NC_DEMO_ADMIN_PASSWORD=%s\n' "$admin_password"
        printf 'NC_DEMO_TEST_USER=nc-native-e2e\n'
        printf 'NC_DEMO_TEST_PASSWORD=%s\n' "$test_password"
    } >"$environment_file"
    chmod 600 "$environment_file"

    local san_kind="DNS"
    if [[ "$host" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
        san_kind="IP"
    fi
    {
        printf '[req]\n'
        printf 'distinguished_name=subject\n'
        printf 'req_extensions=extensions\n'
        printf 'prompt=no\n'
        printf '[subject]\n'
        printf 'CN=NC Native demo server\n'
        printf '[extensions]\n'
        printf 'subjectAltName=@alternate_names\n'
        printf '[alternate_names]\n'
        printf 'DNS.1=localhost\n'
        printf 'IP.1=127.0.0.1\n'
        printf 'IP.2=10.0.2.2\n'
        if [[ "$host" != "localhost" && "$host" != "127.0.0.1" && "$host" != "10.0.2.2" ]]; then
            if [[ "$san_kind" == "IP" ]]; then
                printf 'IP.3=%s\n' "$host"
            else
                printf 'DNS.2=%s\n' "$host"
            fi
        fi
    } >"$state_root/tls/server.cnf"

    openssl req -x509 -newkey rsa:3072 -sha256 -nodes -days 3650 \
        -subj '/CN=NC Native Demo Development CA' \
        -keyout "$state_root/tls/ca.key" \
        -out "$state_root/tls/ca.crt" >/dev/null 2>&1
    openssl req -newkey rsa:3072 -sha256 -nodes \
        -config "$state_root/tls/server.cnf" \
        -keyout "$state_root/tls/server.key" \
        -out "$state_root/tls/server.csr" >/dev/null 2>&1
    openssl x509 -req -sha256 -days 825 \
        -in "$state_root/tls/server.csr" \
        -CA "$state_root/tls/ca.crt" \
        -CAkey "$state_root/tls/ca.key" \
        -CAcreateserial \
        -extensions extensions \
        -extfile "$state_root/tls/server.cnf" \
        -out "$state_root/tls/server.crt" >/dev/null 2>&1
    chmod 600 "$state_root/tls/ca.key" "$state_root/tls/server.key"
    chmod 644 "$state_root/tls/ca.crt" "$state_root/tls/server.crt"
    ensure_ca_bundle
    printf 'Initialized private demo configuration for https://%s:%s.\n' "$host" "$default_port"
}

start_stack() {
    require_initialized
    require_command jq
    require_command curl
    compose up -d
    wait_for_server
    compose restart gateway >/dev/null
    local readiness_url
    readiness_url="$(local_server_url)"
    local attempt
    for attempt in $(seq 1 30); do
        if curl --silent --show-error --fail --cacert "$state_root/tls/ca.crt" \
            --max-time 5 --output /dev/null "$readiness_url/status.php" 2>/dev/null; then
            break
        fi
        [[ "$attempt" -lt 30 ]] || fail "the demo HTTPS gateway did not become ready"
        sleep 1
    done
    printf 'Nextcloud demo is ready at %s.\n' "$(server_url)"
}

server_url() {
    local host
    local port
    host="$(read_environment_value NC_DEMO_HOST)"
    port="$(read_environment_value NC_DEMO_HTTPS_PORT)"
    printf 'https://%s:%s\n' "$host" "$port"
}

local_server_url() {
    printf 'https://localhost:%s\n' "$(read_environment_value NC_DEMO_HTTPS_PORT)"
}

validated_android_server_url() {
    local candidate="${1:-$(server_url)}"
    local host
    local port
    if [[ "$candidate" =~ ^https://([A-Za-z0-9.-]+):([0-9]{1,5})$ ]]; then
        host="${BASH_REMATCH[1]}"
        port="${BASH_REMATCH[2]}"
    else
        fail "Android session URL must be an HTTPS origin without a path, query, or fragment"
    fi
    ((10#$port >= 1 && 10#$port <= 65535)) ||
        fail "Android session URL port is invalid"
    [[ "$port" == "$(read_environment_value NC_DEMO_HTTPS_PORT)" ]] ||
        fail "Android session URL must use the configured demo HTTPS port"

    require_command openssl
    if [[ "$host" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
        openssl x509 -in "$state_root/tls/server.crt" -noout -checkip "$host" >/dev/null 2>&1 ||
            fail "Android session URL IP is not present in the demo server certificate"
    else
        openssl x509 -in "$state_root/tls/server.crt" -noout -checkhost "$host" >/dev/null 2>&1 ||
            fail "Android session URL host is not present in the demo server certificate"
    fi
    printf '%s\n' "$candidate"
}

test_user() {
    read_environment_value NC_DEMO_TEST_USER
}

ensure_test_account() {
    local user
    local password
    user="$(test_user)"
    password="$(read_environment_value NC_DEMO_TEST_PASSWORD)"
    if ! occ user:info "$user" >/dev/null 2>&1; then
        compose exec -T --user www-data -e "OC_PASS=$password" nextcloud \
            php occ user:add --password-from-env --display-name="NC Native E2E" "$user" >/dev/null
    fi
    occ user:setting "$user" core lang en >/dev/null
    occ user:setting "$user" core locale en_US >/dev/null
}

create_app_password() {
    local credentials_file="$state_root/test-session.json"
    local curl_config="$state_root/curl.conf"
    if [[ -s "$credentials_file" && -s "$curl_config" ]]; then
        return
    fi
    local user
    local password
    local token_output
    local token
    user="$(test_user)"
    password="$(read_environment_value NC_DEMO_TEST_PASSWORD)"
    token_output="$(compose exec -T --user www-data -e "NC_PASS=$password" nextcloud \
        php occ user:auth-tokens:add --password-from-env --name='NC Native E2E' "$user")"
    token="$(
        printf '%s\n' "$token_output" |
            awk '
                capture { print; exit }
                /^[[:space:]]*app password:/ {
                    sub(/^[[:space:]]*app password:[[:space:]]*/, "")
                    if (length > 0) {
                        print
                        exit
                    }
                    capture = 1
                }
            '
    )"
    [[ "$token" =~ ^[A-Za-z0-9_-]{16,128}$ ]] || fail "Nextcloud returned an unexpected app-password shape"

    jq -n \
        --arg serverUrl "$(server_url)" \
        --arg loginName "$user" \
        --arg appPassword "$token" \
        '{serverUrl: $serverUrl, loginName: $loginName, appPassword: $appPassword}' \
        >"$credentials_file"
    {
        printf 'silent\n'
        printf 'show-error\n'
        printf 'fail-with-body\n'
        printf 'cacert = "%s"\n' "$state_root/tls/ca.crt"
        printf 'user = "%s:%s"\n' "$user" "$token"
    } >"$curl_config"
    chmod 600 "$credentials_file" "$curl_config"
}

app_is_installed() {
    local app_id="$1"
    occ app:list --output=json |
        jq -e --arg app "$app_id" '(.enabled[$app] // .disabled[$app]) != null' >/dev/null
}

app_is_enabled() {
    local app_id="$1"
    occ app:list --output=json |
        jq -e --arg app "$app_id" '.enabled[$app] != null' >/dev/null
}

install_app() {
    local app_id="${1:-}"
    [[ "$app_id" =~ ^[a-z0-9][a-z0-9_]{0,63}$ ]] || fail "invalid app ID: $app_id"
    if app_is_enabled "$app_id"; then
        printf 'enabled %s\n' "$app_id"
        return
    fi
    if ! app_is_installed "$app_id"; then
        compose_with_app_timeout exec -T --user www-data nextcloud \
            php occ app:install "$app_id" >/dev/null
    fi
    compose_with_app_timeout exec -T --user www-data nextcloud \
        php occ app:enable "$app_id" >/dev/null
    printf 'enabled %s\n' "$app_id"
}

install_suite() {
    wait_for_server
    mkdir -p "$report_root"
    local report="$report_root/representative-install.tsv"
    printf 'app_id\tsurface\trequirement\tresult\n' >"$report"
    local app_id
    local surface
    local requirement
    local purpose
    local failures=0
    while read -r app_id surface requirement purpose; do
        [[ -n "${app_id:-}" && "$app_id" != \#* ]] || continue
        if install_app "$app_id" >/dev/null 2>&1; then
            printf '%s\t%s\t%s\tenabled\n' "$app_id" "$surface" "$requirement" >>"$report"
            printf 'enabled %s\n' "$app_id"
        else
            printf '%s\t%s\t%s\tfailed\n' "$app_id" "$surface" "$requirement" >>"$report"
            printf 'could not enable %s (%s)\n' "$app_id" "$requirement" >&2
            if [[ "$requirement" == "required" ]]; then
                failures=$((failures + 1))
            fi
        fi
    done <"$app_manifest"
    ((failures == 0)) || fail "$failures required representative apps could not be enabled; inspect $report"
    printf 'Representative app report: %s\n' "$report"
}

stage_catalog() {
    local limit="${1:-0}"
    [[ "$limit" =~ ^[0-9]+$ ]] || fail "catalog limit must be zero or a positive integer"
    wait_for_server
    require_command curl
    require_command jq
    mkdir -p "$report_root"
    local version
    local catalog
    local report
    version="$(occ status --output=json | jq -r '.versionstring')"
    catalog="$state_root/appstore-$version.json"
    report="$report_root/catalog-stage-$version.tsv"
    curl --fail --silent --show-error --max-time 60 \
        "https://apps.nextcloud.com/api/v1/platform/$version/apps.json" >"$catalog"
    jq -e 'type == "array" and all(.[]; .id | test("^[a-z0-9][a-z0-9_]{0,63}$"))' "$catalog" >/dev/null ||
        fail "the App Store returned an invalid catalog"
    printf 'app_id\tresult\n' >"$report"
    local count=0
    local app_id
    while IFS= read -r app_id; do
        if ((limit > 0 && count >= limit)); then
            break
        fi
        count=$((count + 1))
        if app_is_installed "$app_id"; then
            printf '%s\talready-installed\n' "$app_id" >>"$report"
        elif compose_with_app_timeout exec -T --user www-data nextcloud \
            php occ app:install --keep-disabled "$app_id" >/dev/null 2>&1; then
            printf '%s\tstaged-disabled\n' "$app_id" >>"$report"
        else
            printf '%s\tfailed\n' "$app_id" >>"$report"
        fi
        printf '[%s] %s\n' "$count" "$app_id"
    done < <(jq -r '.[].id' "$catalog" | sort -u)
    printf 'Catalog staging report: %s\n' "$report"
}

seed_fixtures() {
    wait_for_server
    ensure_test_account
    create_app_password
    require_command curl
    local user
    local base_url
    local curl_config
    user="$(test_user)"
    base_url="$(local_server_url)"
    curl_config="$state_root/curl.conf"

    occ dav:create-addressbook "$user" contacts >/dev/null 2>&1 || true
    occ dav:create-calendar "$user" personal >/dev/null 2>&1 || true
    occ dav:create-calendar "$user" tasks >/dev/null 2>&1 || true

    local folder_status
    folder_status="$(curl --config "$curl_config" --output /dev/null --write-out '%{http_code}' \
        --request MKCOL "$base_url/remote.php/dav/files/$user/NC%20Native%20E2E" 2>/dev/null || true)"
    [[ "$folder_status" == "201" || "$folder_status" == "405" ]] ||
        fail "could not create the bounded WebDAV fixture directory (HTTP $folder_status)"
    curl --config "$curl_config" --header 'Content-Type: text/markdown; charset=utf-8' \
        --upload-file "$demo_root/fixtures/README.md" \
        "$base_url/remote.php/dav/files/$user/NC%20Native%20E2E/README.md" >/dev/null
    curl --config "$curl_config" --header 'Content-Type: text/vcard; charset=utf-8' \
        --upload-file "$demo_root/fixtures/contact.vcf" \
        "$base_url/remote.php/dav/addressbooks/users/$user/contacts/nc-native-e2e-contact.vcf" >/dev/null
    curl --config "$curl_config" --header 'Content-Type: text/calendar; charset=utf-8' \
        --upload-file "$demo_root/fixtures/calendar.ics" \
        "$base_url/remote.php/dav/calendars/$user/personal/nc-native-e2e.ics" >/dev/null
    curl --config "$curl_config" --header 'Content-Type: text/calendar; charset=utf-8' \
        --upload-file "$demo_root/fixtures/task.ics" \
        "$base_url/remote.php/dav/calendars/$user/tasks/nc-native-e2e-task.ics" >/dev/null
    printf 'Seeded bounded DAV fixtures for %s.\n' "$user"
}

provision_bridge() {
    compose exec -T --user root nextcloud sh -c '
        set -eu
        mkdir -p /var/www/html/custom_apps/obiente_native_bridge
        rsync -a --delete /opt/obiente_native_bridge/ /var/www/html/custom_apps/obiente_native_bridge/
        chown -R www-data:www-data /var/www/html/custom_apps/obiente_native_bridge
    '
    occ app:enable obiente_native_bridge >/dev/null
}

configure_office() {
    if ! app_is_enabled richdocuments || ! app_is_enabled richdocumentscode; then
        return
    fi
    local base_url
    local public_code_url
    base_url="$(server_url)"
    public_code_url="$base_url/custom_apps/richdocumentscode/proxy.php?req="
    occ config:system:set default_certificates_bundle_path \
        --value='/etc/ssl/certs/ca-certificates.crt' >/dev/null
    compose exec -T nextcloud curl --silent --show-error --fail --max-time 120 \
        --output /dev/null "$public_code_url/hosting/discovery"
    occ richdocuments:activate-config \
        --wopi-url="$public_code_url" \
        --callback-url='http://localhost' >/dev/null
    occ config:app:set richdocuments public_wopi_url --value="$public_code_url" >/dev/null
    curl --config "$state_root/curl.conf" --output /dev/null --max-time 120 \
        "$public_code_url/hosting/discovery"
}

configure_proxy_protocol() {
    local gateway_ip
    gateway_ip="$(compose exec -T nextcloud getent hosts gateway | awk 'NR == 1 { print $1 }')"
    [[ "$gateway_ip" =~ ^[0-9a-fA-F:.]+$ ]] ||
        fail "could not resolve the demo gateway address"
    occ config:system:set trusted_proxies 0 --value="$gateway_ip" >/dev/null
    occ config:system:delete overwriteprotocol >/dev/null 2>&1 || true
}

provision() {
    wait_for_server
    configure_proxy_protocol
    ensure_test_account
    install_suite
    provision_bridge
    create_app_password
    configure_office
    seed_fixtures
    printf 'Provisioned the representative real-server compatibility instance.\n'
}

show_status() {
    require_initialized
    compose ps
    if compose ps --status running --services | grep -Fxq nextcloud; then
        occ status
        printf '\nEnabled non-shipped apps:\n'
        occ app:list --shipped=false
    fi
}

adb_for_instance() {
    local instance="$1"
    local serial
    serial="$($project_root/tools/android-emulator.sh serial "$instance")"
    local sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/android-sdk}}"
    local adb="$sdk_root/platform-tools/adb"
    [[ -x "$adb" ]] || fail "adb is missing from $sdk_root/platform-tools"
    printf '%s\t%s\n' "$adb" "$serial"
}

android_ca() {
    local instance="${1:-}"
    [[ -n "$instance" ]] || fail "android-ca requires an emulator instance name"
    require_initialized
    local adb
    local serial
    IFS=$'\t' read -r adb serial < <(adb_for_instance "$instance")
    "$adb" -s "$serial" push "$state_root/tls/ca.crt" /sdcard/Download/nc-native-demo-ca.crt >/dev/null
    "$adb" -s "$serial" shell am start -a android.settings.SECURITY_SETTINGS >/dev/null
    printf '%s\n' \
        "Copied nc-native-demo-ca.crt to Downloads on $serial." \
        "Install it as a CA certificate in Android security settings, then remove it when this demo environment is retired."
}

android_import() {
    local instance="${1:-}"
    local requested_server_url="${2:-}"
    [[ -n "$instance" ]] || fail "android-session requires an emulator instance name"
    [[ "$#" -le 2 ]] || fail "android-session accepts only an instance and optional server URL"
    require_initialized
    create_app_password
    require_command jq
    local package_name="${NC_DEMO_ANDROID_PACKAGE:-dev.obiente.nextcloudnative}"
    local adb
    local serial
    local import_server_url
    import_server_url="$(validated_android_server_url "$requested_server_url")"
    IFS=$'\t' read -r adb serial < <(adb_for_instance "$instance")
    "$adb" -s "$serial" shell run-as "$package_name" true >/dev/null 2>&1 ||
        fail "install a debuggable $package_name build on $serial first"
    "$adb" -s "$serial" shell run-as "$package_name" mkdir -p files
    jq --arg serverUrl "$import_server_url" '.serverUrl = $serverUrl' "$state_root/test-session.json" |
        "$adb" -s "$serial" shell \
            "run-as '$package_name' sh -c 'umask 077; cat > files/nc-native-test-session.json'"
    restart_android_app "$adb" "$serial" "$package_name"
    printf 'Imported the disposable demo account on %s in enforced read-only mode.\n' "$serial"
}

android_write_scope() {
    local instance="${1:-}"
    local api_prefix="${2:-}"
    [[ -n "$instance" && -n "$api_prefix" ]] ||
        fail "android-write-scope requires an instance and exact app API or DAV collection prefix"
    require_command jq
    local package_name="${NC_DEMO_ANDROID_PACKAGE:-dev.obiente.nextcloudnative}"
    local adb
    local serial
    IFS=$'\t' read -r adb serial < <(adb_for_instance "$instance")
    local import_file="$state_root/write-scope.json"
    jq -n --arg apiPathPrefix "$api_prefix" '{apiPathPrefix: $apiPathPrefix}' >"$import_file"
    chmod 600 "$import_file"
    "$adb" -s "$serial" shell "run-as '$package_name' sh -c 'umask 077; cat > files/nc-native-test-write-scope.json'" \
        <"$import_file"
    restart_android_app "$adb" "$serial" "$package_name"
    printf 'Requested exact write scope %s on %s.\n' "$api_prefix" "$serial"
}

android_clear_write_scope() {
    local instance="${1:-}"
    [[ -n "$instance" ]] || fail "android-clear-write-scope requires an emulator instance"
    require_command jq
    local package_name="${NC_DEMO_ANDROID_PACKAGE:-dev.obiente.nextcloudnative}"
    local adb
    local serial
    IFS=$'\t' read -r adb serial < <(adb_for_instance "$instance")
    local import_file="$state_root/write-scope-clear.json"
    jq -n '{clear: true}' >"$import_file"
    chmod 600 "$import_file"
    "$adb" -s "$serial" shell "run-as '$package_name' sh -c 'umask 077; cat > files/nc-native-test-write-scope.json'" \
        <"$import_file"
    restart_android_app "$adb" "$serial" "$package_name"
    printf 'Cleared the scoped write authorization on %s.\n' "$serial"
}

restart_android_app() {
    local adb="$1"
    local serial="$2"
    local package_name="$3"
    "$adb" -s "$serial" shell am force-stop "$package_name"
    "$adb" -s "$serial" shell monkey -p "$package_name" -c android.intent.category.LAUNCHER 1 >/dev/null
}

validate() {
    require_command jq
    bash -n "$project_root/tools/nextcloud-demo.sh"
    awk 'NF && $1 !~ /^#/ { print $1 }' "$app_manifest" |
        while IFS= read -r app_id; do
            [[ "$app_id" =~ ^[a-z0-9][a-z0-9_]{0,63}$ ]] || fail "invalid manifest app ID: $app_id"
        done
    local duplicate
    duplicate="$(awk 'NF && $1 !~ /^#/ { print $1 }' "$app_manifest" | sort | uniq -d)"
    [[ -z "$duplicate" ]] || fail "duplicate app ID in representative manifest: $duplicate"
    require_command podman
    (
        cd "$demo_root"
        export POSTGRES_PASSWORD="$(grep -m1 '^NC_DEMO_DATABASE_PASSWORD=' .env.example | cut -d= -f2-)"
        export NEXTCLOUD_ADMIN_PASSWORD="$(grep -m1 '^NC_DEMO_ADMIN_PASSWORD=' .env.example | cut -d= -f2-)"
        podman compose --env-file .env.example -f compose.yml config --quiet
    )
    printf 'Nextcloud demo configuration is valid.\n'
}

reset_stack() {
    [[ "${1:-}" == "--confirm" ]] ||
        fail "reset deletes this demo stack's volumes; rerun with reset --confirm"
    if [[ -f "$environment_file" ]]; then
        compose_down_for_reset
    fi
    rm -f -- \
        "$environment_file" \
        "$state_root/test-session.json" \
        "$state_root/curl.conf" \
        "$state_root/write-scope.json" \
        "$state_root/write-scope-clear.json"
    rm -rf -- "$state_root/tls"
    printf 'Removed any nc-native-demo containers and volumes.\n'
    printf 'Removed private bootstrap credentials, certificates, and cached app-password session files.\n'
    printf 'Run tools/nextcloud-demo.sh init [host] to initialize a fresh demo.\n'
}

command="${1:-help}"
shift || true
case "$command" in
    help|-h|--help) usage ;;
    init) initialize "$@" ;;
    up) start_stack "$@" ;;
    provision) provision "$@" ;;
    install-suite) install_suite "$@" ;;
    install-app) install_app "$@" ;;
    stage-catalog) stage_catalog "$@" ;;
    seed) seed_fixtures "$@" ;;
    status) show_status "$@" ;;
    credentials-path)
        require_initialized
        create_app_password
        printf '%s\n' "$state_root/test-session.json"
        ;;
    android-ca) android_ca "$@" ;;
    android-session) android_import "$@" ;;
    android-write-scope) android_write_scope "$@" ;;
    android-clear-write-scope) android_clear_write_scope "$@" ;;
    logs)
        require_initialized
        compose logs --follow --tail=200 "$@"
        ;;
    down)
        require_initialized
        compose down
        printf 'Stopped the demo stack and preserved its volumes.\n'
        ;;
    reset) reset_stack "$@" ;;
    validate) validate "$@" ;;
    *)
        usage >&2
        fail "unknown command: $command"
        ;;
esac
