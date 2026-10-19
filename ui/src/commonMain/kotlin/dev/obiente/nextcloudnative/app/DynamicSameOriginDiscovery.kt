package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.AdvertisedOpenApi
import dev.obiente.nextcloudnative.nativeui.model.AppIdentity
import dev.obiente.nextcloudnative.nativeui.model.DynamicAppDescriptor
import dev.obiente.nextcloudnative.nativeui.model.DynamicAppDescriptorCompiler
import dev.obiente.nextcloudnative.nativeui.model.DynamicDiscoveryInput
import dev.obiente.nextcloudnative.nativeui.model.EndpointPolicy
import dev.obiente.nextcloudnative.nativeui.model.requireValid
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Why a same-origin catalog or contract probe could not prove that the server offers no contract.
 *
 * [rank] orders results when probes disagree: access problems first, because signing in or gaining
 * permission can change every other answer; then inconclusive results that a retry may resolve;
 * then a definitive but unusable contract. Only a definitive absence ([DEFINITIVE_ABSENCE_STATUSES])
 * records nothing; every other unsuccessful status is classified, so a failed request is never
 * read as absence.
 */
enum class DynamicServerProbeFailure(internal val rank: Int) {
    /** The server rejected the account's credentials (HTTP 401). */
    AuthenticationRequired(0),

    /** The account may not read the catalog or contract, for example an administrator-only endpoint (HTTP 403). */
    PermissionDenied(1),

    /** The request produced no HTTP response. */
    NoResponse(2),

    /** The server answered with a 5xx status. */
    ServerError(2),

    /** The server rate limited the request (HTTP 429). */
    Throttled(2),

    /** A successful catalog or contract response was not readable JSON or carried no OpenAPI version. */
    UnreadableResponse(2),

    /**
     * Any other unsuccessful status, such as 400, 405, 408, 409, 412, an unfollowed 3xx redirect, or
     * a 1xx status. It neither proves absence nor has a more specific meaning.
     */
    UnexpectedStatus(2),

    /** The server offered a contract that is not OpenAPI 3, such as Swagger 2, or that cannot be compiled. */
    UnsupportedContract(3),
    ;

    internal fun isInconclusive(): Boolean = when (this) {
        AuthenticationRequired, PermissionDenied, UnsupportedContract -> false
        NoResponse, ServerError, Throttled, UnreadableResponse, UnexpectedStatus -> true
    }
}

/**
 * Statuses that prove a probe target is absent. 404 is the server's ordinary answer for a missing
 * app route or asset. 410 is included because it states the same thing permanently. Every other
 * unsuccessful status is evidence that the probe failed, not that the contract is absent.
 */
internal val DEFINITIVE_ABSENCE_STATUSES = setOf(404, 410)

/**
 * The typed probe result for an HTTP [status], or null for a success or a definitive absence. Every
 * other status has a typed kind, so an unrecognized failure is inconclusive rather than absence.
 */
internal fun probeFailureForStatus(status: Int): DynamicServerProbeFailure? = when {
    status in 200..299 || status in DEFINITIVE_ABSENCE_STATUSES -> null
    status == 401 -> DynamicServerProbeFailure.AuthenticationRequired
    status == 403 -> DynamicServerProbeFailure.PermissionDenied
    status == 429 -> DynamicServerProbeFailure.Throttled
    status in 500..599 -> DynamicServerProbeFailure.ServerError
    else -> DynamicServerProbeFailure.UnexpectedStatus
}

/** Keeps the highest-precedence probe result; among equal ranks the first result wins. */
private class DynamicServerProbe {
    var failure: DynamicServerProbeFailure? = null
        private set

    fun record(failure: DynamicServerProbeFailure) {
        if (this.failure.let { current -> current == null || failure.rank < current.rank }) this.failure = failure
    }

    fun record(response: NextcloudApiResponse) {
        probeFailureForStatus(response.status)?.let(::record)
    }
}

/** Diagnostic text and contract-info code derive from the typed result, never from response content. */
private fun DynamicServerProbeFailure.diagnostic(): String = when {
    isInconclusive() -> "The server API probe was inconclusive: $name."
    this == DynamicServerProbeFailure.UnsupportedContract -> "The server API probe found an unsupported contract."
    else -> "The server API probe was not authorized: $name."
}

private fun DynamicServerProbeFailure.diagnosticCode(): DynamicDiagnosticCode = when {
    isInconclusive() -> DynamicDiagnosticCode.ServerProbeInconclusive
    this == DynamicServerProbeFailure.UnsupportedContract -> DynamicDiagnosticCode.ServerContractUnsupported
    else -> DynamicDiagnosticCode.ServerProbeAccessDenied
}

private fun DynamicServerProbeFailure.fallbackReason(): DynamicContractFallbackReason = when (this) {
    DynamicServerProbeFailure.AuthenticationRequired -> DynamicContractFallbackReason.ServerAuthenticationRequired
    DynamicServerProbeFailure.PermissionDenied -> DynamicContractFallbackReason.ServerPermissionDenied
    DynamicServerProbeFailure.UnsupportedContract -> DynamicContractFallbackReason.UnsupportedContract
    DynamicServerProbeFailure.NoResponse,
    DynamicServerProbeFailure.ServerError,
    DynamicServerProbeFailure.Throttled,
    DynamicServerProbeFailure.UnreadableResponse,
    DynamicServerProbeFailure.UnexpectedStatus,
    -> DynamicContractFallbackReason.ServerProbeInconclusive
}

internal suspend fun discoverDynamicAppDescriptor(
    serverUrl: String,
    app: NextcloudAppEntry,
    execute: suspend (NextcloudApiRequest) -> NextcloudApiResponse,
    onProgress: (DynamicDescriptorDiscoveryProgress) -> Unit = {},
): DynamicDescriptorDiscovery {
    require(app.id.matches(Regex("[A-Za-z0-9_.-]+"))) { "The app ID is invalid." }
    val origin = serverUrl.httpOrigin()
    val policy = EndpointPolicy(
        serverOrigin = origin,
        approvedApiPrefixes = listOf(
            "/ocs/v1.php/apps/${app.id}",
            "/ocs/v2.php/apps/${app.id}",
            "/index.php/apps/${app.id}",
        ),
    )
    val identity = AppIdentity(app.id, app.name, "server-installed")
    val compiler = DynamicAppDescriptorCompiler()
    val diagnostics = mutableListOf<String>()
    val codes = mutableListOf<DynamicDiagnosticCode>()
    val probe = DynamicServerProbe()

    onProgress(
        DynamicDescriptorDiscoveryProgress(
            DynamicDescriptorDiscoveryPhase.ServerApiCatalog,
            "Checking the server API catalog",
        ),
    )
    val viewerSpecs = discoverOfficialViewerSpecIds(app.id, execute, diagnostics, codes, probe)
    viewerSpecs.forEach { specId ->
        onProgress(
            DynamicDescriptorDiscoveryProgress(
                DynamicDescriptorDiscoveryPhase.AppApiDefinition,
                "Reading the app's API definition",
            ),
        )
        val path = "$OCS_API_VIEWER_SPEC_PATH/${specId.encodeUrlComponent()}"
        val response = runCatchingPreservingCancellation { execute(dynamicDiscoveryRequest(path)) }
            .onFailure {
                probe.record(DynamicServerProbeFailure.NoResponse)
                diagnostics += "OCS API Viewer could not load $specId (stage: spec, ${DynamicServerProbeFailure.NoResponse.name})."
            }
            .getOrNull() ?: return@forEach
        if (response.status !in 200..299) {
            probe.record(response)
            diagnostics += "OCS API Viewer could not load $specId (stage: spec, ${response.statusClass()})."
            return@forEach
        }
        val document = response.parseOpenApiDocument()
        if (document == null) {
            probe.record(response.contractBodyFailure())
            codes += DynamicDiagnosticCode.ContractInvalid
            diagnostics += "OCS API Viewer returned an invalid OpenAPI JSON body for $specId${response.contentTypeDiagnostic()}."
            return@forEach
        }
        onProgress(
            DynamicDescriptorDiscoveryProgress(
                DynamicDescriptorDiscoveryPhase.NativeWorkspace,
                "Preparing the native workspace",
            ),
        )
        val descriptor = runCatchingPreservingCancellation {
            compileDynamicDescriptor(compiler, identity, policy, serverUrl, path, document)
        }.onFailure {
            probe.record(DynamicServerProbeFailure.UnsupportedContract)
            diagnostics += "OCS API Viewer returned an unusable specification for $specId (stage: compilation)."
        }.getOrNull() ?: return@forEach
        return DynamicDescriptorDiscovery(
            descriptor = descriptor,
            sourcePath = path,
            acquisition = DynamicDescriptorAcquisition.OcsApiViewer,
            diagnostics = diagnostics,
            diagnosticCodes = codes,
        )
    }

    val staticCandidates = buildList {
        listOf("openapi.json", "openapi-full.json", "openapi-public.json").forEach { file ->
            add("/apps/${app.id}/$file")
            add("/custom_apps/${app.id}/$file")
        }
    }
    onProgress(
        DynamicDescriptorDiscoveryProgress(
            DynamicDescriptorDiscoveryPhase.AppApiDefinition,
            "Reading the app's API definition",
        ),
    )
    staticCandidates.forEach { path ->
        val response = runCatchingPreservingCancellation {
            execute(dynamicDiscoveryRequest(path))
        }.onFailure { probe.record(DynamicServerProbeFailure.NoResponse) }.getOrNull() ?: return@forEach
        if (response.status !in 200..299 || response.contentType?.contains("json", ignoreCase = true) != true) {
            probe.record(response)
            return@forEach
        }
        // A JSON response at a contract location is evidence; only a non-JSON response is absence.
        val document = response.parseOpenApiDocument()
        if (document == null) {
            probe.record(response.contractBodyFailure())
            return@forEach
        }
        onProgress(
            DynamicDescriptorDiscoveryProgress(
                DynamicDescriptorDiscoveryPhase.NativeWorkspace,
                "Preparing the native workspace",
            ),
        )
        val descriptor = runCatchingPreservingCancellation {
            compileDynamicDescriptor(compiler, identity, policy, serverUrl, path, document)
        }.onFailure { probe.record(DynamicServerProbeFailure.UnsupportedContract) }.getOrNull() ?: return@forEach
        return DynamicDescriptorDiscovery(
            descriptor = descriptor,
            sourcePath = path,
            acquisition = DynamicDescriptorAcquisition.StaticAppAsset,
            diagnostics = diagnostics,
            diagnosticCodes = codes,
        )
    }

    diagnostics += "No valid static OpenAPI document was found in the app's advertised asset locations."
    codes += DynamicDiagnosticCode.StaticContractUnavailable
    probe.failure?.let { failure ->
        diagnostics += failure.diagnostic()
        codes += failure.diagnosticCode()
    }

    onProgress(
        DynamicDescriptorDiscoveryProgress(
            DynamicDescriptorDiscoveryPhase.NativeWorkspace,
            "Preparing the native workspace",
        ),
    )
    return DynamicDescriptorDiscovery(
        descriptor = compiler.compile(
            DynamicDiscoveryInput(
                app = identity,
                endpointPolicy = policy,
            ),
        ).requireValid(),
        sourcePath = null,
        acquisition = DynamicDescriptorAcquisition.MetadataFallback,
        diagnostics = diagnostics,
        diagnosticCodes = codes,
        fallbackReason = probe.failure?.fallbackReason(),
    )
}

private suspend fun discoverOfficialViewerSpecIds(
    appId: String,
    execute: suspend (NextcloudApiRequest) -> NextcloudApiResponse,
    diagnostics: MutableList<String>,
    codes: MutableList<DynamicDiagnosticCode>,
    probe: DynamicServerProbe,
): List<String> {
    val response = runCatchingPreservingCancellation { execute(dynamicDiscoveryRequest(OCS_API_VIEWER_CATALOG_PATH)) }
        .onFailure {
            probe.record(DynamicServerProbeFailure.NoResponse)
            diagnostics += "OCS API Viewer is unavailable (stage: catalog, ${DynamicServerProbeFailure.NoResponse.name})."
        }
        .getOrNull() ?: return emptyList()
    if (response.status in DEFINITIVE_ABSENCE_STATUSES) {
        codes += DynamicDiagnosticCode.ApiViewerUnavailable
        diagnostics += "OCS API Viewer is not installed or enabled for this user (stage: catalog, HTTP 4xx)."
        return emptyList()
    }
    if (response.status !in 200..299) {
        probe.record(response)
        diagnostics += "OCS API Viewer is unavailable (stage: catalog, ${response.statusClass()})."
        return emptyList()
    }
    val catalog = runCatchingPreservingCancellation { dynamicJson.parseToJsonElement(response.body.decodeToString()) as? JsonArray }
        .getOrNull()
    if (catalog == null) {
        probe.record(DynamicServerProbeFailure.UnreadableResponse)
        diagnostics += "OCS API Viewer returned an invalid app catalog${response.contentTypeDiagnostic()}."
        return emptyList()
    }
    // An element that is not an object, or has no string `id`, may have been this app, as in App Store catalogs.
    val identified = catalog.mapNotNull { element -> (element as? JsonObject)?.takeIf { it.viewerAppId() != null } }
    // Differing records for this app make every one of them ambiguous; identical copies are read once.
    val records = identified.filter { entry -> entry.viewerAppId() == appId }.distinct()
    if (records.size > 1) {
        probe.record(DynamicServerProbeFailure.UnreadableResponse)
        diagnostics += "OCS API Viewer listed differing records for $appId (stage: catalog, duplicate app records)."
        return emptyList()
    }
    val appEntry = records.firstOrNull()
    if (appEntry == null) {
        val unidentified = catalog.size - identified.size
        if (unidentified > 0) {
            probe.record(DynamicServerProbeFailure.UnreadableResponse)
            diagnostics += if (unidentified == catalog.size) {
                "OCS API Viewer returned an unreadable app catalog (stage: catalog, no readable app ID)."
            } else {
                "OCS API Viewer listed entries without a readable app ID, so $appId may be unlisted or unreadable."
            }
            return emptyList()
        }
        diagnostics += "OCS API Viewer does not advertise an OpenAPI specification for $appId."
        return emptyList()
    }
    // A missing or non-array list, or a malformed identifier when nothing usable remains, is an
    // unreadable advertisement. A well-formed empty list, or one naming only other apps, is absence.
    val advertised = appEntry["specs"] as? JsonArray
    val identifiers = advertised.orEmpty().map { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.contentOrNull }
    val wellFormed = identifiers.filterNotNull().filter { it.matches(Regex("[A-Za-z0-9_.-]+")) }
    val specs = wellFormed
        .filter { it == appId || it.startsWith("$appId-") }
        .distinct()
        .sortedWith(compareBy<String> { it != appId }.thenBy { it })
    if (specs.isEmpty()) {
        if (advertised == null || wellFormed.size != identifiers.size) {
            probe.record(DynamicServerProbeFailure.UnreadableResponse)
        }
        diagnostics += "OCS API Viewer advertised $appId without a usable specification ID."
    }
    return specs
}

private fun JsonObject.viewerAppId(): String? = (get("id") as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun dynamicDiscoveryRequest(path: String): NextcloudApiRequest = NextcloudApiRequest(
    method = NextcloudApiMethod.GET,
    relativePath = path,
    maximumResponseBytes = DEFAULT_DYNAMIC_API_RESPONSE_LIMIT_BYTES,
)

private fun NextcloudApiResponse.parseOpenApiDocument(): JsonObject? {
    val document = runCatching { dynamicJson.parseToJsonElement(body.decodeToString()) as? JsonObject }
        .getOrNull() ?: return null
    // Only a JSON string declares the version; a number such as 3.0 is not coerced into one.
    val version = (document["openapi"] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
    return document.takeIf { version?.startsWith("3.") == true }
}

/** Classifies a successful contract body that [parseOpenApiDocument] rejected. */
private fun NextcloudApiResponse.contractBodyFailure(): DynamicServerProbeFailure {
    val root = runCatching { dynamicJson.parseToJsonElement(body.decodeToString()) as? JsonObject }.getOrNull()
    val declaredVersion = (root?.get("openapi") ?: root?.get("swagger")) as? JsonPrimitive
    return if (declaredVersion?.contentOrNull != null) {
        DynamicServerProbeFailure.UnsupportedContract
    } else {
        DynamicServerProbeFailure.UnreadableResponse
    }
}

/** Only the status class enters diagnostics; exact statuses and header values stay out. */
private fun NextcloudApiResponse.statusClass(): String = "HTTP ${status / 100}xx"

/** A bounded media-type token; arbitrary server header text never enters diagnostics. */
private fun NextcloudApiResponse.contentTypeDiagnostic(): String {
    val mediaType = contentType?.substringBefore(';')?.trim()?.lowercase()
        ?.takeIf { value -> value.length <= 64 && value.all { it.isLetterOrDigit() || it in "/.+-" } }
    return " (stage: body, media type ${mediaType ?: "unavailable"})"
}

private fun compileDynamicDescriptor(
    compiler: DynamicAppDescriptorCompiler,
    identity: AppIdentity,
    policy: EndpointPolicy,
    serverUrl: String,
    path: String,
    document: JsonObject,
): DynamicAppDescriptor = compiler.compile(
    DynamicDiscoveryInput(
        app = identity,
        endpointPolicy = policy,
        advertisedOpenApi = AdvertisedOpenApi(
            documentUrl = serverUrl.trimEnd('/') + path,
            document = document,
        ),
    ),
).requireValid()

private const val OCS_API_VIEWER_CATALOG_PATH = "/index.php/apps/ocs_api_viewer/apps"
private const val OCS_API_VIEWER_SPEC_PATH = "/index.php/apps/ocs_api_viewer/apps"
