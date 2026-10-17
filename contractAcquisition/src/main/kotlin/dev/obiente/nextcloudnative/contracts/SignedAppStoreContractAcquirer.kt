package dev.obiente.nextcloudnative.contracts

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import org.xml.sax.EntityResolver
import org.xml.sax.SAXException
import javax.xml.parsers.DocumentBuilderFactory

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
    private val catalogRefreshThrottle: CatalogRefreshThrottle = CatalogRefreshThrottle(),
    private val responseLimits: ContractResponseLimits = ContractResponseLimits(),
) {
    private val verifiedContracts = ConcurrentHashMap<ContractAcquisitionRequest, VerifiedOpenApiContract>()
    private val inFlightAcquisitions =
        ConcurrentHashMap<Pair<ContractAcquisitionRequest, CatalogPolicy>, CompletableFuture<ContractAcquisitionOutcome>>()

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

    /** Returns the verified contract, or null for any [ContractAcquisitionOutcome.Unavailable] reason. */
    fun acquire(request: ContractAcquisitionRequest): VerifiedOpenApiContract? =
        (acquireOutcome(request) as? ContractAcquisitionOutcome.Acquired)?.contract

    /** [catalogPolicy] is [CatalogPolicy.Refresh] only for an explicit user retry; see [CatalogRefreshThrottle]. */
    fun acquireOutcome(
        request: ContractAcquisitionRequest,
        catalogPolicy: CatalogPolicy = CatalogPolicy.UseCache,
    ): ContractAcquisitionOutcome {
        val owner = CompletableFuture<ContractAcquisitionOutcome>()
        val existing = inFlightAcquisitions.putIfAbsent(request to catalogPolicy, owner)
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
            acquireUncoalesced(request, catalogPolicy).also { acquired -> owner.complete(acquired) }
        } catch (failure: Throwable) {
            owner.completeExceptionally(failure)
            throw failure
        } finally {
            inFlightAcquisitions.remove(request to catalogPolicy, owner)
        }
    }

    private fun acquireUncoalesced(
        request: ContractAcquisitionRequest,
        catalogPolicy: CatalogPolicy,
    ): ContractAcquisitionOutcome {
        requireValidRequest(request.appId.matches(APP_ID_PATTERN)) { "The app ID is invalid." }
        val coreServerVersion = normalizeServerVersion(request.serverVersion)
        request.installedAppVersion?.let { version ->
            requireValidRequest(version.matches(APP_VERSION_PATTERN)) { "The installed app version is invalid." }
        }
        verifiedContracts[request]?.let { return ContractAcquisitionOutcome.Acquired(it) }
        verifiedContractCache.load(request)?.let { cached ->
            verifiedContracts[request] = cached
            return ContractAcquisitionOutcome.Acquired(cached)
        }
        val compatibleCatalogUrl =
            "${appStoreBaseUrl.trimEnd('/')}/platform/$coreServerVersion/apps.json"
        val platformCatalog = fetchCatalogReleases(compatibleCatalogUrl, request.appId, catalogPolicy)
        if (platformCatalog.refreshDenied) return ContractAcquisitionOutcome.Unavailable(ContractUnavailableReason.RecentlyChecked)
        val platformReleases = platformCatalog.releases
        val exactPlatformRelease = request.installedAppVersion?.let { installed ->
            platformReleases.firstOrNull { release -> release.version == installed }
        }
        val allCatalog = request.installedAppVersion?.takeIf { exactPlatformRelease == null }?.let {
            fetchCatalogReleases("${appStoreBaseUrl.trimEnd('/')}/apps.json", request.appId, catalogPolicy)
        }
        if (allCatalog?.refreshDenied == true) return ContractAcquisitionOutcome.Unavailable(ContractUnavailableReason.RecentlyChecked)
        val exactRelease = exactPlatformRelease
            ?: allCatalog?.releases?.firstOrNull { release -> release.version == request.installedAppVersion }
        val compatibleReleases = compatibleReleaseCandidates(
            releases = platformReleases,
            installedVersion = request.installedAppVersion,
        ).filterNot { candidate -> candidate.version == exactRelease?.version }
        val releases = listOfNotNull(exactRelease) + compatibleReleases
        if (releases.isEmpty()) return unmatchedReleaseOutcome(request.installedAppVersion, platformCatalog, allCatalog)
        val attempt = ContractAcquisitionAttempt()

        val missingPackageContracts = mutableListOf<AppStoreRelease>()
        val verifiedRouteFallbacks = mutableListOf<Pair<AppStoreRelease, VerifiedPackageContract>>()
        for (release in releases) {
            requireSecureUrl(release.downloadUrl)
            val archiveResponse = execute(Request.Builder().url(release.downloadUrl).get().build())
            val archive = archiveResponse.use { response ->
                requireSecureResponse(response)
                if (response.code in setOf(404, 410) && verifiedRouteFallbacks.isNotEmpty()) return@use null
                requireSuccessfulSource(response) {
                    "Downloading the signed ${request.appId} package failed (HTTP ${response.code})."
                }
                response.readBodyLimited(responseLimits.archiveBytes)
            } ?: continue
            val verified = try {
                trustVerifier.verifyAndExtract(release, archive)
            } catch (_: OpenApiContractMissingException) {
                missingPackageContracts += release
                continue
            } catch (failure: Exception) {
                throw failure.asSourceVerificationFailure(request.appId)
            }
            if (verified.contractKind == VerifiedContractKind.VerifiedReadRoutes) {
                acquireGitHubTagContract(
                    release = release,
                    installedAppVersion = request.installedAppVersion,
                    exact = release.version == request.installedAppVersion,
                    attempt = attempt,
                )?.let { linkedContract ->
                    return cacheContract(
                        request,
                        linkedContract.mergeVerifiedPackageRoutes(verified),
                    )
                }
                verifiedRouteFallbacks += release to verified
                continue
            }
            return cacheContract(request, verified.asPackageContract(request, release))
        }

        for (release in missingPackageContracts) {
            val exact = release.version == request.installedAppVersion
            acquireGitHubTagContract(release, request.installedAppVersion, exact, attempt)?.let { contract ->
                return cacheContract(request, contract)
            }
        }
        val (release, verified) = verifiedRouteFallbacks.firstOrNull() ?: return attempt.unavailable()
        return cacheContract(request, verified.asPackageContract(request, release))
    }

    private fun VerifiedPackageContract.asPackageContract(
        request: ContractAcquisitionRequest,
        release: AppStoreRelease,
    ) = VerifiedOpenApiContract(
        appId = appId,
        appVersion = request.installedAppVersion ?: appVersion,
        contractVersion = appVersion,
        specFile = specFile,
        document = document,
        catalogUrl = release.catalogUrl,
        packageUrl = release.downloadUrl,
        sourceUrl = "${release.downloadUrl}#$specFile",
        sourceKind = if (release.version == request.installedAppVersion) {
            OpenApiContractSourceKind.SignedAppPackage
        } else {
            OpenApiContractSourceKind.SignedCompatibleAppPackage
        },
        contractKind = contractKind,
    )

    private fun cacheContract(
        request: ContractAcquisitionRequest,
        contract: VerifiedOpenApiContract,
    ): ContractAcquisitionOutcome = ContractAcquisitionOutcome.Acquired(contract).also {
        verifiedContracts[request] = contract
        runCatching { verifiedContractCache.store(request, contract) }
    }

    private fun acquireGitHubTagContract(
        release: AppStoreRelease,
        installedAppVersion: String?,
        exact: Boolean,
        attempt: ContractAcquisitionAttempt,
    ): VerifiedOpenApiContract? {
        release.githubSources.forEach sourceLoop@ { source ->
            val sourceRoot = "${rawGitHubBaseUrl.trimEnd('/')}/${source.owner}/${source.repository}/${source.tag}"
            val infoUrl = "$sourceRoot/appinfo/info.xml"
            val infoXml = fetchOptionalGitHubResource(
                url = infoUrl,
                expectedBaseUrl = rawGitHubBaseUrl,
                maximumBytes = responseLimits.appInfoBytes,
            )?.decodeToString() ?: return@sourceLoop
            val identity = runCatching { parseAppInfoIdentity(infoXml) }.getOrNull() ?: return@sourceLoop
            if (identity.first != release.appId || identity.second != release.version) return@sourceLoop

            val treeUrl = "${githubApiBaseUrl.trimEnd('/')}/repos/${source.owner}/${source.repository}" +
                "/git/trees/${source.tag}?recursive=1"
            val treeBytes = fetchOptionalGitHubResource(
                url = treeUrl,
                expectedBaseUrl = githubApiBaseUrl,
                maximumBytes = responseLimits.githubTreeBytes,
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
            } ?: listOfNotNull(acquireCodeloadCandidate(source, release, sourceRoot, attempt))
            selectOpenApiCandidate(contracts, release.appId)
                ?.let { selected ->
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
        attempt: ContractAcquisitionAttempt,
    ): OpenApiCandidate? {
        val archiveUrl = "${codeloadGitHubBaseUrl.trimEnd('/')}/${source.owner}/${source.repository}" +
            "/tar.gz/refs/tags/${source.tag}"
        val archive = fetchOptionalGitHubResource(
            url = archiveUrl,
            expectedBaseUrl = codeloadGitHubBaseUrl,
            maximumBytes = responseLimits.archiveBytes,
            recoverableStatusCodes = setOf(403, 429),
            // The archive is the last exact-tag fallback; a refusal here leaves the outcome unknown.
            onRecoverableStatus = attempt::markSourceTemporarilyUnavailable,
        ) ?: return null
        val extracted = extractLinkedSourceContractOrNull(archive, release) ?: return null
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
        onRecoverableStatus: () -> Unit = {},
    ): ByteArray? {
        requireSecureUrl(url)
        val response = execute(Request.Builder().url(url).header("Accept", accept).get().build())
        return response.use { githubResponse ->
            if (githubResponse.code == 404) return@use null
            if (githubResponse.code in recoverableStatusCodes) return@use null.also { onRecoverableStatus() }
            requireSuccessfulSource(githubResponse) {
                "Loading an exact-tag GitHub contract resource failed (HTTP ${githubResponse.code})."
            }
            requireSecureResponse(githubResponse)
            requireExpectedOrigin(githubResponse, expectedBaseUrl)
            // An oversized linked resource is unusable, like a missing one; the lower-trust fallback moves on.
            try {
                githubResponse.readBodyLimited(maximumBytes)
            } catch (_: ContractSourceTooLargeException) {
                null
            }
        }
    }

    private fun fetchCatalogReleases(
        catalogUrl: String,
        appId: String,
        catalogPolicy: CatalogPolicy,
    ): CatalogReleases {
        requireSecureUrl(catalogUrl)
        // A refresh only bypasses the cached copy; the copy stays until a readable catalog replaces it.
        val refreshAllowed = catalogPolicy == CatalogPolicy.Refresh && catalogRefreshThrottle.tryRefresh(catalogUrl)
        catalogCache.load(catalogUrl)?.takeUnless { refreshAllowed }?.let { cached ->
            return parseReleases(cached.decodeToString(), appId, catalogUrl, requireOfficialGitHubHost = requireHttps)
        }
        // A retry inside the refresh window never reaches the App Store, even without a cached copy.
        if (catalogPolicy == CatalogPolicy.Refresh && !refreshAllowed) return CatalogReleases(emptyList(), refreshDenied = true)
        val response = execute(Request.Builder().url(catalogUrl).get().build())
        val bytes = response.use { catalogResponse ->
            requireSuccessfulSource(catalogResponse) {
                "Loading the Nextcloud App Store catalog failed (HTTP ${catalogResponse.code})."
            }
            requireSecureResponse(catalogResponse)
            catalogResponse.readBodyLimited(responseLimits.catalogBytes)
        }
        // An unreadable document is not cached, so checking again downloads the catalog again.
        return parseReleases(bytes.decodeToString(), appId, catalogUrl, requireOfficialGitHubHost = requireHttps)
            .also { parsed -> if (parsed.documentReadable) catalogCache.storeBestEffort(catalogUrl, bytes) }
    }

    private fun execute(request: Request): Response = httpClient.newCall(request).execute()

    private fun requireSecureUrl(url: String) {
        if (requireHttps) requireVerifiedSource(url.startsWith("https://")) { "Contract sources must use HTTPS." }
    }

    private fun requireSecureResponse(response: Response) {
        if (requireHttps) requireVerifiedSource(response.request.url.isHttps) { "A contract request left HTTPS." }
    }

    private fun requireExpectedOrigin(response: Response, expectedBaseUrl: String) {
        val expected = URI(expectedBaseUrl)
        val actual = response.request.url
        requireVerifiedSource(
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

class OpenApiContractMissingException(message: String) : IllegalStateException(message)

internal fun extractOpenApiContract(
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
    val selected = selectOpenApiCandidate(candidates, release.appId)?.let { candidate ->
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

private fun Response.readBodyLimited(maximumBytes: Long): ByteArray {
    val responseBody = body
    val declaredLength = responseBody.contentLength()
    if (declaredLength > maximumBytes) throw ContractSourceTooLargeException(maximumBytes, "The response is too large.")
    return responseBody.byteStream().use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            total += read
            if (total > maximumBytes) throw ContractSourceTooLargeException(maximumBytes, "The response is too large.")
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

internal const val MAX_SELECTED_FILE_BYTES = 16L * 1024L * 1024L
private const val MAX_OPEN_API_CANDIDATES = 64
private const val MAX_EXTERNAL_SCHEMA_DOCUMENTS = 16
private const val MAX_EXTERNAL_REFERENCE_DEPTH = 24
internal val OPEN_API_FILE_PATTERN = Regex("(?i)openapi(?:[-_][A-Za-z0-9][A-Za-z0-9._-]*)?\\.(?:json|yaml|yml)")
private val SAFE_REPOSITORY_PATH_SEGMENT = Regex("[A-Za-z0-9_.-]+")
