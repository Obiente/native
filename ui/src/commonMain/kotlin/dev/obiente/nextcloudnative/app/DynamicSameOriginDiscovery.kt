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
 * then a definitive but unusable contract. A definitive absence, such as 404, records nothing.
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

    /** The server offered a contract that is not OpenAPI 3, such as Swagger 2, or that cannot be compiled. */
    UnsupportedContract(3),
    ;

    internal fun isInconclusive(): Boolean = rank == 2
}

/** Keeps the highest-precedence probe result; among equal ranks the first result wins. */
private class DynamicServerProbe {
    var failure: DynamicServerProbeFailure? = null
        private set

    fun record(failure: DynamicServerProbeFailure) {
        if (this.failure.let { current -> current == null || failure.rank < current.rank }) this.failure = failure
    }

    fun record(response: NextcloudApiResponse) {
        when {
            response.status == 401 -> record(DynamicServerProbeFailure.AuthenticationRequired)
            response.status == 403 -> record(DynamicServerProbeFailure.PermissionDenied)
            response.status == 429 -> record(DynamicServerProbeFailure.Throttled)
            response.status in 500..599 -> record(DynamicServerProbeFailure.ServerError)
        }
    }
}

/** Diagnostic text and contract-info code derive from the typed result, never from response content. */
private fun DynamicServerProbeFailure.diagnostic(): String = when {
    isInconclusive() -> "The server API probe was inconclusive: $name."
    this == DynamicServerProbeFailure.UnsupportedContract -> "The server API probe found an unsupported contract."
    else -> "The server API probe was not authorized: $name."
}

private fun DynamicServerProbeFailure.fallbackReason(): DynamicContractFallbackReason = when (this) {
    DynamicServerProbeFailure.AuthenticationRequired -> DynamicContractFallbackReason.ServerAuthenticationRequired
    DynamicServerProbeFailure.PermissionDenied -> DynamicContractFallbackReason.ServerPermissionDenied
    DynamicServerProbeFailure.UnsupportedContract -> DynamicContractFallbackReason.UnsupportedContract
    else -> DynamicContractFallbackReason.ServerProbeInconclusive
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
    val probe = DynamicServerProbe()

    onProgress(
        DynamicDescriptorDiscoveryProgress(
            DynamicDescriptorDiscoveryPhase.ServerApiCatalog,
            "Checking the server API catalog",
        ),
    )
    val viewerSpecs = discoverOfficialViewerSpecIds(app.id, execute, diagnostics, probe)
    viewerSpecs.forEach { specId ->
        onProgress(
            DynamicDescriptorDiscoveryProgress(
                DynamicDescriptorDiscoveryPhase.AppApiDefinition,
                "Reading the app's API definition",
            ),
        )
        val path = "$OCS_API_VIEWER_SPEC_PATH/${specId.encodeUrlComponent()}"
        val response = runCatchingPreservingCancellation { execute(dynamicDiscoveryRequest(path)) }
            .onFailure { failure ->
                probe.record(DynamicServerProbeFailure.NoResponse)
                diagnostics += "OCS API Viewer could not load $specId: ${failure.message ?: "request failed"}."
            }
            .getOrNull() ?: return@forEach
        if (response.status !in 200..299) {
            probe.record(response)
            diagnostics += "OCS API Viewer could not load $specId (HTTP ${response.status})."
            return@forEach
        }
        val document = response.parseOpenApiDocument()
        if (document == null) {
            probe.record(response.contractBodyFailure())
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
        }.onFailure { failure ->
            probe.record(DynamicServerProbeFailure.UnsupportedContract)
            diagnostics += "OCS API Viewer returned an unusable specification for $specId: " +
                (failure.message ?: "compilation failed") + "."
        }.getOrNull() ?: return@forEach
        return DynamicDescriptorDiscovery(
            descriptor = descriptor,
            sourcePath = path,
            acquisition = DynamicDescriptorAcquisition.OcsApiViewer,
            diagnostics = diagnostics,
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
        )
    }

    diagnostics += "No valid static OpenAPI document was found in the app's advertised asset locations."
    probe.failure?.let { failure -> diagnostics += failure.diagnostic() }

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
        fallbackReason = probe.failure?.fallbackReason(),
    )
}

private suspend fun discoverOfficialViewerSpecIds(
    appId: String,
    execute: suspend (NextcloudApiRequest) -> NextcloudApiResponse,
    diagnostics: MutableList<String>,
    probe: DynamicServerProbe,
): List<String> {
    val response = runCatchingPreservingCancellation { execute(dynamicDiscoveryRequest(OCS_API_VIEWER_CATALOG_PATH)) }
        .onFailure { failure ->
            probe.record(DynamicServerProbeFailure.NoResponse)
            diagnostics += "OCS API Viewer is unavailable at its official authenticated endpoint: " +
                (failure.message ?: "request failed") + "."
        }
        .getOrNull() ?: return emptyList()
    if (response.status == 404) {
        diagnostics += "OCS API Viewer is not installed or enabled for this user (HTTP 404 at $OCS_API_VIEWER_CATALOG_PATH)."
        return emptyList()
    }
    if (response.status !in 200..299) {
        probe.record(response)
        diagnostics += "OCS API Viewer is unavailable at its official authenticated endpoint (HTTP ${response.status})."
        return emptyList()
    }
    val catalog = runCatchingPreservingCancellation { dynamicJson.parseToJsonElement(response.body.decodeToString()) as? JsonArray }
        .getOrNull()
    if (catalog == null) {
        probe.record(DynamicServerProbeFailure.UnreadableResponse)
        diagnostics += "OCS API Viewer returned an invalid app catalog${response.contentTypeDiagnostic()}."
        return emptyList()
    }
    val appEntry = catalog
        .mapNotNull { it as? JsonObject }
        .firstOrNull { entry -> (entry["id"] as? JsonPrimitive)?.contentOrNull == appId }
    if (appEntry == null) {
        diagnostics += "OCS API Viewer does not advertise an OpenAPI specification for $appId."
        return emptyList()
    }
    val specs = (appEntry["specs"] as? JsonArray)
        .orEmpty()
        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        .filter { it.matches(Regex("[A-Za-z0-9_.-]+")) && (it == appId || it.startsWith("$appId-")) }
        .distinct()
        .sortedWith(compareBy<String> { it != appId }.thenBy { it })
    if (specs.isEmpty()) {
        diagnostics += "OCS API Viewer advertised $appId without a usable specification ID."
    }
    return specs
}

private fun dynamicDiscoveryRequest(path: String): NextcloudApiRequest = NextcloudApiRequest(
    method = NextcloudApiMethod.GET,
    relativePath = path,
    maximumResponseBytes = DEFAULT_DYNAMIC_API_RESPONSE_LIMIT_BYTES,
)

private fun NextcloudApiResponse.parseOpenApiDocument(): JsonObject? {
    val document = runCatching { dynamicJson.parseToJsonElement(body.decodeToString()) as? JsonObject }
        .getOrNull() ?: return null
    val version = (document["openapi"] as? JsonPrimitive)?.contentOrNull
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

private fun NextcloudApiResponse.contentTypeDiagnostic(): String =
    contentType?.let { " (Content-Type: $it)" } ?: " (without a Content-Type header)"

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
