#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
temporary="$(mktemp -d)"
trap 'rm -r -- "$temporary"' EXIT
REAL_JQ="$(command -v jq)"
export REAL_JQ
mkdir -p "$temporary/bin"
# Simulate native Windows text output on any host, including Unix jq 1.6.
cat > "$temporary/bin/jq" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
"$REAL_JQ" "$@" | sed 's/\r$//' | sed 's/$/\r/'
EOF
chmod +x "$temporary/bin/jq"
export PATH="$temporary/bin:$PATH"
mapfile -t text_lines < <(jq -nr '2')
[[ "${text_lines[0]}" == $'2\r' ]]
repository=Obiente/native
source "$project_root/tools/release-repository.sh"
mapfile -t normalized_lines < <(release_jq -nr '2')
[[ "${normalized_lines[0]}" == '2' ]]
if release_jq -en 'error("synthetic")' >/dev/null 2>&1; then
    printf 'jq errors must retain a failing exit status.\n' >&2
    exit 1
fi
bash "$project_root/tools/test-download-channel-promotion.sh"
bash "$project_root/tools/test-desktop-update-manifest.sh"
bash "$project_root/tools/test-update-channel-promotion.sh"
printf 'Release jq output portability fixtures passed.\n'
