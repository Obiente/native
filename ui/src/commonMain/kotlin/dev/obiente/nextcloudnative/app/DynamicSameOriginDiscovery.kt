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

    onProgress(
        DynamicDescriptorDiscoveryProgress(
            DynamicDescriptorDiscoveryPhase.ServerApiCatalog,
            "Checking the server API catalog",
        ),
    )
    val viewerSpecs = discoverOfficialViewerSpecIds(app.id, execute, diagnostics)
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
                diagnostics += "OCS API Viewer could not load $specId: ${failure.message ?: "request failed"}."
            }
            .getOrNull() ?: return@forEach
        if (response.status !in 200..299) {
            diagnostics += "OCS API Viewer could not load $specId (HTTP ${response.status})."
            return@forEach
        }
        val document = response.parseOpenApiDocument()
        if (document == null) {
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
        }.getOrNull() ?: return@forEach
        if (response.status !in 200..299 || response.contentType?.contains("json", ignoreCase = true) != true) {
            return@forEach
        }
        val document = response.parseOpenApiDocument() ?: return@forEach
        onProgress(
            DynamicDescriptorDiscoveryProgress(
                DynamicDescriptorDiscoveryPhase.NativeWorkspace,
                "Preparing the native workspace",
            ),
        )
        val descriptor = runCatchingPreservingCancellation {
            compileDynamicDescriptor(compiler, identity, policy, serverUrl, path, document)
        }.getOrNull() ?: return@forEach
        return DynamicDescriptorDiscovery(
            descriptor = descriptor,
            sourcePath = path,
            acquisition = DynamicDescriptorAcquisition.StaticAppAsset,
            diagnostics = diagnostics,
        )
    }

    diagnostics += "No valid static OpenAPI document was found in the app's advertised asset locations."

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
    )
}

private suspend fun discoverOfficialViewerSpecIds(
    appId: String,
    execute: suspend (NextcloudApiRequest) -> NextcloudApiResponse,
    diagnostics: MutableList<String>,
): List<String> {
    val response = runCatchingPreservingCancellation { execute(dynamicDiscoveryRequest(OCS_API_VIEWER_CATALOG_PATH)) }
        .onFailure { failure ->
            diagnostics += "OCS API Viewer is unavailable at its official authenticated endpoint: " +
                (failure.message ?: "request failed") + "."
        }
        .getOrNull() ?: return emptyList()
    if (response.status == 404) {
        diagnostics += "OCS API Viewer is not installed or enabled for this user (HTTP 404 at $OCS_API_VIEWER_CATALOG_PATH)."
        return emptyList()
    }
    if (response.status !in 200..299) {
        diagnostics += "OCS API Viewer is unavailable at its official authenticated endpoint (HTTP ${response.status})."
        return emptyList()
    }
    val catalog = runCatchingPreservingCancellation { dynamicJson.parseToJsonElement(response.body.decodeToString()) as? JsonArray }
        .getOrNull()
    if (catalog == null) {
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
