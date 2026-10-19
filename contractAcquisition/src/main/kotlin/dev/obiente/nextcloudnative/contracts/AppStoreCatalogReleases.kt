package dev.obiente.nextcloudnative.contracts

import java.net.URI
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Which required catalog value made an entry for the requested app unusable. */
internal enum class MalformedCatalogField {
    /**
     * The catalog document itself is not a readable JSON array, or it is a non-empty array in
     * which no element is an app object with a readable `id`.
     */
    Document,

    /**
     * The app was not found, and some top-level element is not an object or has a missing or
     * wrong-typed `id`, so it may have been the requested app.
     */
    App,

    /**
     * More than one top-level record has the requested app `id` and they differ, so no single record
     * can be trusted. Structurally equal duplicates ([jsonEquivalent]) are read once.
     */
    DuplicateApp,
    Releases,
    Entry,
    Version,

    /** `isNightly` is present but not a JSON boolean, so the release channel is unknown. */
    Nightly,
    Certificate,
    Download,
    Signature,
    SignatureDigest,
}

/**
 * A catalog entry for the requested app that parsing dropped. A null version could not be read. A
 * required value of the wrong JSON type, for example a number or object where a string belongs, is
 * malformed exactly like a missing one; it is never coerced.
 */
internal data class MalformedCatalogEntry(val version: String?, val field: MalformedCatalogField)

/** Where a catalog read came from. */
internal enum class CatalogSource {
    Downloaded,
    Cached,

    /** An explicit retry was refused by the refresh window and no cached copy was available. */
    RefreshRefused,
}

/** Usable releases of one app in one catalog, plus the entries that were present but malformed. */
internal data class CatalogReleases(
    val releases: List<AppStoreRelease>,
    val malformed: List<MalformedCatalogEntry> = emptyList(),
    val source: CatalogSource = CatalogSource.Downloaded,
) {
    /** False when the whole document was unreadable; such a document must not be cached. */
    val documentReadable: Boolean get() = malformed.none { entry -> entry.field == MalformedCatalogField.Document }
}

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
    ).releases.filter { release ->
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
): CatalogReleases {
    // A truncated, empty, non-JSON, or non-array document is malformed source data, not a client fault.
    val apps = try {
        JSONArray(catalogJson)
    } catch (_: JSONException) {
        return CatalogReleases(emptyList(), listOf(MalformedCatalogEntry(null, MalformedCatalogField.Document)))
    }
    var unidentified = 0
    val records = mutableListOf<JSONObject>()
    for (index in 0 until apps.length()) {
        val candidate = apps.optJSONObject(index)?.takeIf { candidate -> candidate.stringValue("id") != null }
        if (candidate == null) unidentified += 1 else if (candidate.stringValue("id") == appId) records += candidate
    }
    // Differing records for this app make every one of them ambiguous; identical copies are read once.
    if (records.drop(1).any { duplicate -> !jsonEquivalent(duplicate, records.first()) }) {
        return CatalogReleases(emptyList(), listOf(MalformedCatalogEntry(null, MalformedCatalogField.DuplicateApp)))
    }
    val app = records.firstOrNull()
    if (app == null) {
        // An element whose identity cannot be read may have been the requested app, so absence is unproven.
        val field = when (unidentified) {
            0 -> return CatalogReleases(emptyList())
            apps.length() -> MalformedCatalogField.Document
            else -> MalformedCatalogField.App
        }
        return CatalogReleases(emptyList(), listOf(MalformedCatalogEntry(null, field)))
    }
    val releases = app.optJSONArray("releases")
        ?: return CatalogReleases(emptyList(), listOf(MalformedCatalogEntry(null, MalformedCatalogField.Releases)))
    val certificate = app.stringValue("certificate")?.takeIf(String::isNotBlank)
    val malformed = mutableListOf<MalformedCatalogEntry>()
    val parsed = (0 until releases.length())
        .asSequence()
        .mapNotNull { index ->
            releases.optJSONObject(index).also { release ->
                if (release == null) malformed += MalformedCatalogEntry(null, MalformedCatalogField.Entry)
            }
        }
        .filter { release -> !release.booleanValue("isNightly", absent = false, wrongType = false) }
        .mapNotNull { release ->
            val version = release.stringValue("version")?.takeIf(String::isNotBlank)
            val nightlyKnown = release.opt("isNightly").let { value -> value == null || value is Boolean }
            // A download value that is not a valid HTTP URL is malformed catalog data, like a missing one.
            val download = release.stringValue("download")?.takeIf { value -> value.toHttpUrlOrNull() != null }
            val signature = release.stringValue("signature")?.takeIf(String::isNotBlank)
            val digest = release.stringValue("signatureDigest")?.takeIf(String::isNotBlank)
            if (version == null || !nightlyKnown || certificate == null || download == null || signature == null ||
                digest == null
            ) {
                malformed += MalformedCatalogEntry(
                    version = version,
                    field = when {
                        version == null -> MalformedCatalogField.Version
                        !nightlyKnown -> MalformedCatalogField.Nightly
                        certificate == null -> MalformedCatalogField.Certificate
                        download == null -> MalformedCatalogField.Download
                        signature == null -> MalformedCatalogField.Signature
                        else -> MalformedCatalogField.SignatureDigest
                    },
                )
                return@mapNotNull null
            }
            AppStoreRelease(
                appId = appId,
                version = version,
                downloadUrl = download,
                signature = signature,
                signatureDigest = digest,
                certificatePem = certificate,
                catalogUrl = catalogUrl,
                platformVersionSpec = release.stringValue("platformVersionSpec")?.takeIf(String::isNotBlank),
                githubSources = deriveGitHubSources(
                    app = app,
                    downloadUrl = download,
                    releaseVersion = version,
                    requireOfficialReleaseHost = requireOfficialGitHubHost,
                ),
            )
        }
        .toList()
    return CatalogReleases(parsed, malformed.toList())
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
    val compatible = releases.filter { release -> isCompatibleReleaseVersion(release.version, installedVersion) }
    return if (installedVersion == null) {
        compatible.sortedWith { left, right -> compareSemanticVersions(right.version, left.version) }
    } else {
        compatible.sortedWith { left, right -> compareCompatibility(left.version, right.version, installedVersion) }
    }
}

/** A non-prerelease with the installed major and minor version, or any non-prerelease when unknown. */
internal fun isCompatibleReleaseVersion(version: String, installedVersion: String?): Boolean {
    if (version.isRecognizedPrerelease()) return false
    if (installedVersion == null) return true
    val installed = parseVersion(installedVersion) ?: return false
    if (installed.numeric.size < 2 || installedVersion.isRecognizedPrerelease()) return false
    val candidate = parseVersion(version) ?: return false
    return candidate.numeric.size >= 2 &&
        candidate.numeric[0] == installed.numeric[0] &&
        candidate.numeric[1] == installed.numeric[1]
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
        app.stringValue("repository")?.let(::parseGitHubRepository)?.let(::add)
        app.stringValue("website")?.let(::parseGitHubRepository)?.let(::add)
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
