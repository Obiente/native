package dev.obiente.nextcloudnative.app

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal fun requireWindowsCloudCallbackPath(root: Path, normalizedPath: String, identityPath: String) {
    // Windows can report the same directory through a long path in CFAPI while java.io.tmpdir or a
    // configured root still contains an 8.3 component such as RUNNER~1. Compare real filesystem
    // paths so the containment check does not reject that legitimate alias.
    val absoluteRoot = root.windowsCloudRealPath()
    val callbackTarget = Path.of(normalizedPath).windowsCloudRealPath()
    require(callbackTarget.startsWith(absoluteRoot)) { "The Cloud Files callback escaped its sync root." }
    val relative = if (callbackTarget == absoluteRoot) {
        ""
    } else {
        absoluteRoot.relativize(callbackTarget).joinToString("/") { it.toString() }.windowsCloudPath()
    }
    require(relative == identityPath) { "The Cloud Files callback path does not match its identity." }
}

private fun Path.windowsCloudRealPath(): Path {
    val absolute = toAbsolutePath().normalize()
    var existing = absolute
    while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
        existing = requireNotNull(existing.parent) { "The Cloud Files callback path has no existing ancestor." }
    }
    val realAncestor = existing.toRealPath(LinkOption.NOFOLLOW_LINKS)
    return if (existing == absolute) realAncestor else realAncestor.resolve(existing.relativize(absolute)).normalize()
}

internal fun windowsWildcardMatches(pattern: String, name: String): Boolean {
    if (pattern == "*" || pattern == "*.*") return true
    var patternIndex = 0
    var nameIndex = 0
    var starIndex = -1
    var retryNameIndex = -1
    while (nameIndex < name.length) {
        if (
            patternIndex < pattern.length &&
            (pattern[patternIndex] == '?' || pattern[patternIndex].equals(name[nameIndex], true))
        ) {
            patternIndex += 1
            nameIndex += 1
        } else if (patternIndex < pattern.length && pattern[patternIndex] == '*') {
            starIndex = patternIndex++
            retryNameIndex = nameIndex
        } else if (starIndex >= 0) {
            patternIndex = starIndex + 1
            nameIndex = ++retryNameIndex
        } else {
            return false
        }
    }
    while (patternIndex < pattern.length && pattern[patternIndex] == '*') patternIndex += 1
    return patternIndex == pattern.length
}

internal fun String.windowsCloudPath(): String {
    val normalized = trim('/', '\\').replace('\\', '/')
    if (normalized.isEmpty()) return ""
    require(normalized.split('/').none { it.isEmpty() || it == "." || it == ".." })
    require('\u0000' !in normalized)
    return normalized
}
