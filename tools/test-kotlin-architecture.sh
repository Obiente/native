#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
checker="$project_root/tools/check-kotlin-architecture.sh"
fixture="$(mktemp -d)"
trap 'rm -rf -- "$fixture"' EXIT

mkdir -p \
    "$fixture/ui/src/commonMain/kotlin/example" \
    "$fixture/ui/src/androidMain/kotlin/example" \
    "$fixture/ui/src/desktopMain/kotlin/example" \
    "$fixture/androidApp/src/main/kotlin/example" \
    "$fixture/contractAcquisition/src/main/kotlin/example" \
    "$fixture/tools"
: > "$fixture/tools/kotlin-file-size-baseline.txt"

expect_failure() {
    local label="$1"
    shift
    if "$@" >/dev/null 2>&1; then
        printf 'Expected Kotlin architecture check to reject %s.\n' "$label" >&2
        exit 1
    fi
}

for ((line = 1; line <= 801; line += 1)); do
    printf '// line %s\n' "$line"
done > "$fixture/ui/src/commonMain/kotlin/example/Large.kt"
expect_failure 'a new oversized production file' "$checker" "$fixture"

printf '%s|801\n' 'ui/src/commonMain/kotlin/example/Large.kt' > \
    "$fixture/tools/kotlin-file-size-baseline.txt"
"$checker" "$fixture" >/dev/null
printf '// growth\n' >> "$fixture/ui/src/commonMain/kotlin/example/Large.kt"
expect_failure 'growth above an existing baseline' "$checker" "$fixture"
rm "$fixture/ui/src/commonMain/kotlin/example/Large.kt"
: > "$fixture/tools/kotlin-file-size-baseline.txt"

printf '%s\n' 'package example' > "$fixture/ui/src/androidMain/kotlin/example/Same.android.kt"
cp "$fixture/ui/src/androidMain/kotlin/example/Same.android.kt" \
    "$fixture/ui/src/desktopMain/kotlin/example/Same.desktop.kt"
expect_failure 'byte-identical Android and desktop files' "$checker" "$fixture"
rm \
    "$fixture/ui/src/androidMain/kotlin/example/Same.android.kt" \
    "$fixture/ui/src/desktopMain/kotlin/example/Same.desktop.kt"

cat > "$fixture/ui/src/commonMain/kotlin/example/PlatformImport.kt" <<'EOF'
package example

import java.io.File
EOF
expect_failure 'a platform import in commonMain' "$checker" "$fixture"
rm "$fixture/ui/src/commonMain/kotlin/example/PlatformImport.kt"

printf '%s\n' 'package example' > "$fixture/ui/src/commonMain/kotlin/example/Utils.kt"
expect_failure 'a generic Kotlin container name' "$checker" "$fixture"
rm "$fixture/ui/src/commonMain/kotlin/example/Utils.kt"

cat > "$fixture/androidApp/src/main/kotlin/example/EmptyCatch.kt" <<'EOF'
package example

fun discardFailure(block: () -> Unit) {
    try {
        block()
    } catch (failure: Exception) {
    }
}
EOF
expect_failure 'an empty broad catch' "$checker" "$fixture"
rm "$fixture/androidApp/src/main/kotlin/example/EmptyCatch.kt"

"$checker" "$fixture" >/dev/null
printf 'Kotlin architecture checker tests passed.\n'
