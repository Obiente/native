#!/usr/bin/env bash
set -euo pipefail

project_root="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
baseline_file="${2:-$project_root/tools/kotlin-file-size-baseline.txt}"
production_limit=800
test_limit=1200
failed=false

declare -A size_baseline=()
if [[ -f "$baseline_file" ]]; then
    while IFS='|' read -r path limit; do
        [[ -n "$path" ]] || continue
        if [[ ! "$limit" =~ ^[0-9]+$ ]]; then
            printf 'Invalid Kotlin size baseline for %s: %s\n' "$path" "$limit" >&2
            exit 1
        fi
        size_baseline["$path"]="$limit"
    done < "$baseline_file"
fi

is_test_source() {
    [[ "$1" == *'/test/'* || "$1" == *'Test/'* || "$1" == *'Test.kt' ]]
}

mapfile -d '' kotlin_files < <(
    find \
        "$project_root/ui/src" \
        "$project_root/androidApp/src" \
        "$project_root/contractAcquisition/src" \
        -type f -name '*.kt' -print0 2>/dev/null
)

for file in "${kotlin_files[@]}"; do
    relative_path="${file#"$project_root/"}"
    line_count="$(wc -l < "$file")"
    if is_test_source "$relative_path"; then
        default_limit="$test_limit"
    else
        default_limit="$production_limit"
    fi
    allowed="${size_baseline[$relative_path]:-$default_limit}"
    if (( line_count > allowed )); then
        printf '%s has %s lines; its limit is %s. Split by ownership instead of raising the baseline.\n' \
            "$relative_path" "$line_count" "$allowed" >&2
        failed=true
    fi
done

for path in "${!size_baseline[@]}"; do
    if [[ ! -f "$project_root/$path" ]]; then
        printf 'Remove stale Kotlin size baseline entry: %s\n' "$path" >&2
        failed=true
    fi
done

while IFS= read -r android_file; do
    relative_path="${android_file#"$project_root/ui/src/androidMain/"}"
    desktop_file="$project_root/ui/src/desktopMain/${relative_path%.android.kt}.desktop.kt"
    if [[ -f "$desktop_file" ]] && cmp -s "$android_file" "$desktop_file"; then
        printf 'Move byte-identical platform files to jvmMain: %s and %s\n' \
            "${android_file#"$project_root/"}" "${desktop_file#"$project_root/"}" >&2
        failed=true
    fi
done < <(find "$project_root/ui/src/androidMain" -type f -name '*.android.kt' 2>/dev/null | sort)

common_main="$project_root/ui/src/commonMain"
if [[ -d "$common_main" ]]; then
    if rg -n '^import (android|java|javax|javafx|sun)\.' "$common_main" >&2; then
        printf 'commonMain imports a platform API. Move the implementation to the owning source set.\n' >&2
        failed=true
    fi
    if rg -n '(GlobalScope|runBlocking\(|Thread\.sleep\()' "$common_main" >&2; then
        printf 'commonMain contains blocking or unstructured coroutine work.\n' >&2
        failed=true
    fi
fi

while IFS= read -r generic_file; do
    printf 'Rename generic Kotlin container by its actual owner: %s\n' \
        "${generic_file#"$project_root/"}" >&2
    failed=true
done < <(
    find "$project_root/ui/src" "$project_root/androidApp/src" "$project_root/contractAcquisition/src" \
        -type f \( -name 'Utils.kt' -o -name 'Helpers.kt' -o -name 'Common.kt' -o -name 'Misc.kt' \) \
        2>/dev/null | sort
)

if rg -n -U -g '*.kt' 'catch\s*\([^)]*:\s*(Throwable|Exception)\)\s*\{\s*\}' \
    "$project_root/ui/src" "$project_root/androidApp/src" "$project_root/contractAcquisition/src" >&2; then
    printf 'An empty broad catch discards a failure. Classify, report, or deliberately recover from it.\n' >&2
    failed=true
fi

if [[ "$failed" == true ]]; then
    exit 1
fi

printf 'Kotlin architecture checks passed.\n'
