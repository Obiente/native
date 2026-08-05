#!/bin/sh
set -eu

indexnow_origin="https://nc-native.obiente.dev"
indexnow_endpoint="https://api.indexnow.org/indexnow"
indexnow_state_directory="/var/cache/nginx/indexnow"
indexnow_private_directory="/opt/indexnow"

if [ "${INDEXNOW_PRODUCTION:-0}" != "1" ]; then
    echo "IndexNow disabled: INDEXNOW_PRODUCTION is not enabled for this deployment."
    exit 0
fi

(
    fingerprint="$(tr -d '\r\n' <"${indexnow_private_directory}/fingerprint")"
    mkdir -p "${indexnow_state_directory}"
    marker="${indexnow_state_directory}/${fingerprint}.submitted"
    if [ -f "${marker}" ]; then
        echo "IndexNow already received deployment ${fingerprint}."
        exit 0
    fi

    attempt=1
    while [ "${attempt}" -le 60 ]; do
        remote_fingerprint="$({
            curl --fail --silent --show-error \
                --connect-timeout 5 \
                --max-time 10 \
                --header 'Cache-Control: no-cache' \
                "${indexnow_origin}/indexnow-deployment.txt?build=${fingerprint}"
        } 2>/dev/null || true)"
        remote_fingerprint="$(printf '%s' "${remote_fingerprint}" | tr -d '\r\n')"
        if [ "${remote_fingerprint}" = "${fingerprint}" ]; then
            break
        fi
        sleep 5
        attempt=$((attempt + 1))
    done

    if [ "${attempt}" -gt 60 ]; then
        echo "IndexNow skipped deployment ${fingerprint}: the canonical site did not expose its fingerprint." >&2
        exit 0
    fi

    response_file="$(mktemp)"
    trap 'rm -f "${response_file}"' EXIT
    status="$({
        curl --silent --show-error \
            --connect-timeout 10 \
            --max-time 30 \
            --retry 2 \
            --retry-all-errors \
            --output "${response_file}" \
            --write-out '%{http_code}' \
            --header 'Content-Type: application/json; charset=utf-8' \
            --data-binary "@${indexnow_private_directory}/payload.json" \
            "${indexnow_endpoint}"
    } || true)"

    case "${status}" in
        200|202)
            : >"${marker}"
            echo "IndexNow accepted deployment ${fingerprint} with HTTP ${status}."
            ;;
        *)
            echo "IndexNow rejected deployment ${fingerprint} with HTTP ${status:-000}." >&2
            if [ -s "${response_file}" ]; then
                sed -n '1,8p' "${response_file}" >&2
            fi
            ;;
    esac
) &
