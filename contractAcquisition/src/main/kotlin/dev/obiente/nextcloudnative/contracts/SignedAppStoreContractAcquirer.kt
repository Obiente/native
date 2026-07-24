package dev.obiente.nextcloudnative.contracts

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509CRL
import java.security.cert.X509Certificate
import java.net.URI
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.w3c.dom.Element
import org.xml.sax.EntityResolver
import org.xml.sax.SAXException
import javax.xml.parsers.DocumentBuilderFactory

data class ContractAcquisitionRequest(
    val appId: String,
    val serverVersion: String,
    val installedAppVersion: String? = null,
)

data class VerifiedOpenApiContract(
    val appId: String,
    /** The version actually installed on the server when it could be discovered. */
    val appVersion: String,
    /** The signed release/tag from which this contract was acquired. */
    val contractVersion: String,
    val specFile: String,
    val document: String,
    val catalogUrl: String,
    val packageUrl: String,
    val sourceUrl: String,
    val sourceKind: OpenApiContractSourceKind,
    val contractKind: VerifiedContractKind = VerifiedContractKind.OpenApi,
)

enum class VerifiedContractKind {
    OpenApi,
    VerifiedReadRoutes,
    OpenApiWithVerifiedReadRoutes,
}

enum class OpenApiContractSourceKind {
    SignedAppPackage,
    SignedCompatibleAppPackage,
    AppStoreLinkedExactGitHubTag,
    AppStoreLinkedCompatibleGitHubTag,
}

data class GitHubSourceReference(
    val owner: String,
    val repository: String,
    val tag: String,
)

data class AppStoreRelease(
    val appId: String,
    val version: String,
    val downloadUrl: String,
    val signature: String,
    val signatureDigest: String,
    val certificatePem: String,
    val catalogUrl: String,
    val platformVersionSpec: String? = null,
    val githubSources: List<GitHubSourceReference> = emptyList(),
)

interface AppPackageTrustVerifier {
    fun verifyAndExtract(release: AppStoreRelease, archive: ByteArray): VerifiedPackageContract
}

data class VerifiedPackageContract(
    val appId: String,
    val appVersion: String,
    val specFile: String,
    val document: String,
    val contractKind: VerifiedContractKind = VerifiedContractKind.OpenApi,
)

class SignedAppStoreContractAcquirer(
    private val httpClient: OkHttpClient = defaultContractHttpClient(),
    private val appStoreBaseUrl: String = OFFICIAL_APP_STORE_BASE_URL,
    private val requireHttps: Boolean = true,
    private val trustVerifier: AppPackageTrustVerifier = NextcloudAppPackageTrustVerifier(),
    private val catalogCache: AppStoreCatalogCache = MemoryAppStoreCatalogCache(),
    private val verifiedContractCache: VerifiedContractCache = MemoryVerifiedContractCache(),
    private val rawGitHubBaseUrl: String = OFFICIAL_RAW_GITHUB_BASE_URL,
    private val githubApiBaseUrl: String = OFFICIAL_GITHUB_API_BASE_URL,
    private val codeloadGitHubBaseUrl: String = OFFICIAL_CODELOAD_GITHUB_BASE_URL,
) {
    private val verifiedContracts = ConcurrentHashMap<ContractAcquisitionRequest, VerifiedOpenApiContract>()
    private val inFlightAcquisitions =
        ConcurrentHashMap<ContractAcquisitionRequest, CompletableFuture<VerifiedOpenApiContract?>>()

    init {
        if (requireHttps) {
            require(rawGitHubBaseUrl.trimEnd('/') == OFFICIAL_RAW_GITHUB_BASE_URL) {
                "Exact-tag contract files must come from raw.githubusercontent.com."
            }
            require(githubApiBaseUrl.trimEnd('/') == OFFICIAL_GITHUB_API_BASE_URL) {
                "Exact-tag repository discovery must use api.github.com."
            }
            require(codeloadGitHubBaseUrl.trimEnd('/') == OFFICIAL_CODELOAD_GITHUB_BASE_URL) {
                "Exact-tag source archives must come from codeload.github.com."
            }
        }
    }

    fun acquire(request: ContractAcquisitionRequest): VerifiedOpenApiContract? {
        val owner = CompletableFuture<VerifiedOpenApiContract?>()
        val existing = inFlightAcquisitions.putIfAbsent(request, owner)
        if (existing != null) {
            return try {
                existing.get()
            } catch (failure: ExecutionException) {
                throw failure.cause ?: failure
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                throw failure
            }
        }
        return try {
            acquireUncoalesced(request).also { acquired -> owner.complete(acquired) }
        } catch (failure: Throwable) {
            owner.completeExceptionally(failure)
            throw failure
        } finally {
            inFlightAcquisitions.remove(request, owner)
        }
    }

    private fun acquireUncoalesced(request: ContractAcquisitionRequest): VerifiedOpenApiContract? {
        require(request.appId.matches(APP_ID_PATTERN)) { "The app ID is invalid." }
        val coreServerVersion = normalizeServerVersion(request.serverVersion)
        request.installedAppVersion?.let { version ->
            require(version.matches(APP_VERSION_PATTERN)) { "The installed app version is invalid." }
        }
        verifiedContracts[request]?.let { return it }
        verifiedContractCache.load(request)?.let { cached ->
            verifiedContracts[request] = cached
            return cached
        }
        val compatibleCatalogUrl =
            "${appStoreBaseUrl.trimEnd('/')}/platform/$coreServerVersion/apps.json"
        val platformReleases = fetchCatalogReleases(compatibleCatalogUrl, request.appId)
        val exactPlatformRelease = request.installedAppVersion?.let { installed ->
            platformReleases.firstOrNull { release -> release.version == installed }
        }
        val exactRelease = if (request.installedAppVersion != null && exactPlatformRelease == null) {
            val allReleasesUrl = "${appStoreBaseUrl.trimEnd('/')}/apps.json"
            fetchCatalogReleases(allReleasesUrl, request.appId)
                .firstOrNull { release -> release.version == request.installedAppVersion }
        } else {
            exactPlatformRelease
        }
        val compatibleReleases = compatibleReleaseCandidates(
            releases = platformReleases,
            installedVersion = request.installedAppVersion,
        ).filterNot { candidate -> candidate.version == exactRelease?.version }
        val releases = listOfNotNull(exactRelease) + compatibleReleases
        if (releases.isEmpty()) return null

        val missingPackageContracts = mutableListOf<AppStoreRelease>()
        val verifiedRouteFallbacks = mutableListOf<Pair<AppStoreRelease, VerifiedPackageContract>>()
        for (release in releases) {
            requireSecureUrl(release.downloadUrl)
            val archiveResponse = execute(Request.Builder().url(release.downloadUrl).get().build())
            val archive = archiveResponse.use { response ->
                check(response.isSuccessful) {
                    "Downloading the signed ${request.appId} package failed (HTTP ${response.code})."
                }
                requireSecureResponse(response)
                response.readBodyLimited(MAX_ARCHIVE_BYTES)
            }
            try {
                val verified = trustVerifier.verifyAndExtract(release, archive)
                if (verified.contractKind == VerifiedContractKind.VerifiedReadRoutes) {
                    acquireGitHubTagContract(
                        release = release,
                        installedAppVersion = request.installedAppVersion,
                        exact = release.version == request.installedAppVersion,
                    )?.let { linkedContract ->
                        return cacheContract(
                            request,
                            linkedContract.mergeVerifiedPackageRoutes(verified),
                        )
                    }
                    verifiedRouteFallbacks += release to verified
                    continue
                }
                return cacheContract(
                    request,
                    VerifiedOpenApiContract(
                        appId = verified.appId,
                        appVersion = request.installedAppVersion ?: verified.appVersion,
                        contractVersion = verified.appVersion,
                        specFile = verified.specFile,
                        document = verified.document,
                        catalogUrl = release.catalogUrl,
                        packageUrl = release.downloadUrl,
                        sourceUrl = "${release.downloadUrl}#${verified.specFile}",
                        sourceKind = if (release.version == request.installedAppVersion) {
                            OpenApiContractSourceKind.SignedAppPackage
                        } else {
                            OpenApiContractSourceKind.SignedCompatibleAppPackage
                        },
                        contractKind = verified.contractKind,
                    ),
                )
            } catch (_: OpenApiContractMissingException) {
                missingPackageContracts += release
            }
        }

        for (release in missingPackageContracts) {
            val exact = release.version == request.installedAppVersion
            acquireGitHubTagContract(release, request.installedAppVersion, exact)?.let { contract ->
                return cacheContract(request, contract)
            }
        }
        val (release, verified) = verifiedRouteFallbacks.firstOrNull() ?: return null
        return cacheContract(
            request,
            VerifiedOpenApiContract(
                appId = verified.appId,
                appVersion = request.installedAppVersion ?: verified.appVersion,
                contractVersion = verified.appVersion,
                specFile = verified.specFile,
                document = verified.document,
                catalogUrl = release.catalogUrl,
                packageUrl = release.downloadUrl,
                sourceUrl = "${release.downloadUrl}#${verified.specFile}",
                sourceKind = if (release.version == request.installedAppVersion) {
                    OpenApiContractSourceKind.SignedAppPackage
                } else {
                    OpenApiContractSourceKind.SignedCompatibleAppPackage
                },
                contractKind = verified.contractKind,
            ),
        )
    }

    private fun cacheContract(
        request: ContractAcquisitionRequest,
        contract: VerifiedOpenApiContract,
    ): VerifiedOpenApiContract = contract.also {
        verifiedContracts[request] = it
        runCatching { verifiedContractCache.store(request, it) }
    }

    private fun acquireGitHubTagContract(
        release: AppStoreRelease,
        installedAppVersion: String?,
        exact: Boolean,
    ): VerifiedOpenApiContract? {
        release.githubSources.forEach sourceLoop@ { source ->
            val sourceRoot = "${rawGitHubBaseUrl.trimEnd('/')}/${source.owner}/${source.repository}/${source.tag}"
            val infoUrl = "$sourceRoot/appinfo/info.xml"
            val infoXml = fetchOptionalGitHubResource(
                url = infoUrl,
                expectedBaseUrl = rawGitHubBaseUrl,
                maximumBytes = MAX_APP_INFO_BYTES,
            )?.decodeToString() ?: return@sourceLoop
            val identity = runCatching { parseAppInfoIdentity(infoXml) }.getOrNull() ?: return@sourceLoop
            if (identity.first != release.appId || identity.second != release.version) return@sourceLoop

            val treeUrl = "${githubApiBaseUrl.trimEnd('/')}/repos/${source.owner}/${source.repository}" +
                "/git/trees/${source.tag}?recursive=1"
            val treeBytes = fetchOptionalGitHubResource(
                url = treeUrl,
                expectedBaseUrl = githubApiBaseUrl,
                maximumBytes = MAX_GITHUB_TREE_BYTES,
                accept = "application/vnd.github+json",
                recoverableStatusCodes = setOf(403, 429),
            )
            val contracts = treeBytes?.let { bytes ->
                val tree = runCatching { JSONObject(bytes.decodeToString()) }.getOrNull()
                val entries = tree?.takeUnless { it.optBoolean("truncated", true) }?.optJSONArray("tree")
                entries?.let { treeEntries ->
                    val candidates = (0 until treeEntries.length())
                        .asSequence()
                        .mapNotNull(treeEntries::optJSONObject)
                        .filter { entry -> entry.optString("type") == "blob" }
                        .map { entry -> entry.optString("path") }
                        .filter { path -> path.substringAfterLast('/').matches(OPEN_API_FILE_PATTERN) }
                        .distinct()
                        .sorted()
                        .take(MAX_OPEN_API_CANDIDATES)
                        .toList()
                    candidates.mapNotNull { fileName ->
                        val specUrl = "$sourceRoot/$fileName"
                        val contractBytes = fetchOptionalGitHubResource(
                            url = specUrl,
                            expectedBaseUrl = rawGitHubBaseUrl,
                            maximumBytes = MAX_SELECTED_FILE_BYTES,
                        ) ?: return@mapNotNull null
                        parseOpenApiCandidate(fileName, contractBytes)?.copy(sourceUrl = specUrl)
                    }
                }
            } ?: listOfNotNull(acquireCodeloadCandidate(source, release, sourceRoot))
            selectOpenApiCandidate(contracts)?.let { selected ->
                val bundled = bundleGitHubSchemaReferences(sourceRoot, selected)
                return VerifiedOpenApiContract(
                    appId = release.appId,
                    appVersion = installedAppVersion ?: release.version,
                    contractVersion = release.version,
                    specFile = bundled.path,
                    document = bundled.document,
                    catalogUrl = release.catalogUrl,
                    packageUrl = release.downloadUrl,
                    sourceUrl = bundled.sourceUrl,
                    sourceKind = if (exact) {
                        OpenApiContractSourceKind.AppStoreLinkedExactGitHubTag
                    } else {
                        OpenApiContractSourceKind.AppStoreLinkedCompatibleGitHubTag
                    },
                    contractKind = VerifiedContractKind.OpenApi,
                )
            }
        }
        return null
    }

    private fun acquireCodeloadCandidate(
        source: GitHubSourceReference,
        release: AppStoreRelease,
        sourceRoot: String,
    ): OpenApiCandidate? {
        val archiveUrl = "${codeloadGitHubBaseUrl.trimEnd('/')}/${source.owner}/${source.repository}" +
            "/tar.gz/refs/tags/${source.tag}"
        val archive = fetchOptionalGitHubResource(
            url = archiveUrl,
            expectedBaseUrl = codeloadGitHubBaseUrl,
            maximumBytes = MAX_ARCHIVE_BYTES,
            recoverableStatusCodes = setOf(403, 429),
        ) ?: return null
        val extracted = try {
            extractOpenApiContract(archive, release)
        } catch (_: OpenApiContractMissingException) {
            return null
        }
        return OpenApiCandidate(
            path = extracted.specFile,
            document = extracted.document,
            apiVersion = runCatching {
                JSONObject(extracted.document).optJSONObject("info")?.optString("version")
            }.getOrNull(),
            sourceUrl = "$sourceRoot/${extracted.specFile}",
        )
    }

    private fun bundleGitHubSchemaReferences(
        sourceRoot: String,
        candidate: OpenApiCandidate,
    ): OpenApiCandidate {
        val root = runCatching { JSONObject(candidate.document) }.getOrNull() ?: return candidate
        val documents = mutableMapOf(candidate.path to root)
        val activeReferences = mutableSetOf<String>()
        var fetchedDocuments = 0

        fun loadDocument(path: String): JSONObject? {
            documents[path]?.let { return it }
            if (fetchedDocuments >= MAX_EXTERNAL_SCHEMA_DOCUMENTS) return null
            val bytes = fetchOptionalGitHubResource(
                url = "$sourceRoot/$path",
                expectedBaseUrl = rawGitHubBaseUrl,
                maximumBytes = MAX_SELECTED_FILE_BYTES,
            ) ?: return null
            fetchedDocuments += 1
            return parseStructuredObject(path, bytes)?.also { documents[path] = it }
        }

        fun resolveValue(
            value: Any?,
            documentPath: String,
            documentRoot: JSONObject,
            preserveLocalReferences: Boolean,
            depth: Int,
        ): Any? {
            if (depth > MAX_EXTERNAL_REFERENCE_DEPTH) return value
            return when (value) {
                is JSONObject -> {
                    val reference = value.optString("${'$'}ref").takeIf(String::isNotBlank)
                    if (reference != null) {
                        val (filePart, fragment) = splitSchemaReference(reference) ?: return value
                        if (filePart.isEmpty() && preserveLocalReferences) return value
                        val targetPath = if (filePart.isEmpty()) {
                            documentPath
                        } else {
                            resolveRepositoryPath(documentPath, filePart) ?: return value
                        }
                        val targetRoot = if (targetPath == documentPath) documentRoot else loadDocument(targetPath)
                            ?: return value
                        val target = resolveJsonPointer(targetRoot, fragment) ?: return value
                        val identity = "$targetPath#$fragment"
                        if (!activeReferences.add(identity)) return JSONObject()
                        val resolved = resolveValue(
                            value = target,
                            documentPath = targetPath,
                            documentRoot = targetRoot,
                            preserveLocalReferences = false,
                            depth = depth + 1,
                        )
                        activeReferences.remove(identity)
                        if (resolved is JSONObject) {
                            JSONObject(resolved.toString()).also { merged ->
                                value.keys().asSequence().filter { key -> key != "${'$'}ref" }.forEach { key ->
                                    merged.put(
                                        key,
                                        resolveValue(
                                            value.get(key),
                                            documentPath,
                                            documentRoot,
                                            preserveLocalReferences,
                                            depth + 1,
                                        ),
                                    )
                                }
                            }
                        } else {
                            resolved
                        }
                    } else {
                        JSONObject().also { mapped ->
                            value.keys().asSequence().forEach { key ->
                                mapped.put(
                                    key,
                                    resolveValue(
                                        value.get(key),
                                        documentPath,
                                        documentRoot,
                                        preserveLocalReferences,
                                        depth + 1,
                                    ),
                                )
                            }
                        }
                    }
                }
                is JSONArray -> JSONArray().also { mapped ->
                    repeat(value.length()) { index ->
                        mapped.put(
                            resolveValue(
                                value.get(index),
                                documentPath,
                                documentRoot,
                                preserveLocalReferences,
                                depth + 1,
                            ),
                        )
                    }
                }
                else -> value
            }
        }

        val bundled = resolveValue(root, candidate.path, root, true, 0) as? JSONObject ?: return candidate
        return candidate.copy(document = bundled.toString())
    }

    private fun fetchOptionalGitHubResource(
        url: String,
        expectedBaseUrl: String,
        maximumBytes: Long,
        accept: String = "application/octet-stream",
        recoverableStatusCodes: Set<Int> = emptySet(),
    ): ByteArray? {
        requireSecureUrl(url)
        val response = execute(Request.Builder().url(url).header("Accept", accept).get().build())
        return response.use { githubResponse ->
            if (githubResponse.code == 404 || githubResponse.code in recoverableStatusCodes) return@use null
            check(githubResponse.isSuccessful) {
                "Loading an exact-tag GitHub contract resource failed (HTTP ${githubResponse.code})."
            }
            requireSecureResponse(githubResponse)
            requireExpectedOrigin(githubResponse, expectedBaseUrl)
            githubResponse.readBodyLimited(maximumBytes)
        }
    }

    private fun fetchCatalogReleases(
        catalogUrl: String,
        appId: String,
    ): List<AppStoreRelease> {
        requireSecureUrl(catalogUrl)
        catalogCache.load(catalogUrl)?.let { cached ->
            return parseReleases(
                cached.decodeToString(),
                appId,
                catalogUrl,
                requireOfficialGitHubHost = requireHttps,
            )
        }
        val response = execute(Request.Builder().url(catalogUrl).get().build())
        val bytes = response.use { catalogResponse ->
            check(catalogResponse.isSuccessful) {
                "Loading the Nextcloud App Store catalog failed (HTTP ${catalogResponse.code})."
            }
            requireSecureResponse(catalogResponse)
            catalogResponse.readBodyLimited(MAX_CATALOG_BYTES)
        }
        catalogCache.store(catalogUrl, bytes)
        return parseReleases(
            bytes.decodeToString(),
            appId,
            catalogUrl,
            requireOfficialGitHubHost = requireHttps,
        )
    }

    private fun execute(request: Request): Response = httpClient.newCall(request).execute()

    private fun requireSecureUrl(url: String) {
        if (requireHttps) require(url.startsWith("https://")) { "Contract sources must use HTTPS." }
    }

    private fun requireSecureResponse(response: Response) {
        if (requireHttps) require(response.request.url.isHttps) { "A contract request was redirected away from HTTPS." }
    }

    private fun requireExpectedOrigin(response: Response, expectedBaseUrl: String) {
        val expected = URI(expectedBaseUrl)
        val actual = response.request.url
        check(
            actual.scheme.equals(expected.scheme, ignoreCase = true) &&
                actual.host.equals(expected.host, ignoreCase = true) &&
                actual.port == expected.effectivePort()
        ) { "A GitHub contract request was redirected to an unexpected origin." }
    }

    companion object {
        const val OFFICIAL_APP_STORE_BASE_URL = "https://apps.nextcloud.com/api/v1"
        const val OFFICIAL_RAW_GITHUB_BASE_URL = "https://raw.githubusercontent.com"
        const val OFFICIAL_GITHUB_API_BASE_URL = "https://api.github.com"
        const val OFFICIAL_CODELOAD_GITHUB_BASE_URL = "https://codeload.github.com"
        private const val MAX_CATALOG_BYTES = 32L * 1024L * 1024L
        private const val MAX_ARCHIVE_BYTES = 64L * 1024L * 1024L
        private const val MAX_APP_INFO_BYTES = 512L * 1024L
        private const val MAX_GITHUB_TREE_BYTES = 8L * 1024L * 1024L
        private val APP_ID_PATTERN = Regex("[A-Za-z0-9_.-]+")
        private val APP_VERSION_PATTERN = Regex("[0-9A-Za-z][0-9A-Za-z.+_-]*")
    }
}

private fun VerifiedOpenApiContract.mergeVerifiedPackageRoutes(
    routes: VerifiedPackageContract,
): VerifiedOpenApiContract {
    require(contractKind == VerifiedContractKind.OpenApi)
    require(appId == routes.appId)
    require(contractVersion == routes.appVersion)
    val merged = mergeOpenApiWithVerifiedReadRoutes(
        openApi = VerifiedPackageContract(
            appId = appId,
            appVersion = contractVersion,
            specFile = specFile,
            document = document,
            contractKind = contractKind,
        ),
        verifiedReadRoutes = routes,
    )
    return copy(
        specFile = merged.specFile,
        document = merged.document,
        contractKind = merged.contractKind,
    )
}

private fun defaultContractHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(90, TimeUnit.SECONDS)
    .callTimeout(120, TimeUnit.SECONDS)
    .build()

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

private fun parseReleases(
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

private fun normalizeServerVersion(version: String): String {
    val parsed = parseVersion(version)
    require(parsed != null && parsed.numeric.size >= 3) {
        "The server version must contain at least three numeric components."
    }
    return parsed.numeric.take(3).joinToString(".")
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

private fun compatibleReleaseCandidates(
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

private fun compareSemanticVersions(left: String, right: String): Int {
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

class NextcloudAppPackageTrustVerifier(
    private val trustResourceLoader: (String) -> ByteArray = ::loadBundledTrustResource,
) : AppPackageTrustVerifier {
    override fun verifyAndExtract(release: AppStoreRelease, archive: ByteArray): VerifiedPackageContract {
        require(release.signatureDigest.equals("sha512", ignoreCase = true)) {
            "The package uses an unsupported signature digest."
        }
        val factory = CertificateFactory.getInstance("X.509")
        val trustedCertificates = factory.generateCertificates(
            ByteArrayInputStream(trustResourceLoader(ROOT_CERTIFICATES_RESOURCE)),
        ).map { certificate -> certificate as X509Certificate }
        check(trustedCertificates.isNotEmpty()) { "The Nextcloud trust bundle is empty." }
        trustedCertificates.forEach(X509Certificate::checkValidity)
        val appCertificate = factory.generateCertificate(
            ByteArrayInputStream(release.certificatePem.encodeToByteArray()),
        ) as X509Certificate
        appCertificate.checkValidity()
        check(appCertificate.subjectCommonName() == release.appId) {
            "The package certificate does not belong to ${release.appId}."
        }
        check(appCertificate.sigAlgName.contains("RSA", ignoreCase = true)) {
            "The package certificate uses an unsupported key algorithm."
        }
        verifyCertificateChain(appCertificate, trustedCertificates)
        val crl = factory.generateCRL(
            ByteArrayInputStream(trustResourceLoader(ROOT_REVOCATION_LIST_RESOURCE)),
        ) as X509CRL
        val crlIssuer = trustedCertificates.firstOrNull { certificate ->
            certificate.subjectX500Principal == crl.issuerX500Principal
        } ?: error("The CRL issuer is missing from the Nextcloud trust bundle.")
        crl.verify(crlIssuer.publicKey)
        check(!crl.isRevoked(appCertificate)) { "The package certificate has been revoked." }
        val signature = Signature.getInstance("SHA512withRSA")
        signature.initVerify(appCertificate.publicKey)
        signature.update(archive)
        val signatureBytes = Base64.getMimeDecoder().decode(release.signature)
        check(signature.verify(signatureBytes)) { "The downloaded app package signature is invalid." }
        return extractOpenApiContract(archive, release)
    }
}

class OpenApiContractMissingException(message: String) : IllegalStateException(message)

private fun verifyCertificateChain(
    leaf: X509Certificate,
    trustedCertificates: List<X509Certificate>,
) {
    var current = leaf
    val visited = mutableSetOf<String>()
    repeat(trustedCertificates.size + 1) {
        val identity = current.subjectX500Principal.name
        check(visited.add(identity)) { "The package certificate chain contains a cycle." }
        val issuer = trustedCertificates.firstOrNull { candidate ->
            candidate.subjectX500Principal == current.issuerX500Principal
        } ?: error("The package certificate was not issued by Nextcloud's trusted authority.")
        current.verify(issuer.publicKey)
        if (issuer.subjectX500Principal == issuer.issuerX500Principal) {
            issuer.verify(issuer.publicKey)
            return
        }
        current = issuer
    }
    error("The package certificate chain is too deep.")
}

private fun X509Certificate.subjectCommonName(): String? {
    val principal = subjectX500Principal.getName("RFC2253")
    return Regex("(?:^|,)CN=([^,]+)").find(principal)?.groupValues?.get(1)
}

private fun extractOpenApiContract(
    archive: ByteArray,
    release: AppStoreRelease,
): VerifiedPackageContract {
    val files = readSelectedTarFiles(archive)
    val info = files.entries.firstOrNull { (name, _) -> name.endsWith("/appinfo/info.xml") }
        ?: error("The signed app package does not contain appinfo/info.xml.")
    val root = info.key.substringBefore('/')
    check(root.isNotBlank() && files.keys.all { name -> name.substringBefore('/') == root }) {
        "The signed app package must contain one top-level app folder."
    }
    val (infoId, infoVersion) = parseAppInfoIdentity(info.value.decodeToString())
    check(infoId == release.appId) { "The signed package contains the wrong app ID." }
    check(infoVersion == release.version) { "The signed package version does not match the App Store release." }
    val candidates = files.entries
        .mapNotNull { (path, bytes) ->
            val relativePath = path.removePrefix("$root/")
            if (
                relativePath == path ||
                !relativePath.substringAfterLast('/').matches(OPEN_API_FILE_PATTERN)
            ) {
                null
            } else {
                parseOpenApiCandidate(relativePath, bytes)
            }
        }
        .take(MAX_OPEN_API_CANDIDATES)
    val selected = selectOpenApiCandidate(candidates)?.let { candidate ->
        VerifiedPackageContract(release.appId, release.version, candidate.path, candidate.document)
    }
    val verifiedReadRoutes = synthesizeReadOnlyRouteContract(release.appId, release.version, files)
    if (selected != null) {
        return verifiedReadRoutes?.let { routes ->
            mergeOpenApiWithVerifiedReadRoutes(selected, routes)
        } ?: selected
    }
    return verifiedReadRoutes
        ?: throw OpenApiContractMissingException(
            "The signed app package does not contain a usable OpenAPI 3 contract or safe static read routes.",
        )
}

private data class OpenApiCandidate(
    val path: String,
    val document: String,
    val apiVersion: String?,
    val sourceUrl: String = "",
)

private fun parseOpenApiCandidate(path: String, bytes: ByteArray): OpenApiCandidate? {
    val document = parseStructuredObject(path, bytes) ?: return null
    if (!document.optString("openapi").startsWith("3.") || document.optJSONObject("paths") == null) {
        return null
    }
    val normalized = document.toString()
    return OpenApiCandidate(
        path = path,
        document = normalized,
        apiVersion = document.optJSONObject("info")?.optString("version")?.takeIf(String::isNotBlank),
    )
}

private fun parseStructuredObject(path: String, bytes: ByteArray): JSONObject? = runCatching {
    if (path.endsWith(".json", ignoreCase = true)) {
        JSONObject(bytes.decodeToString())
    } else {
        parseYamlObject(bytes.decodeToString())
    }
}.getOrNull()

private fun splitSchemaReference(reference: String): Pair<String, String>? {
    if ('?' in reference || '\\' in reference || "://" in reference || reference.startsWith('/')) return null
    val file = reference.substringBefore('#')
    val fragment = reference.substringAfter('#', "")
    if (file.isNotEmpty() && !file.lowercase().let { it.endsWith(".json") || it.endsWith(".yaml") || it.endsWith(".yml") }) {
        return null
    }
    if (fragment.isNotEmpty() && !fragment.startsWith('/')) return null
    return file to fragment
}

private fun resolveRepositoryPath(currentPath: String, referencedPath: String): String? {
    if ('%' in referencedPath) return null
    val segments = currentPath.substringBeforeLast('/', "").split('/').filter(String::isNotBlank).toMutableList()
    referencedPath.split('/').forEach { segment ->
        when (segment) {
            "", "." -> Unit
            ".." -> if (segments.isEmpty()) return null else segments.removeAt(segments.lastIndex)
            else -> {
                if (!segment.matches(SAFE_REPOSITORY_PATH_SEGMENT)) return null
                segments += segment
            }
        }
    }
    return segments.joinToString("/").takeIf(String::isNotBlank)
}

private fun resolveJsonPointer(root: JSONObject, pointer: String): Any? {
    if (pointer.isEmpty()) return root
    if (!pointer.startsWith('/')) return null
    var value: Any? = root
    pointer.removePrefix("/").split('/').forEach { token ->
        val key = token.replace("~1", "/").replace("~0", "~")
        value = when (val current = value) {
            is JSONObject -> current.opt(key).takeUnless { it == null || it == JSONObject.NULL }
            is JSONArray -> key.toIntOrNull()?.takeIf { index -> index in 0 until current.length() }?.let(current::opt)
            else -> null
        }
        if (value == null) return null
    }
    return value
}

private fun selectOpenApiCandidate(candidates: List<OpenApiCandidate>): OpenApiCandidate? =
    candidates.sortedWith { left, right ->
        val preference = openApiPreference(left.path).compareTo(openApiPreference(right.path))
        if (preference != 0) {
            preference
        } else {
            val version = compareSemanticVersions(right.apiVersion.orEmpty(), left.apiVersion.orEmpty())
            if (version != 0) version else left.path.compareTo(right.path)
        }
    }.firstOrNull()

private fun openApiPreference(path: String): Int {
    val normalized = path.lowercase()
    return OPEN_API_FILE_PREFERENCE.indexOf(normalized).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE
}

internal fun parseYamlObject(source: String): JSONObject? {
    val settings = LoadSettings.builder()
        .setLabel("OpenAPI contract")
        .setAllowDuplicateKeys(false)
        .setMaxAliasesForCollections(MAX_YAML_ALIASES)
        .setCodePointLimit(MAX_SELECTED_FILE_BYTES.toInt())
        .build()
    val loaded = Load(settings).loadFromString(source) ?: return null
    return yamlToJson(loaded, 0, intArrayOf(0)) as? JSONObject
}

private fun yamlToJson(value: Any?, depth: Int, nodes: IntArray): Any {
    check(depth <= MAX_YAML_DEPTH) { "The OpenAPI YAML is nested too deeply." }
    nodes[0] += 1
    check(nodes[0] <= MAX_YAML_NODES) { "The OpenAPI YAML has too many values." }
    return when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().also { result ->
            value.forEach { (key, child) ->
                check(key is String || key is Number || key is Boolean) {
                    "OpenAPI YAML object keys must be scalar values."
                }
                result.put(key.toString(), yamlToJson(child, depth + 1, nodes))
            }
        }
        is Iterable<*> -> JSONArray().also { result ->
            value.forEach { child -> result.put(yamlToJson(child, depth + 1, nodes)) }
        }
        is String, is Number, is Boolean -> value
        else -> value.toString()
    }
}

internal fun parseAppInfoIdentity(infoXml: String): Pair<String, String> {
    check(!infoXml.contains("<!DOCTYPE", ignoreCase = true)) { "DOCTYPE is not allowed in app metadata." }
    check(!infoXml.contains("<!ENTITY", ignoreCase = true)) { "Entities are not allowed in app metadata." }
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        // These hardening switches are not implemented consistently by Android's bundled parser.
        // The lexical rejection above and the refusing entity resolver below are mandatory; these
        // supported-feature calls add defense in depth without making valid metadata platform-specific.
        runCatching { isXIncludeAware = false }
        runCatching { isExpandEntityReferences = false }
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
    }
    val builder = factory.newDocumentBuilder().apply {
        setEntityResolver(EntityResolver { _, _ ->
            throw SAXException("External entities are not allowed in app metadata.")
        })
    }
    val document = builder.parse(ByteArrayInputStream(infoXml.encodeToByteArray()))
    check(document.documentElement.nodeName == "info") { "The app metadata root is invalid." }
    fun requiredElement(name: String): String {
        val directChildren = document.documentElement.childNodes
        val matches = (0 until directChildren.length)
            .mapNotNull { index -> directChildren.item(index) as? Element }
            .filter { element -> element.tagName == name }
        check(matches.size == 1) { "The app metadata must contain exactly one $name element." }
        return matches.single().textContent.trim().also { value ->
            check(value.isNotBlank()) { "The app metadata $name is empty." }
        }
    }
    return requiredElement("id") to requiredElement("version")
}

private fun readSelectedTarFiles(archive: ByteArray): Map<String, ByteArray> {
    val selected = linkedMapOf<String, ByteArray>()
    GZIPInputStream(ByteArrayInputStream(archive)).use { input ->
        var totalUncompressed = 0L
        while (true) {
            val header = input.readExactlyOrNull(TAR_BLOCK_BYTES) ?: break
            if (header.all { byte -> byte == 0.toByte() }) break
            val name = header.tarText(0, 100)
            val prefix = header.tarText(345, 155)
            val fullName = if (prefix.isBlank()) name else "$prefix/$name"
            val size = header.tarOctal(124, 12)
            check(size in 0..MAX_TAR_ENTRY_BYTES) { "An app package entry is too large." }
            totalUncompressed += size
            check(totalUncompressed <= MAX_TAR_UNCOMPRESSED_BYTES) { "The app package expands beyond the safety limit." }
            val type = header[156].toInt().toChar()
            if (type in TAR_METADATA_TYPES) {
                input.skipExactly(size)
                val metadataPadding = (TAR_BLOCK_BYTES - (size % TAR_BLOCK_BYTES)) % TAR_BLOCK_BYTES
                input.skipExactly(metadataPadding)
                continue
            }
            validateArchivePath(fullName)
            val staticMetadata = fullName.endsWith("/appinfo/routes.php") ||
                (fullName.contains("/lib/Controller/") && fullName.endsWith("Controller.php"))
            val keep = (type == '\u0000' || type == '0') && (
                fullName.endsWith("/appinfo/info.xml") ||
                    fullName.substringAfterLast('/').matches(OPEN_API_FILE_PATTERN) ||
                    staticMetadata
                )
            if (keep) {
                val selectedFileLimit = if (staticMetadata) MAX_STATIC_METADATA_FILE_BYTES else MAX_SELECTED_FILE_BYTES
                check(size <= selectedFileLimit) { "A contract metadata file exceeds the safety limit." }
                check(selected.size < MAX_SELECTED_ARCHIVE_FILES) { "The app package has too many contract metadata files." }
                selected[fullName] = input.readExactly(size.toInt())
            } else {
                input.skipExactly(size)
            }
            val padding = (TAR_BLOCK_BYTES - (size % TAR_BLOCK_BYTES)) % TAR_BLOCK_BYTES
            input.skipExactly(padding)
        }
    }
    return selected
}

private fun validateArchivePath(path: String) {
    require(path.isNotBlank() && !path.startsWith('/') && '\\' !in path) { "The app package contains an unsafe path." }
    require(path.split('/').none { segment -> segment == "." || segment == ".." }) {
        "The app package contains a traversing path."
    }
}

private fun ByteArray.tarText(offset: Int, length: Int): String =
    copyOfRange(offset, offset + length).takeWhile { byte -> byte != 0.toByte() }.toByteArray()
        .decodeToString()
        .trim()

private fun ByteArray.tarOctal(offset: Int, length: Int): Long {
    val value = tarText(offset, length).trim().trimStart('0')
    return if (value.isEmpty()) 0L else value.toLong(8)
}

private fun InputStream.readExactlyOrNull(length: Int): ByteArray? {
    val first = read()
    if (first == -1) return null
    val result = ByteArray(length)
    result[0] = first.toByte()
    var offset = 1
    while (offset < length) {
        val read = read(result, offset, length - offset)
        check(read > 0) { "The app package is truncated." }
        offset += read
    }
    return result
}

private fun InputStream.readExactly(length: Int): ByteArray {
    val result = ByteArray(length)
    var offset = 0
    while (offset < length) {
        val read = read(result, offset, length - offset)
        check(read > 0) { "The app package is truncated." }
        offset += read
    }
    return result
}

private fun InputStream.skipExactly(length: Long) {
    var remaining = length
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (remaining > 0) {
        val read = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
        check(read > 0) { "The app package is truncated." }
        remaining -= read
    }
}

private fun Response.readBodyLimited(maximumBytes: Long): ByteArray {
    val responseBody = body
    val declaredLength = responseBody.contentLength()
    check(declaredLength < 0 || declaredLength <= maximumBytes) { "The response is too large." }
    return responseBody.byteStream().use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            total += read
            check(total <= maximumBytes) { "The response is too large." }
            output.write(buffer, 0, read)
        }
        output.toByteArray()
    }
}

private fun URI.effectivePort(): Int = when {
    port >= 0 -> port
    scheme.equals("https", ignoreCase = true) -> 443
    scheme.equals("http", ignoreCase = true) -> 80
    else -> -1
}

private fun loadBundledTrustResource(name: String): ByteArray =
    NextcloudAppPackageTrustVerifier::class.java.classLoader.getResourceAsStream(name)?.use(InputStream::readBytes)
        ?: error("The bundled Nextcloud trust resource is missing: $name")

private const val ROOT_CERTIFICATES_RESOURCE = "nextcloud-codesigning/root.crt"
private const val ROOT_REVOCATION_LIST_RESOURCE = "nextcloud-codesigning/root.crl"
private const val TAR_BLOCK_BYTES = 512
private const val MAX_TAR_ENTRY_BYTES = 128L * 1024L * 1024L
private const val MAX_TAR_UNCOMPRESSED_BYTES = 256L * 1024L * 1024L
private const val MAX_SELECTED_FILE_BYTES = 16L * 1024L * 1024L
private const val MAX_STATIC_METADATA_FILE_BYTES = 1L * 1024L * 1024L
private const val MAX_SELECTED_ARCHIVE_FILES = 320
private const val MAX_OPEN_API_CANDIDATES = 64
private const val MAX_EXTERNAL_SCHEMA_DOCUMENTS = 16
private const val MAX_EXTERNAL_REFERENCE_DEPTH = 24
private const val MAX_YAML_ALIASES = 32
private const val MAX_YAML_DEPTH = 100
private const val MAX_YAML_NODES = 250_000
private val OPEN_API_FILE_PREFERENCE = listOf(
    "openapi.json",
    "openapi.yaml",
    "openapi.yml",
    "openapi-full.json",
    "openapi-public.json",
)
private val OPEN_API_FILE_PATTERN = Regex("(?i)openapi(?:[-_][A-Za-z0-9][A-Za-z0-9._-]*)?\\.(?:json|yaml|yml)")
private val GITHUB_REPOSITORY_COMPONENT = Regex("[A-Za-z0-9_.-]+")
private val SAFE_REPOSITORY_PATH_SEGMENT = Regex("[A-Za-z0-9_.-]+")
private val GITHUB_TAG_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._+-]*")
private val PRE_RELEASE_MARKERS = listOf("alpha", "beta", "rc", "dev", "nightly")
private val TAR_METADATA_TYPES = setOf('g', 'x', 'L', 'K')
