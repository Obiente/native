#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_root"

criteria_file="gradle/gradle-daemon-jvm.properties"
java_version_file=".java-version"
settings_file="settings.gradle.kts"

if [[ ! -f "$java_version_file" ]]; then
    printf '%s is required to select Java 21 for compatible version managers.\n' "$java_version_file" >&2
    exit 1
fi

java_version="$(sed -e 's/\r$//' -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' "$java_version_file")"
if [[ "$java_version" != "21" ]]; then
    printf '%s must select Java 21.\n' "$java_version_file" >&2
    exit 1
fi

if [[ ! -f "$criteria_file" ]]; then
    printf '%s is required so Gradle does not inherit an arbitrary system JDK.\n' "$criteria_file" >&2
    exit 1
fi

grep -Fxq 'toolchainVersion=21' "$criteria_file" || {
    printf 'The Gradle daemon must use Java 21.\n' >&2
    exit 1
}
grep -Fxq 'toolchainVendor=ADOPTIUM' "$criteria_file" || {
    printf 'The Gradle daemon must use the shared Adoptium toolchain criteria.\n' >&2
    exit 1
}

# Ignore line and block comments, then require a standalone Kotlin plugin
# application rather than accepting the plugin ID in prose or another string.
awk '
    BEGIN {
        in_block_comment = 0
        in_triple_string = 0
        triple_quote = sprintf("%c%c%c", 34, 34, 34)
        found = 0
    }
    {
        remaining = $0
        code = ""
        while (length(remaining) > 0) {
            if (in_triple_string) {
                string_end = index(remaining, triple_quote)
                if (string_end == 0) {
                    remaining = ""
                    break
                }
                remaining = substr(remaining, string_end + 3)
                in_triple_string = 0
                continue
            }
            if (in_block_comment) {
                block_end = index(remaining, "*/")
                if (block_end == 0) {
                    remaining = ""
                    break
                }
                remaining = substr(remaining, block_end + 2)
                in_block_comment = 0
                continue
            }

            block_start = index(remaining, "/*")
            line_comment = index(remaining, "//")
            string_start = index(remaining, triple_quote)
            if (line_comment > 0 && (block_start == 0 || line_comment < block_start) && (string_start == 0 || line_comment < string_start)) {
                code = code substr(remaining, 1, line_comment - 1)
                remaining = ""
                break
            }
            if (string_start > 0 && (block_start == 0 || string_start < block_start)) {
                code = code substr(remaining, 1, string_start - 1)
                remaining = substr(remaining, string_start + 3)
                in_triple_string = 1
                continue
            }
            if (block_start > 0) {
                code = code substr(remaining, 1, block_start - 1)
                remaining = substr(remaining, block_start + 2)
                in_block_comment = 1
                continue
            }

            code = code remaining
            remaining = ""
        }

        if (code ~ /^[[:space:]]*id[[:space:]]*\([[:space:]]*"org[.]gradle[.]toolchains[.]foojay-resolver-convention"[[:space:]]*\)([[:space:]]+version[[:space:]]+"[^"]+")?[[:space:]]*$/) {
            found = 1
        }
    }
    END {
        exit found ? 0 : 1
    }
' "$settings_file" || {
    printf 'A toolchain resolver is required to provision Java 21 for contributors and CI.\n' >&2
    exit 1
}

for platform in \
    LINUX.AARCH64 \
    LINUX.X86_64 \
    MAC_OS.AARCH64 \
    MAC_OS.X86_64 \
    WINDOWS.AARCH64 \
    WINDOWS.X86_64
do
    grep -Eq "^toolchainUrl[.]${platform}=https\\\\://[^[:space:]]+$" "$criteria_file" || {
        printf 'The Gradle daemon criteria is missing %s provisioning.\n' "$platform" >&2
        exit 1
    }
done

for helper in \
    tools/deploy-local.sh \
    tools/capture-marketing-screenshots.sh
do
    if grep -Eq 'JDK 21 (is required|was not found)|java_major.*21|java_version.*21' "$helper"; then
        printf '%s must let the Gradle daemon criteria select Java 21 instead of rejecting another supported launcher JDK.\n' "$helper" >&2
        exit 1
    fi
done

printf 'Build JVM criteria checks passed.\n'
