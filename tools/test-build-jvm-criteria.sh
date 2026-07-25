#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_root"

criteria_file="gradle/gradle-daemon-jvm.properties"

if [[ "$(< .java-version)" != "21" ]]; then
    printf '.java-version must select Java 21.\n' >&2
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
grep -Fq 'org.gradle.toolchains.foojay-resolver-convention' settings.gradle.kts || {
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
    grep -Fq "toolchainUrl.${platform}=" "$criteria_file" || {
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
