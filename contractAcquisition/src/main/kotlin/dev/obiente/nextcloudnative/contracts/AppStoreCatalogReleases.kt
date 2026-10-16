package dev.obiente.nextcloudnative.contracts

import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

internal fun selectRelease(
    catalogJson: String,
    request: ContractAcquisitionRequest,
    catalogUrl: String,
    requireOfficialGitHubHost: Boolean = true,
): AppStoreRelease? {
    val candidates = parseReleases(
        catalogJson = catalogJson,
        appId = request.appId,
        catalogUrl = catalogUrl,
        requireOfficialGitHubHost = requireOfficialGitHubHost,
    ).filter { release ->
        request.installedAppVersion != null || !release.version.isRecognizedPrerelease()
    }.filter { release ->
        request.installedAppVersion == null || release.version == request.installedAppVersion
    }
    return candidates.maxWithOrNull { left, right ->
        compareSemanticVersions(left.version, right.version)
    }
}

internal fun parseReleases(
    catalogJson: String,
    appId: String,
    catalogUrl: String,
    requireOfficialGitHubHost: Boolean,
): List<AppStoreRelease> {
    val apps = JSONArray(catalogJson)
    val app = (0 until apps.length())
        .asSequence()
        .mapNotNull(apps::optJSONObject)
        .firstOrNull { candidate -> candidate.optString("id") == appId }
        ?: return emptyList()
    val certificate = app.optString("certificate").takeIf(String::isNotBlank) ?: return emptyList()
    val releases = app.optJSONArray("releases") ?: return emptyList()
    return (0 until releases.length())
        .asSequence()
        .mapNotNull(releases::optJSONObject)
        .filter { release -> !release.optBoolean("isNightly", false) }
        .mapNotNull { release ->
            val version = release.optString("version").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val download = release.optString("download").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val signature = release.optString("signature").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val digest = release.optString("signatureDigest").takeIf(String::isNotBlank) ?: return@mapNotNull null
            AppStoreRelease(
                appId = appId,
                version = version,
                downloadUrl = download,
                signature = signature,
                signatureDigest = digest,
                certificatePem = certificate,
                catalogUrl = catalogUrl,
                platformVersionSpec = release.optString("platformVersionSpec").takeIf(String::isNotBlank),
                githubSources = deriveGitHubSources(
                    app = app,
                    downloadUrl = download,
                    releaseVersion = version,
                    requireOfficialReleaseHost = requireOfficialGitHubHost,
                ),
            )
        }
        .toList()
}

private data class ParsedVersion(
    val numeric: List<Int>,
    val suffix: String,
)

internal fun normalizeServerVersion(version: String): String {
    val parsed = parseVersion(version)
    requireValidRequest(parsed != null && parsed.numeric.size >= 3) {
        "The server version must contain at least three numeric components."
    }
    return requireNotNull(parsed).numeric.take(3).joinToString(".")
}

private fun parseVersion(version: String): ParsedVersion? {
    if (version.isBlank() || !version.first().isDigit()) return null
    val numeric = mutableListOf<Int>()
    var index = 0
    while (index < version.length) {
        val start = index
        while (index < version.length && version[index].isDigit()) index += 1
        if (start == index) break
        numeric += version.substring(start, index).toIntOrNull() ?: return null
        if (index >= version.length || version[index] != '.') break
        if (index + 1 >= version.length || !version[index + 1].isDigit()) break
        index += 1
    }
    if (numeric.isEmpty()) return null
    return ParsedVersion(numeric, version.substring(index))
}

private fun String.isRecognizedPrerelease(): Boolean {
    val suffix = parseVersion(this)?.suffix?.lowercase().orEmpty()
    return PRE_RELEASE_MARKERS.any(suffix::contains)
}

internal fun compatibleReleaseCandidates(
    releases: List<AppStoreRelease>,
    installedVersion: String?,
): List<AppStoreRelease> {
    if (installedVersion == null) {
        return releases
            .filterNot { release -> release.version.isRecognizedPrerelease() }
            .sortedWith { left, right -> compareSemanticVersions(right.version, left.version) }
    }
    val installed = parseVersion(installedVersion) ?: return emptyList()
    if (installed.numeric.size < 2 || installedVersion.isRecognizedPrerelease()) return emptyList()
    return releases
        .filterNot { release -> release.version.isRecognizedPrerelease() }
        .filter { release ->
            val candidate = parseVersion(release.version)
            candidate != null && candidate.numeric.size >= 2 &&
                candidate.numeric[0] == installed.numeric[0] &&
                candidate.numeric[1] == installed.numeric[1]
        }
        .sortedWith { left, right -> compareCompatibility(left.version, right.version, installedVersion) }
}

private fun compareCompatibility(left: String, right: String, installed: String): Int {
    val installedVersion = parseVersion(installed) ?: return left.compareTo(right)
    val leftVersion = parseVersion(left) ?: return 1
    val rightVersion = parseVersion(right) ?: return -1
    val leftPatch = leftVersion.numeric.getOrElse(2) { 0 }
    val rightPatch = rightVersion.numeric.getOrElse(2) { 0 }
    val installedPatch = installedVersion.numeric.getOrElse(2) { 0 }
    val leftIsPrior = leftPatch <= installedPatch
    val rightIsPrior = rightPatch <= installedPatch
    if (leftIsPrior != rightIsPrior) return if (leftIsPrior) -1 else 1
    return if (leftIsPrior) rightPatch.compareTo(leftPatch) else leftPatch.compareTo(rightPatch)
}

private data class GitHubRepository(val owner: String, val repository: String)

private data class GitHubReleaseLocation(
    val repository: GitHubRepository,
    val tag: String,
)

private fun deriveGitHubSources(
    app: JSONObject,
    downloadUrl: String,
    releaseVersion: String,
    requireOfficialReleaseHost: Boolean,
): List<GitHubSourceReference> {
    val release = parseGitHubReleaseLocation(downloadUrl, requireOfficialReleaseHost) ?: return emptyList()
    val repositories = buildList {
        parseGitHubRepository(app.optString("repository"))?.let(::add)
        parseGitHubRepository(app.optString("website"))?.let(::add)
        add(release.repository)
    }.distinct()
    val conventionalTags = setOf(releaseVersion, "v$releaseVersion")
    val tags = (if (release.tag in conventionalTags) {
        listOf(release.tag)
    } else {
        listOf(release.tag, "v$releaseVersion", releaseVersion)
    })
        .distinct()
        .filter { tag -> tag.matches(GITHUB_TAG_PATTERN) }
    return repositories.flatMap { repository ->
        tags.map { tag -> GitHubSourceReference(repository.owner, repository.repository, tag) }
    }
}

private fun parseGitHubRepository(url: String): GitHubRepository? {
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    if (!uri.scheme.equals("https", ignoreCase = true) || !uri.host.equals("github.com", ignoreCase = true)) {
        return null
    }
    if (
        uri.userInfo != null ||
        uri.port != -1 ||
        uri.query != null ||
        uri.fragment?.takeIf(String::isNotBlank)?.equals("readme", ignoreCase = true) == false
    ) {
        return null
    }
    val parts = uri.rawPath.trim('/').split('/').filter(String::isNotBlank)
    if (parts.size != 2) return null
    val owner = parts[0]
    val repository = parts[1].removeSuffix(".git")
    if (!owner.matches(GITHUB_REPOSITORY_COMPONENT) || !repository.matches(GITHUB_REPOSITORY_COMPONENT)) return null
    return GitHubRepository(owner, repository)
}

private fun parseGitHubReleaseLocation(
    url: String,
    requireOfficialHost: Boolean,
): GitHubReleaseLocation? {
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    if (
        requireOfficialHost &&
        (!uri.scheme.equals("https", ignoreCase = true) ||
            !uri.host.equals("github.com", ignoreCase = true) ||
            uri.port != -1)
    ) {
        return null
    }
    if (uri.userInfo != null || uri.query != null || uri.fragment != null) return null
    val parts = uri.rawPath.trim('/').split('/').filter(String::isNotBlank)
    if (parts.size < 6 || parts[2] != "releases" || parts[3] != "download") return null
    val owner = parts[0]
    val repository = parts[1].removeSuffix(".git")
    val tag = parts[4]
    if (
        !owner.matches(GITHUB_REPOSITORY_COMPONENT) ||
        !repository.matches(GITHUB_REPOSITORY_COMPONENT) ||
        !tag.matches(GITHUB_TAG_PATTERN)
    ) {
        return null
    }
    return GitHubReleaseLocation(GitHubRepository(owner, repository), tag)
}

internal fun compareSemanticVersions(left: String, right: String): Int {
    val leftVersion = parseVersion(left) ?: return left.compareTo(right)
    val rightVersion = parseVersion(right) ?: return left.compareTo(right)
    repeat(maxOf(leftVersion.numeric.size, rightVersion.numeric.size)) { index ->
        val difference = (leftVersion.numeric.getOrNull(index) ?: 0)
            .compareTo(rightVersion.numeric.getOrNull(index) ?: 0)
        if (difference != 0) return difference
    }
    if (leftVersion.suffix.isBlank() != rightVersion.suffix.isBlank()) {
        return if (leftVersion.suffix.isBlank()) 1 else -1
    }
    return leftVersion.suffix.compareTo(rightVersion.suffix)
}

private val GITHUB_REPOSITORY_COMPONENT = Regex("[A-Za-z0-9_.-]+")
private val GITHUB_TAG_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._+-]*")
private val PRE_RELEASE_MARKERS = listOf("alpha", "beta", "rc", "dev", "nightly")
