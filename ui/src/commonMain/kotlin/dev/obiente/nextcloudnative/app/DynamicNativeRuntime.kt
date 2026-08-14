package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.template.scanBracedTemplate

import dev.obiente.nextcloudnative.nativeui.model.AdvertisedOpenApi
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.AppIdentity
import dev.obiente.nextcloudnative.nativeui.model.AuthKind
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.DynamicAction
import dev.obiente.nextcloudnative.nativeui.model.DynamicAppDescriptor
import dev.obiente.nextcloudnative.nativeui.model.DYNAMIC_INTEGER_ARRAY_FORMAT
import dev.obiente.nextcloudnative.nativeui.model.DYNAMIC_STRING_ARRAY_FORMAT
import dev.obiente.nextcloudnative.nativeui.model.DYNAMIC_STRING_LIST_FORMAT
import dev.obiente.nextcloudnative.nativeui.model.DynamicIntegerArrayParseResult
import dev.obiente.nextcloudnative.nativeui.model.DynamicAppDescriptorCompiler
import dev.obiente.nextcloudnative.nativeui.model.DynamicDiscoveryInput
import dev.obiente.nextcloudnative.nativeui.model.DynamicHttpBinding
import dev.obiente.nextcloudnative.nativeui.model.EndpointPolicy
import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.HttpParameter
import dev.obiente.nextcloudnative.nativeui.model.OpenApiTrust
import dev.obiente.nextcloudnative.nativeui.model.ParameterSource
import dev.obiente.nextcloudnative.nativeui.model.ProvenanceKind
import dev.obiente.nextcloudnative.nativeui.model.isExactDynamicIntegerArraySchema
import dev.obiente.nextcloudnative.nativeui.model.parseDynamicIntegerArrayInput
import dev.obiente.nextcloudnative.nativeui.model.repeatableObjectInputSpec
import dev.obiente.nextcloudnative.nativeui.model.requireValid
import dev.obiente.nextcloudnative.nativeui.model.sameDynamicResourceAs
import dev.obiente.nextcloudnative.nativeui.runtime.NativeActionExecutionResult
import dev.obiente.nextcloudnative.nativeui.runtime.NativeActionExecutor
import dev.obiente.nextcloudnative.nativeui.runtime.NativeActionFailureOutcome
import dev.obiente.nextcloudnative.nativeui.runtime.NativeActionRequest
import dev.obiente.nextcloudnative.nativeui.runtime.NativeRecord
import dev.obiente.nextcloudnative.nativeui.runtime.NativeStructuredEntry
import dev.obiente.nextcloudnative.nativeui.runtime.NativeStructuredScalarKind
import dev.obiente.nextcloudnative.nativeui.runtime.NativeStructuredValue
import dev.obiente.nextcloudnative.nativeui.runtime.safeActionBindingValues
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.math.ceil
import kotlin.math.floor

private val dynamicJson = Json {
    ignoreUnknownKeys = true
    isLenient = false
    explicitNulls = false
}

@Serializable
data class DynamicDescriptorDiscovery(
    val descriptor: DynamicAppDescriptor,
    val sourcePath: String?,
    val acquisition: DynamicDescriptorAcquisition,
    val diagnostics: List<String> = emptyList(),
    val versionStatus: DynamicContractVersionStatus = DynamicContractVersionStatus.VerifiedCurrent,
)

@Serializable
enum class DynamicContractVersionStatus {
    VerifiedCurrent,
    LastKnownReadOnly,
}

internal fun DynamicContractVersionStatus.allows(risk: ActionRisk): Boolean =
    this == DynamicContractVersionStatus.VerifiedCurrent || risk == ActionRisk.readOnly

@Serializable
enum class DynamicDescriptorAcquisition {
    OcsApiViewer,
    StaticAppAsset,
    SignedAppStorePackage,
    SignedAppStoreStaticRoutes,
    SignedAppStoreMergedContract,
    AppStoreLinkedGitHubTag,
    AppStoreLinkedStaticRoutes,
    AppStoreLinkedMergedContract,
    MetadataFallback,
}

/**
 * Discovers a machine-readable contract shipped by an installed app.
 *
 * Static app assets are probed with GET only. A valid API contract or a verified read-only
 * contract derived from signed static route metadata is compiled locally. If neither exists,
 * discovery falls back honestly to the descriptor compiler's metadata-only result.
 */
suspend fun discoverDynamicAppDescriptor(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    app: NextcloudAppEntry,
    serverVersion: String? = null,
    installedAppVersionHint: String? = null,
    serverVersionVerified: Boolean = true,
): DynamicDescriptorDiscovery {
    val sameOrigin = discoverDynamicAppDescriptor(
        serverUrl = session.serverUrl,
        app = app,
        execute = { request -> services.executeNextcloudApi(session, request) },
    )
    if (sameOrigin.acquisition != DynamicDescriptorAcquisition.MetadataFallback) return sameOrigin
    val coreVersion = serverVersion?.coreVersionOrNull()
        ?: return sameOrigin.copy(
            diagnostics = sameOrigin.diagnostics +
                "The server version is unavailable, so an App Store release could not be selected safely.",
        )
    val contractAppId = app.canonicalAppStoreId()
    val observedInstalledVersion = discoverInstalledAppVersion(services, session, contractAppId)
    val installedVersion = observedInstalledVersion ?: installedAppVersionHint?.safeDynamicVersionHint()
    val versionStatus = if (serverVersionVerified && observedInstalledVersion != null) {
        DynamicContractVersionStatus.VerifiedCurrent
    } else {
        DynamicContractVersionStatus.LastKnownReadOnly
    }
    val acquired = runCatching {
        services.acquireSignedOpenApiContract(contractAppId, coreVersion, installedVersion)
    }.getOrElse { failure ->
        return sameOrigin.copy(
            diagnostics = sameOrigin.diagnostics +
                "App Store contract acquisition failed: ${failure.message ?: "verification failed"}.",
        )
    } ?: return sameOrigin.copy(
        diagnostics = sameOrigin.diagnostics +
            "No exact or patch-compatible App Store source yielded a usable API contract or " +
            "verified static read routes for $contractAppId on Nextcloud $coreVersion. " +
            "Only app metadata is available.",
    )
    val document = runCatching {
        dynamicJson.parseToJsonElement(acquired.document) as? JsonObject
    }.getOrNull() ?: return sameOrigin.copy(
        diagnostics = sameOrigin.diagnostics + "The acquired App Store contract contained invalid JSON.",
    )
    val verifiedReadRouteCount = (document["x-nextcloud-native-verified-read-route-count"] as? JsonPrimitive)
        ?.contentOrNull
        ?.toIntOrNull()
    val approvedAppIds = listOf(app.id, contractAppId).distinct()
    val policy = EndpointPolicy(
        serverOrigin = session.serverUrl.httpOrigin(),
        approvedApiPrefixes = approvedAppIds.flatMap { appId ->
            listOf(
                "/apps/$appId",
                "/ocs/v1.php/apps/$appId",
                "/ocs/v2.php/apps/$appId",
                "/index.php/apps/$appId",
            )
        } + "/ocs/v2.php/cloud/capabilities",
    )
    val source = acquired.sourceUrl
    val trust = when (acquired.sourceKind) {
        AcquiredOpenApiContractSourceKind.SignedAppPackage -> OpenApiTrust.nextcloudSignedAppPackage
        AcquiredOpenApiContractSourceKind.SignedCompatibleAppPackage ->
            OpenApiTrust.nextcloudSignedCompatibleAppPackage
        AcquiredOpenApiContractSourceKind.AppStoreLinkedExactGitHubTag ->
            OpenApiTrust.appStoreLinkedExactGitHubTag
        AcquiredOpenApiContractSourceKind.AppStoreLinkedCompatibleGitHubTag ->
            OpenApiTrust.appStoreLinkedCompatibleGitHubTag
    }
    val descriptor = runCatching {
        DynamicAppDescriptorCompiler().compile(
            DynamicDiscoveryInput(
                app = AppIdentity(app.id, app.name, acquired.appVersion),
                endpointPolicy = policy,
                advertisedOpenApi = AdvertisedOpenApi(
                    documentUrl = source,
                    document = document,
                    trust = trust,
                ),
            ),
        ).requireValid()
    }.getOrElse { failure ->
        return sameOrigin.copy(
            diagnostics = sameOrigin.diagnostics +
                "The acquired App Store contract could not be compiled into a native read surface: " +
                "${failure.message ?: "unsupported contract"}.",
        )
    }
    return DynamicDescriptorDiscovery(
        descriptor = descriptor,
        sourcePath = source,
        acquisition = when {
            acquired.contractKind == AcquiredContractKind.VerifiedReadRoutes && acquired.sourceKind.isSignedPackage() ->
                DynamicDescriptorAcquisition.SignedAppStoreStaticRoutes
            acquired.contractKind == AcquiredContractKind.VerifiedReadRoutes ->
                DynamicDescriptorAcquisition.AppStoreLinkedStaticRoutes
            acquired.contractKind == AcquiredContractKind.OpenApiWithVerifiedReadRoutes &&
                acquired.sourceKind.isSignedPackage() -> DynamicDescriptorAcquisition.SignedAppStoreMergedContract
            acquired.contractKind == AcquiredContractKind.OpenApiWithVerifiedReadRoutes ->
                DynamicDescriptorAcquisition.AppStoreLinkedMergedContract
            acquired.sourceKind == AcquiredOpenApiContractSourceKind.SignedAppPackage ->
                DynamicDescriptorAcquisition.SignedAppStorePackage
            acquired.sourceKind == AcquiredOpenApiContractSourceKind.SignedCompatibleAppPackage ->
                DynamicDescriptorAcquisition.SignedAppStorePackage
            acquired.sourceKind == AcquiredOpenApiContractSourceKind.AppStoreLinkedExactGitHubTag ->
                DynamicDescriptorAcquisition.AppStoreLinkedGitHubTag
            else -> DynamicDescriptorAcquisition.AppStoreLinkedGitHubTag
        },
        diagnostics = sameOrigin.diagnostics +
            acquired.successDiagnostic(
                app = app,
                endpointCount = descriptor.actions.size,
                verifiedReadRouteCount = verifiedReadRouteCount,
            ) +
            if (versionStatus == DynamicContractVersionStatus.LastKnownReadOnly) {
                listOf(
                    "The cached contract uses the last verified server or app version. " +
                        "Reads remain available, but writes are disabled until both versions are verified again.",
                )
            } else {
                emptyList()
            },
        versionStatus = versionStatus,
    )
}

internal fun DynamicDescriptorAcquisition.usesAppStoreContract(): Boolean = when (this) {
    DynamicDescriptorAcquisition.SignedAppStorePackage,
    DynamicDescriptorAcquisition.SignedAppStoreStaticRoutes,
    DynamicDescriptorAcquisition.SignedAppStoreMergedContract,
    DynamicDescriptorAcquisition.AppStoreLinkedGitHubTag,
    DynamicDescriptorAcquisition.AppStoreLinkedStaticRoutes,
    DynamicDescriptorAcquisition.AppStoreLinkedMergedContract,
    -> true
    DynamicDescriptorAcquisition.OcsApiViewer,
    DynamicDescriptorAcquisition.StaticAppAsset,
    DynamicDescriptorAcquisition.MetadataFallback,
    -> false
}

private fun String.safeDynamicVersionHint(): String? = trim()
    .takeIf { version ->
        version.length in 1..MAX_DYNAMIC_VERSION_HINT_CHARACTERS &&
            version.all { character ->
                character.isLetterOrDigit() || character in setOf('.', '-', '_', '+')
            }
    }

private fun AcquiredOpenApiContractSourceKind.isSignedPackage(): Boolean =
    this == AcquiredOpenApiContractSourceKind.SignedAppPackage ||
        this == AcquiredOpenApiContractSourceKind.SignedCompatibleAppPackage

private fun AcquiredOpenApiContract.successDiagnostic(
    app: NextcloudAppEntry,
    endpointCount: Int,
    verifiedReadRouteCount: Int?,
): String = when (contractKind) {
    AcquiredContractKind.VerifiedReadRoutes -> {
        val readCount = verifiedReadRouteCount ?: endpointCount
        when (sourceKind) {
            AcquiredOpenApiContractSourceKind.SignedAppPackage ->
                "Verified ${app.id} $appVersion and derived $readCount read-only endpoints from " +
                    "$specFile in its signed App Store package. No writes were inferred."
            AcquiredOpenApiContractSourceKind.SignedCompatibleAppPackage ->
                "Verified signed patch-compatible ${app.id} $contractVersion for installed $appVersion and " +
                    "derived $readCount read-only endpoints from $specFile. No writes were inferred."
            AcquiredOpenApiContractSourceKind.AppStoreLinkedExactGitHubTag ->
                "Derived $readCount read-only endpoints from $specFile in the exact App Store-linked " +
                    "source tag for ${app.id} $appVersion. No writes were inferred."
            AcquiredOpenApiContractSourceKind.AppStoreLinkedCompatibleGitHubTag ->
                "Derived $readCount read-only endpoints from $specFile in an App Store-linked " +
                    "patch-compatible source tag for ${app.id} $contractVersion. No writes were inferred."
        }
    }
    AcquiredContractKind.OpenApiWithVerifiedReadRoutes -> {
        val readCount = verifiedReadRouteCount ?: 0
        val sourceLabel = if (sourceKind.isSignedPackage()) "signed App Store package" else "App Store-linked source tag"
        "Imported API contract $specFile from the $sourceLabel and added $readCount verified read " +
            "fallback routes for ${app.id} $appVersion. Documented API operations remain primary; no writes were inferred."
    }
    AcquiredContractKind.OpenApi -> when (sourceKind) {
        AcquiredOpenApiContractSourceKind.SignedAppPackage ->
            "Verified ${app.id} $appVersion and imported API contract $specFile from its signed App Store package."
        AcquiredOpenApiContractSourceKind.SignedCompatibleAppPackage ->
            "Verified and imported API contract $specFile from signed patch-compatible ${app.id} " +
                "$contractVersion for installed $appVersion."
        AcquiredOpenApiContractSourceKind.AppStoreLinkedExactGitHubTag ->
            "Imported API contract $specFile from the exact GitHub release tag linked by the App Store " +
                "after matching ${app.id} $appVersion. This source is not covered by the package signature."
        AcquiredOpenApiContractSourceKind.AppStoreLinkedCompatibleGitHubTag ->
            "Imported API contract $specFile from App Store-linked patch-compatible ${app.id} " +
                "$contractVersion for installed $appVersion. This source is not covered by the package signature."
    }
}

internal fun NextcloudAppEntry.canonicalAppStoreId(): String {
    val path = href?.substringBefore('?')?.substringBefore('#') ?: return id
    val segments = path.split('/').filter(String::isNotBlank)
    val appsIndex = segments.indexOfLast { segment -> segment == "apps" }
    val candidate = segments.getOrNull(appsIndex + 1) ?: return id
    return candidate.takeIf { value ->
        value.isNotBlank() && value != "." && value != ".." && '%' !in value && value.all { character ->
            character.isLetterOrDigit() || character == '_' || character == '.' || character == '-'
        }
    } ?: id
}

private suspend fun discoverInstalledAppVersion(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    appId: String,
): String? = runCatching {
    val response = services.executeNextcloudApi(
        session,
        NextcloudApiRequest(
            method = NextcloudApiMethod.GET,
            relativePath = "/ocs/v2.php/cloud/apps/$appId",
            queryParameters = mapOf("format" to "json"),
            ocsApiRequest = true,
        ),
    )
    if (response.status !in 200..299) return@runCatching null
    val root = dynamicJson.parseToJsonElement(response.body.decodeToString()) as? JsonObject
    val ocs = root?.get("ocs") as? JsonObject
    val data = ocs?.get("data") as? JsonObject
    (data?.get("version") as? JsonPrimitive)?.contentOrNull
}.getOrNull()

private fun String.coreVersionOrNull(): String? =
    Regex("([0-9]+)\\.([0-9]+)\\.([0-9]+)").find(this)?.value

internal suspend fun discoverDynamicAppDescriptor(
    serverUrl: String,
    app: NextcloudAppEntry,
    execute: suspend (NextcloudApiRequest) -> NextcloudApiResponse,
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

    val viewerSpecs = discoverOfficialViewerSpecIds(app.id, execute, diagnostics)
    viewerSpecs.forEach { specId ->
        val path = "$OCS_API_VIEWER_SPEC_PATH/${specId.encodeUrlComponent()}"
        val response = runCatching { execute(dynamicDiscoveryRequest(path)) }
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
        val descriptor = runCatching {
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
    staticCandidates.forEach { path ->
        val response = runCatching {
            execute(dynamicDiscoveryRequest(path))
        }.getOrNull() ?: return@forEach
        if (response.status !in 200..299 || response.contentType?.contains("json", ignoreCase = true) != true) {
            return@forEach
        }
        val document = response.parseOpenApiDocument() ?: return@forEach
        val descriptor = runCatching {
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
    val response = runCatching { execute(dynamicDiscoveryRequest(OCS_API_VIEWER_CATALOG_PATH)) }
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
    val catalog = runCatching { dynamicJson.parseToJsonElement(response.body.decodeToString()) as? JsonArray }
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

class DynamicNextcloudActionExecutor(
    private val services: NextcloudPlatformServices,
    private val session: NextcloudSession,
    private val descriptor: DynamicAppDescriptor,
    private val runtimeContext: Map<String, String> = emptyMap(),
    private val versionStatus: DynamicContractVersionStatus = DynamicContractVersionStatus.VerifiedCurrent,
    private val onMultipartUploadSucceeded: (LocalUploadFile) -> Unit = {},
) : NativeActionExecutor {
    init {
        descriptor.requireValid()
    }

    override suspend fun execute(request: NativeActionRequest): NativeActionExecutionResult {
        val action = descriptor.actions.firstOrNull { it.id == request.action.id }
            ?: return NativeActionExecutionResult.Failure(
                message = "The dynamic action is no longer available.",
                outcome = NativeActionFailureOutcome.Rejected,
            )
        if (!versionStatus.allows(action.risk)) {
            return NativeActionExecutionResult.Failure(
                message = "Reconnect to verify the server and app versions before changing cloud data.",
                outcome = NativeActionFailureOutcome.Rejected,
            )
        }
        val values = (request as? NativeActionRequest.Submit)?.values.orEmpty()
        val observedInputSchema = (request as? NativeActionRequest.Submit)
            ?.action
            ?.takeIf { submitted ->
                submitted.id == action.id &&
                    submitted.binding.allowsObservedBodyFields &&
                    action.binding.body?.schema.observesSettingsBody()
            }
            ?.inputSchema
        if (request is NativeActionRequest.Submit && action.requiresConfirmation && !request.confirmed) {
            return NativeActionExecutionResult.Failure(
                message = "Confirm this action before changing server data.",
                outcome = NativeActionFailureOutcome.Rejected,
            )
        }
        return runCatching {
            val execution = executeDynamicAction(
                services,
                session,
                descriptor,
                action,
                values,
                runtimeContext,
                observedInputSchema,
            )
            execution.response.toDynamicActionExecutionResult(action).also { result ->
                releaseMultipartUploadAfterSuccess(
                    result = result,
                    file = execution.multipartFile,
                    release = onMultipartUploadSucceeded,
                )
            }
        }.getOrElse { failure ->
            NativeActionExecutionResult.Failure(failure.message ?: "The dynamic action failed.")
        }
    }
}

/**
 * Validates a mutation response against the action's declared response envelope.
 *
 * OCS endpoints can report an application failure inside an HTTP 2xx response. A declared OCS
 * mutation therefore succeeds only when its bounded JSON metadata contains both an `ok` status
 * and a successful OCS status code. Missing, malformed, or contradictory metadata fails closed
 * without exposing the response body.
 */
internal fun NextcloudApiResponse.toDynamicActionExecutionResult(
    action: DynamicAction,
): NativeActionExecutionResult {
    if (status !in 200..299) {
        return NativeActionExecutionResult.Failure(
            message = "The server rejected ${action.label} (HTTP $status).",
            outcome = if (status in 400..499) {
                NativeActionFailureOutcome.Rejected
            } else {
                NativeActionFailureOutcome.Unknown
            },
        )
    }
    val ocs = action.binding.ocs
        ?: return NativeActionExecutionResult.Success("${action.label} completed.")
    val metadata = runCatching {
        body.parseBoundedDynamicJson().atJsonPointer(ocs.responseMetaPointer)
    }.getOrNull() as? JsonObject
        ?: return malformedDynamicOcsActionResult(action)
    val ocsStatus = (metadata["status"] as? JsonPrimitive)
        ?.contentOrNull
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: return malformedDynamicOcsActionResult(action)
    val statusCode = (metadata["statuscode"] as? JsonPrimitive)?.longOrNull
        ?: return malformedDynamicOcsActionResult(action)
    val statusIsSuccessful = ocsStatus.equals("ok", ignoreCase = true)
    val codeIsSuccessful = statusCode == 100L || statusCode in 200L..299L
    if (statusIsSuccessful && codeIsSuccessful) {
        return NativeActionExecutionResult.Success("${action.label} completed.")
    }
    val safeMessage = (metadata["message"] as? JsonPrimitive)
        ?.contentOrNull
        ?.toSafeDynamicErrorMessage()
    return NativeActionExecutionResult.Failure(
        message = safeMessage?.let { message -> "The server rejected ${action.label}: $message" }
            ?: "The OCS endpoint rejected ${action.label}.",
        outcome = NativeActionFailureOutcome.Rejected,
    )
}

private fun malformedDynamicOcsActionResult(
    action: DynamicAction,
): NativeActionExecutionResult.Failure = NativeActionExecutionResult.Failure(
    "The server returned invalid OCS metadata for ${action.label}.",
)

suspend fun loadDynamicRecords(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    descriptor: DynamicAppDescriptor,
    actionId: String,
    values: Map<String, String> = emptyMap(),
    runtimeContext: Map<String, String> = emptyMap(),
    cachePolicy: NextcloudApiCachePolicy = NextcloudApiCachePolicy.PreferCache,
): List<NativeRecord> {
    val action = descriptor.actions.firstOrNull { it.id == actionId }
        ?: error("This view has no declared load action.")
    val bindingContext = dynamicReadBindingContext(action, values, runtimeContext)
    return executeDynamicReadWithFallback(
        descriptor = descriptor,
        actionId = actionId,
        execute = { candidate ->
            val candidateValues = remapReadFallbackValues(action, candidate, values)
            val request = buildDynamicApiRequest(
                descriptor = descriptor,
                action = candidate,
                values = candidateValues,
                runtimeContext = runtimeContext + candidateValues,
            ).copy(cachePolicy = cachePolicy)
            services.executeNextcloudApi(session, request).also { response ->
                if (response.status !in 200..299) {
                    throw response.toDynamicReadLoadException(candidate, request.relativePath)
                }
            }
        },
    ).map { record ->
        if (bindingContext.isEmpty()) {
            record
        } else {
            val mergedBindingContext = safeActionBindingValues(record.bindingContext, bindingContext)
            record.copy(
                bindingContext = mergedBindingContext.orEmpty(),
                actionBindingProvenanceValid =
                    record.actionBindingProvenanceValid && mergedBindingContext != null,
            )
        }
    }
}

/**
 * Retains the exact request identities that scoped a dynamic read without conflating a collection
 * parent with each returned record's own identity.
 *
 * Some contracts expose a nested collection at a route such as `/houses/{id}/categories`. The
 * request must still bind the literal `id` parameter, but that value identifies the parent house,
 * not a returned category. Requalify it only when the same trusted response schema declares
 * exactly one matching parent identity such as `houseId`. Otherwise the generic key is retained
 * and downstream mutation/relationship planners continue to fail closed on any identity conflict.
 */
internal fun dynamicReadBindingContext(
    action: DynamicAction,
    values: Map<String, String>,
    runtimeContext: Map<String, String>,
): Map<String, String> = (
    action.binding.pathParameters.map { parameter -> parameter to true } +
        action.binding.queryParameters.map { parameter -> parameter to false }
    )
    .asSequence()
    .mapNotNull { (parameter, pathParameter) ->
        val value = values[parameter.name] ?: runtimeContext[parameter.name]
        value?.takeIf(String::isNotBlank)?.let {
            action.recordBindingContextName(parameter, pathParameter) to it
        }
    }
    .distinctBy { (name, _) -> name.lowercase() }
    .take(MAX_DYNAMIC_BINDING_CONTEXT_VALUES)
    .toMap()

private fun DynamicAction.recordBindingContextName(
    parameter: HttpParameter,
    pathParameter: Boolean,
): String {
    if (!pathParameter || !parameter.name.equals("id", ignoreCase = true)) return parameter.name
    val segments = binding.path.substringBefore('?').split('/').filter(String::isNotBlank)
    val placeholder = "{${parameter.name}}"
    val indices = segments.indices.filter { index -> segments[index].equals(placeholder, ignoreCase = true) }
    val parameterIndex = indices.singleOrNull()
        ?.takeIf { index -> index > 0 && index < segments.lastIndex }
        ?: return parameter.name
    val parentRouteResource = segments[parameterIndex - 1].takeIf { segment ->
        segment.none { character -> character in "{}" } &&
            segment.any(Char::isLetterOrDigit)
    } ?: return parameter.name
    val targetsReturnedCollection = segments.drop(parameterIndex + 1).any { segment ->
        segment.none { character -> character in "{}" } &&
            segment.sameDynamicResourceAs(resourceId)
    }
    if (!targetsReturnedCollection) return parameter.name
    return responseFieldIds.singleOrNull { fieldId ->
        fieldId.length > 2 &&
            fieldId.endsWith("Id", ignoreCase = true) &&
            fieldId.dropLast(2).sameDynamicResourceAs(parentRouteResource)
    } ?: parameter.name
}

/**
 * Equivalent verified reads sometimes rename a single parent identity (`id` versus
 * `mailboxId`) while moving it between path and query. Contract acquisition proves the fallback
 * relationship; this function only carries the already-bound value to the fallback's exact name.
 */
internal fun remapReadFallbackValues(
    preferred: DynamicAction,
    candidate: DynamicAction,
    values: Map<String, String>,
): Map<String, String> {
    if (!candidate.fallbackOnly || candidate.id !in preferred.fallbackActionIds) return values
    val preferredRequired = preferred.binding.requiredReadParameters()
    val candidateRequired = candidate.binding.requiredReadParameters()
    if (preferredRequired.size != candidateRequired.size) return values

    val result = values.toMutableMap()
    val unmatchedPreferred = preferredRequired.toMutableList()
    candidateRequired.forEach { target ->
        if (result.keys.any { key -> key.equals(target.name, ignoreCase = true) }) {
            unmatchedPreferred.removeAll { source -> source.name.equals(target.name, ignoreCase = true) }
            return@forEach
        }
        val exact = unmatchedPreferred.firstOrNull { source -> source.name.equals(target.name, ignoreCase = true) }
        val source = exact ?: unmatchedPreferred.singleOrNull()?.takeIf { candidateRequired.size == 1 }
            ?: return@forEach
        val sourceValue = values.entries.firstOrNull { (key, _) -> key.equals(source.name, ignoreCase = true) }
            ?.value
            ?.takeIf(String::isNotBlank)
            ?: return@forEach
        result[target.name] = sourceValue
        unmatchedPreferred.remove(source)
    }
    return result
}

private fun DynamicHttpBinding.requiredReadParameters(): List<HttpParameter> =
    (pathParameters + queryParameters.filter(HttpParameter::required)).distinctBy { parameter ->
        parameter.name.lowercase()
    }

internal suspend fun executeDynamicReadWithFallback(
    descriptor: DynamicAppDescriptor,
    actionId: String,
    execute: suspend (DynamicAction) -> NextcloudApiResponse,
): List<NativeRecord> {
    val actionsById = descriptor.actions.associateBy(DynamicAction::id)
    val preferred = actionsById[actionId] ?: error("This view has no declared load action.")
    require(preferred.binding.method == HttpMethod.GET && !preferred.fallbackOnly) {
        "Only declared preferred GET actions can load a dynamic view."
    }
    val candidates = listOf(preferred) + preferred.fallbackActionIds.mapNotNull(actionsById::get)
    var bestFailure: Throwable? = null
    var bestFailureSpecificity = -1
    var successfulEmptyResult: List<NativeRecord>? = null
    candidates.forEach { candidate ->
        if (candidate.binding.method != HttpMethod.GET) return@forEach
        val records = runCatching {
            val parsingAction = if (candidate.id == preferred.id) {
                candidate
            } else {
                candidate.copy(
                    label = preferred.label,
                    resourceId = preferred.resourceId,
                    intent = preferred.intent,
                )
            }
            parseDynamicRecords(
                action = parsingAction,
                response = execute(candidate),
                declaredFieldIds = candidate.responseFieldIds.toSet(),
                preferredIdentityFieldId = descriptor.verifiedRecordIdentityFieldId(candidate),
            )
        }.onFailure { failure ->
            val specificity = (failure as? DynamicReadLoadException)?.specificity ?: 0
            if (bestFailure == null || specificity > bestFailureSpecificity) {
                bestFailure = failure
                bestFailureSpecificity = specificity
            }
        }.getOrNull() ?: return@forEach
        if (records.isNotEmpty()) return records
        successfulEmptyResult = records
    }
    successfulEmptyResult?.let { return it }
    throw bestFailure ?: error("No usable declared read action was available.")
}

internal fun parseDynamicRecords(
    action: DynamicAction,
    response: NextcloudApiResponse,
    declaredFieldIds: Set<String> = emptySet(),
    preferredIdentityFieldId: String? = null,
): List<NativeRecord> {
    if (response.status !in 200..299) throw response.toDynamicReadLoadException(action)
    if (response.status == 204) return emptyList()
    check(response.contentType?.contains("json", ignoreCase = true) == true) {
        "The dynamic endpoint did not return JSON."
    }
    val parsed = response.body.parseBoundedDynamicJson()
    val payload = action.binding.ocs?.let { ocs ->
        parsed.atJsonPointer(ocs.responseMetaPointer).requireSuccessfulOcsResponse()
        parsed.atJsonPointer(ocs.responseDataPointer)
    } ?: parsed
    return payload.toNativeRecords(
        mapCollectionCandidate = action.intent == dev.obiente.nextcloudnative.nativeui.model.ActionIntent.list,
        declaredFieldIds = declaredFieldIds,
        collectionNameHints = action.dynamicCollectionNameHints(),
        preferredIdentityFieldId = preferredIdentityFieldId,
    )
}

private fun DynamicAppDescriptor.verifiedRecordIdentityFieldId(action: DynamicAction): String? {
    if (
        app.id != "chores" || app.version != "0.1.0" ||
        action.binding.method != HttpMethod.GET ||
        action.binding.path != "/apps/chores/api/v1.0/account/invites" ||
        action.confidence != Confidence.verified ||
        action.provenance.none { provenance -> provenance.kind == ProvenanceKind.verifiedAppPackage } ||
        action.responseFieldIds.count { fieldId -> fieldId == "inviteId" } != 1 ||
        actions.count { candidate -> candidate.id == action.id } != 1
    ) {
        return null
    }
    return "inviteId"
}

private fun DynamicAction.dynamicCollectionNameHints(): Set<String> =
    listOf(resourceId, label)
        .flatMap { value ->
            value.lowercase()
                .map { character -> if (character.isLetterOrDigit()) character else ' ' }
                .joinToString("")
                .split(' ')
        }
        .filter { word -> word.length > 2 && word !in setOf("get", "list", "read") }
        .toSet()

/**
 * Rejects hostile response shape before kotlinx.serialization recursively materializes it.
 *
 * Platform transports already apply the same byte limit, but this parser is also called by
 * caches, fixtures, and future transports. The lexical depth scan is intentionally independent of
 * JSON validity: the normal parser still owns syntax validation after the cheap safety check.
 */
private fun ByteArray.parseBoundedDynamicJson(): JsonElement {
    check(size.toLong() <= DEFAULT_DYNAMIC_API_RESPONSE_LIMIT_BYTES) {
        "The dynamic endpoint returned too much JSON."
    }
    check(hasBoundedDynamicJsonDepth()) {
        "The dynamic endpoint returned JSON nested too deeply."
    }
    val text = runCatching { decodeToString(throwOnInvalidSequence = true) }
        .getOrElse { throw IllegalStateException("The dynamic endpoint returned invalid UTF-8 JSON.") }
    return runCatching { dynamicJson.parseToJsonElement(text) }
        .getOrElse { throw IllegalStateException("The dynamic endpoint returned malformed JSON.") }
}

private fun ByteArray.hasBoundedDynamicJsonDepth(): Boolean {
    var depth = 0
    var inString = false
    var escaped = false
    forEach { byte ->
        val character = (byte.toInt() and 0xff).toChar()
        if (inString) {
            when {
                escaped -> escaped = false
                character == '\\' -> escaped = true
                character == '"' -> inString = false
            }
        } else {
            when (character) {
                '"' -> inString = true
                '{', '[' -> {
                    depth += 1
                    if (depth > MAX_DYNAMIC_JSON_DEPTH) return false
                }
                '}', ']' -> depth = (depth - 1).coerceAtLeast(0)
            }
        }
    }
    return true
}

private class DynamicReadLoadException(
    message: String,
    val specificity: Int,
) : IllegalStateException(message)

private fun NextcloudApiResponse.toDynamicReadLoadException(
    action: DynamicAction,
    resolvedPath: String? = null,
): DynamicReadLoadException {
    val serverMessage = body.decodeToString()
        .take(MAX_DYNAMIC_ERROR_BODY_CHARS)
        .let { raw -> runCatching { dynamicJson.parseToJsonElement(raw) }.getOrNull() }
        ?.findDynamicErrorMessage()
        ?.toSafeDynamicErrorMessage()
    val resourceLabel = action.resourceId.substringAfterLast('.')
        .map { character -> if (character.isLetterOrDigit()) character else ' ' }
        .joinToString("")
        .trim()
        .replaceFirstChar { character -> character.uppercase() }
        .ifBlank { "this view" }
    val message = if (serverMessage != null) {
        "Could not load $resourceLabel: $serverMessage"
    } else {
        // Keep the template, method and status visible so a rejected dynamic
        // route can be corrected from the contract instead of being mistaken
        // for a general account-authentication failure. Parameter values are
        // intentionally not included.
        "Could not load $resourceLabel (HTTP $status ${action.binding.method} " +
            (resolvedPath ?: action.binding.path) + ")."
    }
    return DynamicReadLoadException(message, specificity = if (serverMessage == null) 1 else 2)
}

private fun JsonElement.findDynamicErrorMessage(): String? {
    val root = this as? JsonObject ?: return null
    val candidates = listOf(
        root["message"],
        (root["data"] as? JsonObject)?.get("message"),
        (root["error"] as? JsonObject)?.get("message"),
        ((root["ocs"] as? JsonObject)?.get("meta") as? JsonObject)?.get("message"),
    )
    return candidates.firstNotNullOfOrNull { candidate ->
        (candidate as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
    }
}

private fun String.toSafeDynamicErrorMessage(): String? {
    val compact = trim().replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')
        .split(' ').filter(String::isNotBlank).joinToString(" ")
        .take(MAX_DYNAMIC_ERROR_MESSAGE_CHARS)
    if (compact.isBlank() || compact.any(Char::isISOControl) || '<' in compact || '>' in compact) return null
    val normalized = compact.lowercase()
    if (normalized == "internal server error" || normalized == "error" || normalized == "failure") return null
    if (normalized.startsWith("mailbox ") && normalized.endsWith(" is not cached")) {
        return "This mailbox has not been synchronized on the server yet."
    }
    return compact.removeSuffix(".") + "."
}

private data class DynamicActionExecution(
    val response: NextcloudApiResponse,
    val multipartFile: LocalUploadFile?,
)

internal fun releaseMultipartUploadAfterSuccess(
    result: NativeActionExecutionResult,
    file: LocalUploadFile?,
    release: (LocalUploadFile) -> Unit,
) {
    if (result is NativeActionExecutionResult.Success && file != null) release(file)
}

private suspend fun executeDynamicAction(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    descriptor: DynamicAppDescriptor,
    action: DynamicAction,
    values: Map<String, String>,
    runtimeContext: Map<String, String>,
    observedInputSchema: JsonElement? = null,
): DynamicActionExecution {
    val request = buildDynamicApiRequest(
        descriptor,
        action,
        values,
        runtimeContext,
        observedInputSchema,
    )
    return DynamicActionExecution(
        response = services.executeNextcloudApi(session, request),
        multipartFile = request.multipartBody?.file,
    )
}

internal fun buildDynamicApiRequest(
    descriptor: DynamicAppDescriptor,
    action: DynamicAction,
    values: Map<String, String>,
    runtimeContext: Map<String, String> = emptyMap(),
    observedInputSchema: JsonElement? = null,
): NextcloudApiRequest {
    descriptor.requireValid()
    require(action in descriptor.actions) { "The action is not part of the validated descriptor." }
    require(action.binding.auth.all { requirement ->
        requirement.kind == AuthKind.nextcloudSession || requirement.kind == AuthKind.basic
    }) { "This action requires an authentication scheme the app does not support yet." }
    val binding = action.binding
    val path = bindDynamicPath(binding, values, runtimeContext, action.resourceId)
    require(descriptor.endpointPolicy.approvedApiPrefixes.any { prefix -> path.matchesApiPrefix(prefix) }) {
        "The action endpoint is outside the approved app API prefixes."
    }
    val query = buildDynamicQuery(
        binding = binding,
        values = values,
        runtimeContext = runtimeContext,
        actionResourceId = action.resourceId,
        collectionRead = action.intent == dev.obiente.nextcloudnative.nativeui.model.ActionIntent.list,
    ).toMutableMap()
    binding.ocs?.formatQueryParameter?.takeIf(String::isNotBlank)?.let { query.putIfAbsent(it, "json") }
    val normalizedBodyContentType = binding.body?.contentType
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase()
    val multipartBody = binding.body
        ?.takeIf { normalizedBodyContentType == "multipart/form-data" }
        ?.let { declaredBody -> buildDynamicMultipartBody(declaredBody.schema, values) }
    val body = binding.body
        ?.takeUnless { normalizedBodyContentType == "multipart/form-data" }
        ?.let { declaredBody ->
            buildDynamicBody(declaredBody.contentType, declaredBody.schema, values, observedInputSchema)
        }
    return NextcloudApiRequest(
        method = binding.method.toTransportMethod(),
        relativePath = path,
        queryParameters = query,
        contentType = binding.body?.contentType?.takeUnless {
            normalizedBodyContentType == "multipart/form-data"
        },
        body = body,
        multipartBody = multipartBody,
        // Nextcloud's OCS controllers reject requests without this marker. A
        // few app specs omit the header from an individual operation even
        // though the validated route is still under /ocs/. Treat the path as a
        // protocol-level signal so read/detail routes do not fail with a
        // misleading 401 while sibling operations work.
        ocsApiRequest = binding.apiRequestHeader ||
            binding.ocs?.apiRequestHeader == true ||
            path.startsWith("/ocs/", ignoreCase = true),
    ).requireSafe()
}

private fun buildDynamicMultipartBody(
    schema: JsonElement,
    values: Map<String, String>,
): NextcloudMultipartBody {
    val objectSchema = schema as? JsonObject
        ?: error("Only object-shaped multipart request bodies are supported.")
    require((objectSchema["type"] as? JsonPrimitive)?.contentOrNull == "object") {
        "Only exact object-shaped multipart request bodies are supported."
    }
    val properties = objectSchema["properties"] as? JsonObject
        ?: error("A multipart request must declare its fields.")
    require(properties.size <= MAX_DYNAMIC_MULTIPART_FIELDS) {
        "The multipart request declares too many fields."
    }
    val fileFields = properties.entries.filter { (_, element) ->
        (element as? JsonObject)?.let { property ->
            property["type"]?.let { it as? JsonPrimitive }?.contentOrNull == "string" &&
                property["format"]?.let { it as? JsonPrimitive }?.contentOrNull == "binary"
        } == true
    }
    require(fileFields.size == 1) {
        "A dynamic multipart request must declare exactly one binary file field."
    }
    val fileFieldName = fileFields.single().key
    require(fileFieldName.isSafeDynamicMultipartFieldName()) {
        "The multipart file field name is invalid."
    }
    val required = (objectSchema["required"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        .orEmpty()
    require(required.all(properties::containsKey)) {
        "The multipart request has invalid required fields."
    }
    require(fileFieldName in required) {
        "Optional multipart file fields are not supported."
    }
    required.forEach { property ->
        require(!values[property].isNullOrBlank()) { "$property is required." }
    }
    val file = decodeDynamicLocalUploadSelection(
        values[fileFieldName]?.takeIf(String::isNotBlank)
            ?: error("$fileFieldName is required."),
    )
    val textFields = properties.entries
        .asSequence()
        .filter { (name, _) -> name != fileFieldName }
        .mapNotNull { (name, element) ->
            require(name.isSafeDynamicMultipartFieldName()) {
                "The multipart text field name is invalid."
            }
            val property = element as? JsonObject
                ?: error("Multipart fields require exact scalar schemas.")
            val type = (property["type"] as? JsonPrimitive)?.contentOrNull
            require(type in DYNAMIC_MULTIPART_SCALAR_TYPES) {
                "Multipart arrays and objects require an exact encoding contract."
            }
            val value = values[name]
            if (value.isNullOrBlank() && name !in required) {
                null
            } else {
                value.orEmpty().requireDynamicMultipartScalar(type)
                MultipartTextField(name, value.orEmpty())
            }
        }
        .toList()
    return NextcloudMultipartBody(
        file = file,
        fileFieldName = fileFieldName,
        textFields = textFields,
    ).requireSafe()
}

private fun String.requireDynamicMultipartScalar(type: String?) {
    when (type) {
        "boolean" -> require(toBooleanStrictOrNull() != null) { "Enter true or false." }
        "integer" -> require(toLongOrNull() != null) { "Enter a whole number." }
        "number" -> require(toDoubleOrNull()?.isFinite() == true) { "Enter a valid number." }
    }
}

private fun String.isSafeDynamicMultipartFieldName(): Boolean =
    length in 1..64 &&
        first().let { it in 'A'..'Z' || it in 'a'..'z' } &&
        all { character ->
            character in 'A'..'Z' ||
                character in 'a'..'z' ||
                character.isDigit() ||
                character in "_.-"
        }

/** Builds a recovery request only from a verified signed refresh/sync operation in the descriptor. */
internal fun buildDynamicRefreshRecoveryRequest(
    descriptor: DynamicAppDescriptor,
    values: Map<String, String>,
): NextcloudApiRequest? {
    val safeKinds = setOf(
        dev.obiente.nextcloudnative.nativeui.model.ProvenanceKind.verifiedAppPackage,
        dev.obiente.nextcloudnative.nativeui.model.ProvenanceKind.appStoreLinkedSourceTag,
    )
    return descriptor.actions.asSequence()
        .filter { action ->
            action.binding.method == HttpMethod.POST && action.binding.body == null &&
                action.provenance.any { provenance -> provenance.kind in safeKinds } &&
                action.binding.path.trimEnd('/').substringAfterLast('/').lowercase() in setOf("refresh", "sync")
        }
        .mapNotNull { action ->
            runCatching {
                buildDynamicApiRequest(
                    descriptor = descriptor,
                    action = action,
                    values = values + ("init" to "true"),
                    runtimeContext = values,
                ).copy(ocsApiRequest = true)
            }.getOrNull()
        }
        .firstOrNull()
}

/**
 * Builds the exact authoritative GET declared for an idempotent replacement mutation.
 *
 * The descriptor validator proves same-route PUT/GET pairing. This function repeats the critical
 * checks at the execution boundary so deserialized or independently constructed descriptors fail
 * closed instead of turning an arbitrary action reference into a recovery read.
 */
internal fun buildDynamicResultRecoveryRequest(
    descriptor: DynamicAppDescriptor,
    mutation: DynamicAction,
    values: Map<String, String>,
    runtimeContext: Map<String, String> = values,
): NextcloudApiRequest? {
    if (mutation.binding.method != HttpMethod.PUT) return null
    val recoveryId = mutation.resultRecoveryActionId ?: return null
    val recovery = descriptor.actions.singleOrNull { action -> action.id == recoveryId } ?: return null
    val safeKinds = setOf(
        dev.obiente.nextcloudnative.nativeui.model.ProvenanceKind.verifiedAppPackage,
        dev.obiente.nextcloudnative.nativeui.model.ProvenanceKind.appStoreLinkedSourceTag,
    )
    if (
        recovery.binding.method != HttpMethod.GET ||
        recovery.binding.path != mutation.binding.path ||
        recovery.binding.body != null ||
        recovery.binding.queryParameters.any(HttpParameter::required) ||
        recovery.fallbackOnly ||
        mutation.provenance.none { provenance -> provenance.kind in safeKinds } ||
        recovery.provenance.none { provenance -> provenance.kind in safeKinds }
    ) {
        return null
    }
    return runCatching {
        buildDynamicApiRequest(
            descriptor = descriptor,
            action = recovery,
            values = values,
            runtimeContext = runtimeContext,
        )
    }.getOrNull()
}

internal fun Throwable.isUnsynchronizedDynamicCollectionFailure(): Boolean =
    message.orEmpty().contains("has not been synchronized", ignoreCase = true)

internal fun NextcloudApiResponse.acceptedDynamicRefresh(): Boolean =
    status in 200..299 || status == 409

private fun bindDynamicPath(
    binding: DynamicHttpBinding,
    values: Map<String, String>,
    runtimeContext: Map<String, String>,
    actionResourceId: String,
): String {
    var path = binding.path
    binding.pathParameters.forEach { parameter ->
        val value = parameter.resolve(values, runtimeContext, actionResourceId)
        require(!parameter.required || !value.isNullOrBlank()) { "${parameter.name} is required." }
        if (!value.isNullOrBlank()) {
            require(value.none { it == '/' || it == '\\' || it.isISOControl() }) {
                "${parameter.name} must be a single safe path segment."
            }
            path = path.replace("{${parameter.name}}", value.encodeUrlComponent())
        }
    }
    val remainingTemplate = path.scanBracedTemplate()
    require(!remainingTemplate.malformed && remainingTemplate.tokens.isEmpty()) {
        "Not all path parameters were provided."
    }
    return path
}

private fun buildDynamicQuery(
    binding: DynamicHttpBinding,
    values: Map<String, String>,
    runtimeContext: Map<String, String>,
    actionResourceId: String,
    collectionRead: Boolean,
): Map<String, String> = buildMap {
    binding.queryParameters.forEach { parameter ->
        val value = parameter.resolve(values, runtimeContext, actionResourceId)
            ?: parameter.initialCollectionPageValue(collectionRead)
        require(!parameter.required || !value.isNullOrBlank()) { "${parameter.name} is required." }
        if (!value.isNullOrBlank()) put(parameter.name, value)
    }
}

private fun HttpParameter.initialCollectionPageValue(collectionRead: Boolean): String? {
    if (!collectionRead || required) return null
    val normalizedName = name.lowercase().filter(Char::isLetterOrDigit)
    if (normalizedName !in INITIAL_PAGE_SIZE_PARAMETER_NAMES) return null
    return automaticCollectionPageSize()?.toString()
}

internal enum class DynamicPaginationMode {
    PageNumber,
    Offset,
    RecordCursor,
}

internal data class DynamicPaginationSpec(
    val parameterName: String,
    val mode: DynamicPaginationMode,
    val expectedPageSize: Int?,
    val recordCursorFieldNames: List<String> = emptyList(),
) {
    fun nextValue(
        nextPageNumber: Int,
        loadedRecordCount: Int,
        lastPage: List<NativeRecord> = emptyList(),
    ): String? = when (mode) {
        DynamicPaginationMode.PageNumber -> nextPageNumber.toString()
        DynamicPaginationMode.Offset -> loadedRecordCount.toString()
        DynamicPaginationMode.RecordCursor -> lastPage.lastOrNull()?.let { record ->
            recordCursorFieldNames.firstNotNullOfOrNull { expectedName ->
                (record.values + record.displayValues).entries.firstOrNull { (fieldName, value) ->
                    value != null && fieldName.normalizedDynamicParameterName() ==
                        expectedName.normalizedDynamicParameterName()
                }?.value?.takeIf { value -> value.isNotBlank() }
            }
        }
    }

    fun canContinue(lastPageSize: Int, novelRecordCount: Int = lastPageSize): Boolean =
        novelRecordCount > 0 && expectedPageSize?.let { lastPageSize >= it } != false
}

/**
 * Returns pagination only when the signed or advertised contract declares a conventional typed
 * integer page/offset query. Cursor values are deliberately never fabricated.
 */
internal fun DynamicAction.dynamicPaginationSpec(): DynamicPaginationSpec? {
    if (binding.method != HttpMethod.GET || intent != dev.obiente.nextcloudnative.nativeui.model.ActionIntent.list) {
        return null
    }
    val optionalIntegerParameters = binding.queryParameters.filter { parameter ->
        !parameter.required && parameter.isIntegerNumberParameter()
    }
    val pageSize = optionalIntegerParameters.firstOrNull { parameter ->
        parameter.name.normalizedDynamicParameterName() in INITIAL_PAGE_SIZE_PARAMETER_NAMES
    }?.automaticCollectionPageSize()
    val pagingParameter = optionalIntegerParameters.firstOrNull { parameter ->
        parameter.name.normalizedDynamicParameterName() in PAGE_NUMBER_PARAMETER_NAMES
    } ?: optionalIntegerParameters.firstOrNull { parameter ->
        parameter.name.normalizedDynamicParameterName() in OFFSET_PARAMETER_NAMES
    } ?: optionalIntegerParameters.firstOrNull { parameter ->
        parameter.name.normalizedDynamicParameterName() in RECORD_CURSOR_PARAMETER_NAMES
    } ?: return null
    val mode = when (pagingParameter.name.normalizedDynamicParameterName()) {
        in OFFSET_PARAMETER_NAMES -> DynamicPaginationMode.Offset
        in RECORD_CURSOR_PARAMETER_NAMES -> DynamicPaginationMode.RecordCursor
        else -> DynamicPaginationMode.PageNumber
    }
    val cursorFields = if (mode == DynamicPaginationMode.RecordCursor) {
        DATE_RECORD_CURSOR_FIELD_NAMES
    } else {
        emptyList()
    }
    return DynamicPaginationSpec(pagingParameter.name, mode, pageSize, cursorFields)
}

private fun HttpParameter.isIntegerNumberParameter(): Boolean {
    val type = (schema as? JsonObject)?.get("type")?.let { it as? JsonPrimitive }?.contentOrNull
    return type == "integer" || type == "number"
}

/**
 * Chooses a useful initial page size only from a conventional optional numeric parameter whose
 * declared schema can be satisfied safely. A valid explicit default wins. Otherwise the normal
 * initial size is clamped to inclusive minimum/maximum bounds. Malformed, contradictory, fractional
 * defaults, and minimums above the automatic-fetch safety ceiling are left to the server by
 * omitting the optional parameter.
 */
private fun HttpParameter.automaticCollectionPageSize(): Int? {
    val objectSchema = schema as? JsonObject ?: return null
    val type = (objectSchema["type"] as? JsonPrimitive)?.contentOrNull
    if (type != "integer" && type != "number") return null

    fun declaredNumber(name: String): Double? {
        val element = objectSchema[name] ?: return null
        val primitive = element as? JsonPrimitive ?: return Double.NaN
        return primitive
            .takeUnless { it.isString }
            ?.doubleOrNull
            ?.takeIf { it.isFinite() }
            ?: Double.NaN
    }

    val minimum = declaredNumber("minimum")
    val maximum = declaredNumber("maximum")
    if (minimum?.isNaN() == true || maximum?.isNaN() == true) return null

    val lowerBound = maxOf(1.0, ceil(minimum ?: 1.0))
    val upperBound = minOf(
        MAX_AUTOMATIC_COLLECTION_PAGE_SIZE.toDouble(),
        floor(maximum ?: MAX_AUTOMATIC_COLLECTION_PAGE_SIZE.toDouble()),
    )
    if (lowerBound > upperBound) return null

    if ("default" in objectSchema) {
        val declaredDefault = declaredNumber("default")
            ?.takeUnless { it.isNaN() }
            ?: return null
        if (
            declaredDefault % 1.0 != 0.0 ||
            declaredDefault < lowerBound ||
            declaredDefault > upperBound
        ) {
            return null
        }
        return declaredDefault.toInt()
    }

    return INITIAL_COLLECTION_PAGE_SIZE.toDouble()
        .coerceIn(lowerBound, upperBound)
        .toInt()
}

private fun String.normalizedDynamicParameterName(): String = lowercase().filter(Char::isLetterOrDigit)

private fun HttpParameter.resolve(
    values: Map<String, String>,
    runtimeContext: Map<String, String>,
    actionResourceId: String,
): String? {
    val sourceValues = when (source) {
        ParameterSource.userInput,
        ParameterSource.resourceField,
        -> values
        ParameterSource.runtimeContext -> runtimeContext
    }
    sourceValues[name]?.takeIf(String::isNotBlank)?.let { return it }

    // Collection payloads commonly expose their primary key as `id`, while an
    // item's declared endpoint gives the same key a resource-qualified name
    // such as `projectId`. A selected record is an unambiguous source for that
    // single semantic identifier, so preserve the exact contract name first
    // and use its native record id only as the fallback.
    return sourceValues["id"]?.takeIf {
        it.isNotBlank() && name.identityResourceStem()?.sameRuntimeResource(actionResourceId) == true
    }
}

private fun String.identityResourceStem(): String? = takeIf {
    length > 2 && endsWith("Id", ignoreCase = true)
}?.dropLast(2)?.takeIf(String::isNotBlank)

private fun String.sameRuntimeResource(other: String): Boolean = runtimeResourceIdentity() == other.runtimeResourceIdentity()

internal const val INITIAL_COLLECTION_PAGE_SIZE = 50
private const val MAX_AUTOMATIC_COLLECTION_PAGE_SIZE = 500
private val INITIAL_PAGE_SIZE_PARAMETER_NAMES = setOf("limit", "pagesize", "perpage", "maxresults")
private val PAGE_NUMBER_PARAMETER_NAMES = setOf("page", "pagenumber", "pageno")
private val OFFSET_PARAMETER_NAMES = setOf("offset")
private val RECORD_CURSOR_PARAMETER_NAMES = setOf("cursor")
private val DATE_RECORD_CURSOR_FIELD_NAMES = listOf("dateInt")

private fun String.runtimeResourceIdentity(): String {
    val normalized = lowercase().filter(Char::isLetterOrDigit)
    return when {
        normalized.endsWith("ies") && normalized.length > 3 -> normalized.dropLast(3) + "y"
        normalized.endsWith("ches") || normalized.endsWith("shes") -> normalized.dropLast(2)
        normalized.endsWith("sses") || normalized.endsWith("xes") || normalized.endsWith("zes") ->
            normalized.dropLast(2)
        normalized.endsWith('s') && normalized.length > 1 -> normalized.dropLast(1)
        else -> normalized
    }
}

private fun buildDynamicBody(
    contentType: String,
    schema: JsonElement,
    values: Map<String, String>,
    observedInputSchema: JsonElement? = null,
): ByteArray {
    val objectSchema = schema as? JsonObject
        ?: error("Only object-shaped dynamic request bodies are supported.")
    val properties = objectSchema["properties"] as? JsonObject ?: JsonObject(emptyMap())
    val observedProperties = ((observedInputSchema as? JsonObject)?.get("properties") as? JsonObject)
        .orEmpty()
    val allowsObservedSettings = (objectSchema["x-nextcloud-native-observed-settings-body"] as? JsonPrimitive)
        ?.booleanOrNull == true &&
        (objectSchema["additionalProperties"] as? JsonPrimitive)?.booleanOrNull == true
    val requiredProperties = (objectSchema["required"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        .orEmpty()
    requiredProperties.forEach { property ->
        require(!values[property].isNullOrBlank()) { "$property is required." }
    }
    val allowed = if (allowsObservedSettings) {
        values.entries.asSequence()
            .filter { (key, value) ->
                key.isSafeObservedFieldName() && !key.isSensitiveObservedField() &&
                    value.length <= MAX_OBSERVED_SCALAR_LENGTH
            }
            .take(MAX_EPHEMERAL_FIELDS_PER_RECORD)
            .associate(Map.Entry<String, String>::toPair)
    } else {
        values.filterKeys(properties::containsKey)
    }
    val normalizedContentType = contentType.substringBefore(';').trim().lowercase()
    if (normalizedContentType == "application/x-www-form-urlencoded") {
        allowed.keys.forEach { name ->
            val property = properties[name] as? JsonObject ?: return@forEach
            require((property["type"] as? JsonPrimitive)?.contentOrNull != "array") {
                "Form-encoded array fields require an exact serialization contract " +
                    "that is not supported yet."
            }
        }
    }
    return when (normalizedContentType) {
        "application/json" -> {
            buildJsonObject {
                val wireNames = mutableSetOf<String>()
                allowed.forEach { (name, value) ->
                    val property = properties[name] as? JsonObject
                    val propertyType = property?.dynamicPropertyType()
                    if (
                        value.isBlank() &&
                        name !in requiredProperties &&
                        (
                            property?.acceptsDynamicNull() == true ||
                            propertyType in setOf("boolean", "integer", "number") ||
                                property.isExactDynamicIntegerArraySchema()
                            )
                    ) {
                        return@forEach
                    }
                    val observedProperty = observedProperties[name]
                        ?.takeIf { allowsObservedSettings }
                    val wireName = (property?.get(SETTINGS_WIRE_NAME_EXTENSION) as? JsonPrimitive)
                        ?.contentOrNull
                        ?.takeIf(String::isSafeDynamicBodyFieldName)
                        ?: name
                    require(wireNames.add(wireName)) { "The request maps more than one field to $wireName." }
                    put(
                        wireName,
                        property?.let(value::toTypedJsonValue)
                            ?: observedProperty?.let(value::toObservedTypedJsonValue)
                            ?: value.toObservedSettingsJsonValue(),
                    )
                }
            }.toString().encodeToByteArray()
        }
        "application/x-www-form-urlencoded" -> allowed.entries.joinToString("&") { (name, value) ->
            "${name.encodeUrlComponent()}=${value.encodeUrlComponent()}"
        }.encodeToByteArray()
        else -> error("Dynamic request bodies of type $contentType are not supported yet.")
    }
}

private fun JsonElement?.observesSettingsBody(): Boolean {
    val schema = this as? JsonObject ?: return false
    return (schema["x-nextcloud-native-observed-settings-body"] as? JsonPrimitive)?.booleanOrNull == true &&
        (schema["additionalProperties"] as? JsonPrimitive)?.booleanOrNull == true
}

private fun String.toObservedTypedJsonValue(schema: JsonElement): JsonElement {
    val type = ((schema as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull
    return when (type) {
        "boolean",
        "integer",
        "number",
        -> toTypedJsonValue(schema)
        else -> JsonPrimitive(this)
    }
}

private fun String.toObservedSettingsJsonValue(): JsonElement {
    val candidate = runCatching { dynamicJson.parseToJsonElement(this) }.getOrNull() as? JsonObject
    if (candidate != null && candidate.isNotEmpty() && candidate.size <= MAX_OBSERVED_BOOLEAN_MAP_ENTRIES &&
        candidate.keys.all(String::isSafeDynamicBodyFieldName) &&
        candidate.values.all { value -> (value as? JsonPrimitive)?.booleanOrNull != null }
    ) {
        return candidate
    }
    return JsonPrimitive(this)
}

private fun String.toTypedJsonValue(schema: JsonElement): JsonElement {
    schema.repeatableObjectInputSpec()?.let { repeatable ->
        return repeatable.canonicalJson(this)
    }
    val declaredObjectSchema = schema.explicitObjectSchema()
    if (declaredObjectSchema != null) {
        val booleanMap = (declaredObjectSchema["x-nextcloud-native-boolean-map"] as? JsonPrimitive)
            ?.booleanOrNull == true
        require(length <= MAX_DYNAMIC_DECLARED_OBJECT_LENGTH) {
            "This contract-declared object is too large."
        }
        val value = runCatching { dynamicJson.parseToJsonElement(this) }.getOrNull() as? JsonObject
            ?: error("Enter a JSON object.")
        if (booleanMap) {
            require(
                value.size <= MAX_OBSERVED_BOOLEAN_MAP_ENTRIES &&
                    value.keys.all(String::isSafeDynamicBodyFieldName) &&
                    value.values.all { item -> (item as? JsonPrimitive)?.booleanOrNull != null },
            ) {
                "This boolean map contains unsupported keys or values."
            }
        }
        return value
    }
    return when ((schema as? JsonObject)?.dynamicPropertyType()) {
    "boolean" -> JsonPrimitive(toBooleanStrictOrNull() ?: error("Enter true or false."))
    "integer" -> JsonPrimitive(toLongOrNull() ?: error("Enter a whole number."))
    "number" -> JsonPrimitive(
        toDoubleOrNull()?.takeIf(Double::isFinite) ?: error("Enter a valid number."),
    )
    "array" -> {
        val objectSchema = schema
        val format = (objectSchema["format"] as? JsonPrimitive)?.contentOrNull
        val itemType = ((objectSchema["items"] as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull
        when {
            objectSchema.isExactDynamicIntegerArraySchema() -> {
                when (val parsed = parseDynamicIntegerArrayInput(this, objectSchema)) {
                    is DynamicIntegerArrayParseResult.Valid ->
                        JsonArray(parsed.values.map(::JsonPrimitive))
                    is DynamicIntegerArrayParseResult.Invalid -> error(parsed.message)
                }
            }
            format in setOf(DYNAMIC_STRING_LIST_FORMAT, DYNAMIC_STRING_ARRAY_FORMAT) &&
                itemType == "string" -> {
                val items = lineSequence()
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                val preserved = if (format == DYNAMIC_STRING_LIST_FORMAT) items.distinct() else items
                JsonArray(
                    preserved
                        .take(MAX_DYNAMIC_STRING_LIST_ITEMS + 1)
                        .toList()
                        .also {
                            require(it.size <= MAX_DYNAMIC_STRING_LIST_ITEMS) {
                                "This list has too many values."
                            }
                        }
                        .map(::JsonPrimitive),
                )
            }
            format == DYNAMIC_INTEGER_ARRAY_FORMAT ->
                error("Only exact contract-declared integer arrays can be edited.")
            else -> error("Only contract-declared scalar arrays can be edited.")
        }
    }
    else -> JsonPrimitive(this)
    }
}

private fun JsonObject.dynamicPropertyType(): String? = when (val type = get("type")) {
    is JsonPrimitive -> type.contentOrNull
    is JsonArray -> type.firstNotNullOfOrNull { candidate ->
        (candidate as? JsonPrimitive)?.contentOrNull?.takeUnless { declared -> declared == "null" }
    }
    else -> null
}

private fun JsonObject.acceptsDynamicNull(): Boolean =
    (get("nullable") as? JsonPrimitive)?.booleanOrNull == true ||
        (get("type") as? JsonArray)?.any { candidate ->
            (candidate as? JsonPrimitive)?.contentOrNull == "null"
        } == true

private fun JsonElement.explicitObjectSchema(): JsonObject? {
    val schema = this as? JsonObject ?: return null
    if ((schema["type"] as? JsonPrimitive)?.contentOrNull == "object") return schema
    return (schema["oneOf"] as? JsonArray).orEmpty()
        .mapNotNull { it as? JsonObject }
        .firstOrNull { option -> (option["type"] as? JsonPrimitive)?.contentOrNull == "object" }
}

private fun String.isSafeDynamicBodyFieldName(): Boolean =
    length in 1..64 && all { it.isLetterOrDigit() || it == '_' || it == '-' }

private const val SETTINGS_WIRE_NAME_EXTENSION = "x-nextcloud-native-wire-name"
private const val MAX_DYNAMIC_STRING_LIST_ITEMS = 256
private const val MAX_DYNAMIC_DECLARED_OBJECT_LENGTH = 512 * 1024
private const val MAX_OBSERVED_BOOLEAN_MAP_ENTRIES = 32
private const val MAX_COLLECTION_ENVELOPE_METADATA_FIELDS = 32
private val COLLECTION_ENVELOPE_ARRAY_KEYS = setOf(
    "data", "entries", "items", "list", "objects", "records", "results", "rows", "values",
)
private val COLLECTION_ENVELOPE_METADATA_KEYS = setOf(
    "count", "cursor", "from", "hasmore", "ispaginated", "label", "limit", "meta", "metadata",
    "name", "next", "nextcursor", "offset", "page", "pagecount", "pages", "pagination", "perpage",
    "status", "success", "title", "total", "totalcount",
)

private fun JsonElement.toNativeRecords(
    mapCollectionCandidate: Boolean,
    declaredFieldIds: Set<String>,
    collectionNameHints: Set<String> = emptySet(),
    preferredIdentityFieldId: String? = null,
): List<NativeRecord> = (when (this) {
    is JsonArray -> asSequence().take(MAX_DYNAMIC_NATIVE_RECORDS).mapIndexed { index, element ->
        element.toNativeRecord(
            fallbackId = index.toString(),
            declaredFieldIds = declaredFieldIds,
            preferredIdentityFieldId = preferredIdentityFieldId,
            allowObservedRichText = !mapCollectionCandidate,
        )
    }.toList()
    is JsonObject -> if (mapCollectionCandidate) {
        val namedArray = (entries.singleOrNull()?.value as? JsonArray)
            ?: collectionEnvelopeArrayOrNull(collectionNameHints)
        when {
            namedArray != null -> namedArray.toNativeRecords(
                mapCollectionCandidate = false,
                declaredFieldIds = declaredFieldIds,
                preferredIdentityFieldId = preferredIdentityFieldId,
            )
            isObjectMapCollection() -> entries.asSequence()
                .take(MAX_DYNAMIC_NATIVE_RECORDS)
                .mapNotNull { (mapKey, element) ->
                (element as? JsonObject)?.toNativeRecord(
                    fallbackId = mapKey,
                    declaredFieldIds = declaredFieldIds,
                    preferredIdentityFieldId = preferredIdentityFieldId,
                    stableFallbackIdentity = true,
                    allowObservedRichText = false,
                )
            }.toList()
            else -> listOf(
                toNativeRecord(
                    fallbackId = "record",
                    declaredFieldIds = declaredFieldIds,
                    preferredIdentityFieldId = preferredIdentityFieldId,
                    allowObservedRichText = true,
                ),
            )
        }
    } else {
        listOf(
            toNativeRecord(
                fallbackId = "record",
                declaredFieldIds = declaredFieldIds,
                preferredIdentityFieldId = preferredIdentityFieldId,
                allowObservedRichText = true,
            ),
        )
    }
    else -> listOf(
        NativeRecord(
            id = "value",
            values = emptyMap(),
            displayValues = mapOf("value" to jsonPrimitive.content),
            actionSafeIdentity = false,
        ),
    )
}).distinctBy(NativeRecord::id).take(MAX_DYNAMIC_NATIVE_RECORDS)

/**
 * Finds a conventional list inside a response envelope without treating arbitrary nested arrays
 * as rows. Many APIs return `{ entries: [...], cursor, total, name }`; rendering that object as one
 * record makes empty collections look non-empty and hides every actual item.
 *
 * This remains deliberately shape-conservative: exactly one recognized collection key is
 * required, every sibling must be recognized envelope metadata, and a second array always makes
 * the result ambiguous. App-specific payload objects continue through structured detail instead.
 */
private fun JsonObject.collectionEnvelopeArrayOrNull(
    collectionNameHints: Set<String>,
): JsonArray? {
    val arrayEntries = entries.filter { (_, value) -> value is JsonArray }
    val candidate = arrayEntries.singleOrNull { (key, _) ->
        key.normalizedEnvelopeKey() in collectionNameHints
    } ?: arrayEntries.singleOrNull { (key, _) ->
        key.normalizedEnvelopeKey() in COLLECTION_ENVELOPE_ARRAY_KEYS
    } ?: return null
    val expectedResourceCollection = candidate.key.normalizedEnvelopeKey() in collectionNameHints
    if (!expectedResourceCollection && arrayEntries.size != 1) return null
    val candidateArray = candidate.value as JsonArray
    if (candidateArray.any { value -> value !is JsonObject && value !is JsonNull }) return null
    val metadata = entries.filterNot { it.key == candidate.key }
    if (metadata.size > MAX_COLLECTION_ENVELOPE_METADATA_FIELDS) return null
    if (metadata.any { (key, value) ->
            if (expectedResourceCollection) {
                !value.isSafeResourceCollectionEnvelopeMetadata(
                    key = key,
                    collectionSize = candidateArray.size,
                )
            } else {
                key.normalizedEnvelopeKey() !in COLLECTION_ENVELOPE_METADATA_KEYS ||
                    !value.isSafeCollectionEnvelopeMetadata()
            }
        }
    ) return null
    return candidateArray
}

private fun JsonElement.isSafeResourceCollectionEnvelopeMetadata(
    key: String,
    collectionSize: Int,
): Boolean = when (this) {
    JsonNull, is JsonPrimitive -> true
    is JsonObject -> isSafeCollectionEnvelopeMetadata()
    is JsonArray -> {
        val normalizedKey = key.normalizedEnvelopeKey()
        normalizedKey.endsWith("ids") &&
            size >= collectionSize &&
            size <= MAX_DYNAMIC_NATIVE_RECORDS &&
            all { value -> value is JsonPrimitive || value is JsonNull }
    }
}

private fun JsonElement.isSafeCollectionEnvelopeMetadata(): Boolean = when (this) {
    JsonNull, is JsonPrimitive -> true
    is JsonObject -> size <= MAX_COLLECTION_ENVELOPE_METADATA_FIELDS && entries.all { (key, value) ->
        key.isSafeObservedFieldName() && (value is JsonPrimitive || value is JsonNull)
    }
    is JsonArray -> false
}

private fun String.normalizedEnvelopeKey(): String = lowercase().filter(Char::isLetterOrDigit)

private fun JsonObject.isObjectMapCollection(): Boolean {
    if (size > MAX_DYNAMIC_NATIVE_RECORDS) return false
    val populated = values.filterNot { it is JsonNull }
    return populated.isNotEmpty() && populated.all { it is JsonObject }
}

private fun JsonElement.toNativeRecord(
    fallbackId: String,
    declaredFieldIds: Set<String>,
    preferredIdentityFieldId: String? = null,
    stableFallbackIdentity: Boolean = false,
    allowObservedRichText: Boolean = false,
): NativeRecord {
    val safeFallbackId = fallbackId.toSafeDynamicRecordId()
    val objectValue = this as? JsonObject ?: return NativeRecord(
        id = safeFallbackId,
        values = emptyMap(),
        displayValues = mapOf("value" to toString().take(MAX_OBSERVED_SCALAR_LENGTH)),
        actionSafeIdentity = false,
    )
    // Prefer identities explicitly described by the contract. Sparse contracts often describe a
    // collection only as `array<object>` or `additionalProperties<object>` though. In that case a
    // backing-store identity such as `databaseId` is a safer child-route key than an opaque display
    // `id` (which is commonly a protocol identifier), while an object-map key is authoritative over
    // fields such as `name`. Undeclared identities remain read-navigation-only below.
    val declaredIdentity: (String) -> Pair<String, String>? = { candidate ->
        declaredFieldIds.firstOrNull { it.equals(candidate, ignoreCase = true) }
            ?.let(objectValue::safeScalarIdentity)
    }
    val observedIdentity: (String) -> Pair<String, String>? = { candidate ->
        if (stableFallbackIdentity) null else objectValue.safeScalarIdentity(candidate)
    }
    // Some APIs advertise a protocol identifier as `id` while omitting their numeric backing
    // identity from an incomplete response schema. Read-only child routes can still require that
    // observed `databaseId`. Prefer it for navigation, but keep it action-unsafe unless the
    // contract explicitly declared the field.
    val identity = preferredIdentityFieldId
        ?.takeIf { preferred -> declaredFieldIds.count { it == preferred } == 1 }
        ?.let(declaredIdentity)
        ?: declaredIdentity("databaseId")
        ?: observedIdentity("databaseId")
        ?: declaredIdentity("id")
        ?: declaredIdentity("uuid")
        ?: observedIdentity("id")
        ?: observedIdentity("uuid")
        ?: observedIdentity("name")
    val id = identity?.second ?: safeFallbackId
    val actionSafeIdentity = identity?.first?.let { identityKey ->
        declaredFieldIds.any { it.equals(identityKey, ignoreCase = true) }
    } == true
    val structuredBudget = NativeStructuredBudget()
    val structuredValues = objectValue.entries.asSequence()
        .filter { (key, value) ->
            (value is JsonArray || value is JsonObject) &&
                key.isSafeObservedFieldName() &&
                !value.isSensitiveObservedValue(key)
        }
        .take(MAX_STRUCTURED_TOP_LEVEL_FIELDS)
        .mapNotNull { (key, value) ->
            value.toBoundedNativeStructure(structuredBudget)?.let { key to it }
        }
        .toMap()
    return NativeRecord(
        id = id,
        values = objectValue.entries.asSequence()
            .filter { (key, value) ->
                key.isSafeObservedFieldName() &&
                declaredFieldIds.any { it.equals(key, ignoreCase = true) } &&
                    !value.isSensitiveObservedValue(key)
            }
            .take(MAX_NATIVE_FIELDS_PER_RECORD)
            .associate { (key, value) -> key to value.toDisplayString() },
        displayValues = objectValue.entries.asSequence()
            .filter { (key, _) -> key.isSafeObservedFieldName() }
            .take(MAX_NATIVE_FIELDS_PER_RECORD)
            .mapNotNull { (key, value) ->
                value.toSafeDisplayString(key, allowObservedRichText)?.let { display -> key to display }
            }
            .toMap(),
        ephemeralFields = objectValue.entries.asSequence()
            .mapNotNull { (key, value) -> value.toEphemeralField(key, allowObservedRichText) }
            .take(MAX_EPHEMERAL_FIELDS_PER_RECORD)
            .toList(),
        actionSafeIdentity = actionSafeIdentity,
        structuredValues = structuredValues,
    )
}

private fun String.toSafeDynamicRecordId(): String {
    val bounded = take(MAX_DYNAMIC_RECORD_ID_LENGTH).map { character ->
        if (character.isISOControl()) '_' else character
    }.joinToString("")
    return bounded.takeIf(String::isNotBlank) ?: "record"
}

private fun JsonObject.safeScalarIdentity(candidate: String): Pair<String, String>? {
    val (key, element) = entries.firstOrNull { (key, _) -> key.equals(candidate, ignoreCase = true) }
        ?: return null
    val value = (element as? JsonPrimitive)?.contentOrNull
        ?.takeIf { it.isNotBlank() && it.length <= 256 && !it.looksLikeBinaryScalar() }
        ?: return null
    return key to value
}

private data class NativeStructuredBudget(
    var remainingNodes: Int = MAX_STRUCTURED_NODES_PER_RECORD,
    var remainingCharacters: Int = MAX_STRUCTURED_CHARACTERS_PER_RECORD,
)

private fun JsonElement.toBoundedNativeStructure(
    budget: NativeStructuredBudget,
    depth: Int = 0,
): NativeStructuredValue? {
    if (depth > MAX_STRUCTURED_DEPTH || budget.remainingNodes <= 0) return null
    budget.remainingNodes -= 1
    return when (this) {
        JsonNull -> NativeStructuredValue.Scalar(null, NativeStructuredScalarKind.nullValue)
        is JsonPrimitive -> {
            val scalar = contentOrNull ?: return NativeStructuredValue.Scalar(null, NativeStructuredScalarKind.nullValue)
            if (scalar.length > MAX_STRUCTURED_SCALAR_LENGTH || scalar.looksLikeBinaryScalar() ||
                scalar.length > budget.remainingCharacters
            ) return null
            budget.remainingCharacters -= scalar.length
            NativeStructuredValue.Scalar(
                value = scalar,
                kind = when {
                    booleanOrNull != null -> NativeStructuredScalarKind.boolean
                    !isString && (longOrNull != null || doubleOrNull != null) -> NativeStructuredScalarKind.number
                    else -> NativeStructuredScalarKind.string
                },
            )
        }
        is JsonArray -> {
            val retained = take(MAX_STRUCTURED_ENTRIES_PER_CONTAINER)
                .mapNotNull { value -> value.toBoundedNativeStructure(budget, depth + 1) }
            NativeStructuredValue.ListValue(
                items = retained,
                omittedItems = (size - retained.size).coerceAtLeast(0),
            )
        }
        is JsonObject -> {
            val isEligible: (Map.Entry<String, JsonElement>) -> Boolean = { (key, value) ->
                key.isSafeStructuredEntryKey() && !value.isSensitiveObservedValue(key)
            }
            val eligibleCount = entries.count(isEligible)
            val safeEntries = entries.asSequence()
                .filter(isEligible)
                .take(MAX_STRUCTURED_ENTRIES_PER_CONTAINER)
                .mapNotNull { (key, value) ->
                    value.toBoundedNativeStructure(budget, depth + 1)?.let { structured ->
                        NativeStructuredEntry(key, key.safeObservedLabel(), structured)
                    }
                }
                .toList()
            NativeStructuredValue.ObjectValue(
                entries = safeEntries,
                omittedEntries = (eligibleCount - safeEntries.size).coerceAtLeast(0),
            )
        }
    }
}

private fun JsonElement.toEphemeralField(key: String, allowObservedRichText: Boolean): FieldSpec? {
    if (!key.isSafeObservedFieldName() || isSensitiveObservedValue(key)) return null
    if (this is JsonArray && isEmpty()) return null
    if (this is JsonObject && entries.none { (nestedKey, nestedValue) ->
            nestedKey.isSafeStructuredEntryKey() && !nestedValue.isSensitiveObservedValue(nestedKey)
        }
    ) return null
    if (this is JsonArray || this is JsonObject) {
        return FieldSpec(
            id = key,
            label = key.safeObservedLabel(),
            kind = FieldKind.objectValue,
            required = false,
            readOnly = true,
        )
    }
    val primitive = this as? JsonPrimitive ?: return null
    if (primitive is JsonNull) return null
    val value = primitive.contentOrNull ?: return null
    val richTextBody = allowObservedRichText && key.isObservedRichTextBodyField()
    val maximumLength = if (richTextBody) MAX_OBSERVED_RICH_TEXT_LENGTH else MAX_OBSERVED_SCALAR_LENGTH
    if (value.length > maximumLength || value.looksLikeBinaryScalar()) return null
    val kind = when {
        primitive.booleanOrNull != null -> FieldKind.boolean
        !primitive.isString && primitive.longOrNull != null -> FieldKind.integer
        !primitive.isString && primitive.doubleOrNull != null -> FieldKind.decimal
        value.looksLikeIsoDateTime() -> FieldKind.dateTime
        value.looksLikeIsoDate() -> FieldKind.date
        richTextBody || value.length > 160 -> FieldKind.longText
        else -> FieldKind.string
    }
    val format = value.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let { "uri" }
    return FieldSpec(
        id = key,
        label = key.safeObservedLabel(),
        kind = kind,
        required = false,
        readOnly = true,
        format = format,
    )
}

private fun JsonElement.toSafeDisplayString(key: String, allowObservedRichText: Boolean): String? = when {
    isSensitiveObservedValue(key) -> "Redacted"
    this is JsonNull -> null
    this is JsonArray -> "${size} ${if (size == 1) "item" else "items"}"
    this is JsonObject -> entries
        .count { (nestedKey, nestedValue) ->
            nestedKey.isSafeStructuredEntryKey() && !nestedValue.isSensitiveObservedValue(nestedKey)
        }
        .let { safeFieldCount ->
            "$safeFieldCount ${if (safeFieldCount == 1) "field" else "fields"}"
        }
    this is JsonPrimitive -> contentOrNull?.let { value ->
        when {
            value.looksLikeBinaryScalar() -> "Binary data"
            allowObservedRichText && key.isObservedRichTextBodyField() && value.length > MAX_OBSERVED_RICH_TEXT_LENGTH ->
                value.take(MAX_OBSERVED_RICH_TEXT_LENGTH) + "\n\n[Content truncated]"
            allowObservedRichText && key.isObservedRichTextBodyField() -> value
            value.length > MAX_OBSERVED_SCALAR_LENGTH -> "Long value"
            else -> value
        }
    }
    else -> null
}

private fun String.isSafeObservedFieldName(): Boolean =
    length in 1..64 && all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }

/** Allows bounded MIME-keyed representation maps without admitting arbitrary object keys. */
private fun String.isSafeStructuredEntryKey(): Boolean {
    if (isSafeObservedFieldName()) return true
    if (length !in 3..128 || count { it == '/' } != 1) return false
    val (type, subtype) = split('/', limit = 2)
    if (type !in setOf("application", "audio", "font", "image", "message", "model", "multipart", "text", "video")) {
        return false
    }
    return subtype.isNotBlank() && subtype.all { character ->
        character.isLetterOrDigit() || character in setOf('!', '#', '$', '&', '-', '^', '_', '.', '+')
    }
}

private fun String.isObservedRichTextBodyField(): Boolean =
    lowercase().filter(Char::isLetterOrDigit) in setOf("body", "content", "text", "htmlbody")

private fun String.isSensitiveObservedField(): Boolean {
    val normalized = lowercase().filter(Char::isLetterOrDigit)
    return normalized in setOf(
        "authorization", "cookie", "credential", "credentials", "csrf", "csrftoken",
        "password", "passwd", "privatekey", "secret", "session", "sessionid", "token",
    ) || normalized.endsWith("password") || normalized.endsWith("secret") || normalized.endsWith("token")
}

/**
 * Bare digest fields are ambiguous, but a long hexadecimal `hash`/`digest` is frequently an
 * authentication verifier. Treat it like a credential in generic observed data. This still keeps
 * ordinary short hashes, labels, and non-hex identifiers visible.
 */
private fun JsonElement.isSensitiveObservedValue(key: String): Boolean {
    if (key.isSensitiveObservedField()) return true
    val normalized = key.lowercase().filter(Char::isLetterOrDigit)
    if (normalized !in setOf("hash", "digest")) return false
    val value = (this as? JsonPrimitive)?.contentOrNull ?: return false
    return value.length in 40..128 && value.all { character ->
        character.isDigit() || character.lowercaseChar() in 'a'..'f'
    }
}

private fun String.looksLikeBinaryScalar(): Boolean {
    if (length < 96 || any(Char::isWhitespace)) return false
    val alphabet = count { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' || it == '-' || it == '_' }
    return alphabet * 100 / length >= 98
}

private fun String.looksLikeIsoDate(): Boolean =
    length == 10 && this[4] == '-' && this[7] == '-' &&
        take(4).all(Char::isDigit) && substring(5, 7).all(Char::isDigit) && takeLast(2).all(Char::isDigit)

private fun String.looksLikeIsoDateTime(): Boolean =
    length > 10 && take(10).looksLikeIsoDate() && (this[10] == 'T' || this[10] == ' ')

private fun String.safeObservedLabel(): String = buildString(length + 4) {
    var previousWasSeparator = true
    this@safeObservedLabel.forEachIndexed { index, character ->
        val separator = character == '_' || character == '-' || character == '.'
        if (separator) {
            if (isNotEmpty() && last() != ' ') append(' ')
            previousWasSeparator = true
        } else {
            if (index > 0 && character.isUpperCase() && !previousWasSeparator && lastOrNull() != ' ') append(' ')
            append(if (isEmpty()) character.uppercaseChar() else character)
            previousWasSeparator = false
        }
    }
}.trim()

private const val MAX_EPHEMERAL_FIELDS_PER_RECORD = 24
internal const val MAX_DYNAMIC_NATIVE_RECORDS = 1_000
private const val MAX_DYNAMIC_BINDING_CONTEXT_VALUES = 32
private const val MAX_DYNAMIC_MULTIPART_FIELDS = 17
private val DYNAMIC_MULTIPART_SCALAR_TYPES = setOf("string", "integer", "number", "boolean")
private const val MAX_DYNAMIC_JSON_DEPTH = 64
private const val MAX_DYNAMIC_RECORD_ID_LENGTH = 256
private const val MAX_NATIVE_FIELDS_PER_RECORD = 128
private const val MAX_STRUCTURED_TOP_LEVEL_FIELDS = 64
private const val MAX_OBSERVED_SCALAR_LENGTH = 512
private const val MAX_OBSERVED_RICH_TEXT_LENGTH = 512 * 1_024
private const val MAX_DECLARED_STRUCTURED_JSON_LENGTH = 256 * 1024
private const val MAX_STRUCTURED_DEPTH = 4
private const val MAX_STRUCTURED_ENTRIES_PER_CONTAINER = 128
private const val MAX_STRUCTURED_NODES_PER_RECORD = 512
private const val MAX_STRUCTURED_CHARACTERS_PER_RECORD = 32 * 1024
private const val MAX_STRUCTURED_SCALAR_LENGTH = 4 * 1024

private fun JsonElement.toDisplayString(): String? = when (this) {
    JsonNull -> null
    is JsonPrimitive -> contentOrNull
    is JsonArray -> takeIf { values -> values.all { it is JsonPrimitive } }
        ?.joinToString(", ") { (it as JsonPrimitive).content }
        ?.takeIf { it.length <= MAX_DECLARED_STRUCTURED_JSON_LENGTH }
        ?: toString().takeIf { it.length <= MAX_DECLARED_STRUCTURED_JSON_LENGTH }
    is JsonObject -> toString().takeIf { it.length <= MAX_DECLARED_STRUCTURED_JSON_LENGTH }
}

private fun JsonElement.atJsonPointer(pointer: String): JsonElement {
    if (pointer.isEmpty()) return this
    require(pointer.startsWith('/')) { "The response JSON pointer is invalid." }
    return pointer.removePrefix("/").split('/').fold(this) { current, rawPart ->
        val part = rawPart.decodeJsonPointerPart()
        when (current) {
            is JsonObject -> current[part] ?: error("The response is missing $pointer.")
            is JsonArray -> current.getOrNull(part.toIntOrNull() ?: -1) ?: error("The response is missing $pointer.")
            else -> error("The response is missing $pointer.")
        }
    }
}

private fun JsonElement.requireSuccessfulOcsResponse() {
    val metadata = this as? JsonObject ?: error("The OCS response metadata is invalid.")
    val status = (metadata["status"] as? JsonPrimitive)?.contentOrNull
    val statusCode = (metadata["statuscode"] as? JsonPrimitive)?.longOrNull
    val successfulCode = statusCode == null || statusCode == 100L ||
        statusCode in 200L..299L
    val successful = (status.equals("ok", ignoreCase = true) && successfulCode) ||
        (status == null && statusCode != null && successfulCode)
    check(successful) {
        (metadata["message"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf(String::isNotBlank)
            ?: "The OCS endpoint reported an error."
    }
}

private fun String.decodeJsonPointerPart(): String = buildString {
    var index = 0
    while (index < this@decodeJsonPointerPart.length) {
        val character = this@decodeJsonPointerPart[index]
        if (character != '~') {
            append(character)
            index += 1
            continue
        }
        val escape = this@decodeJsonPointerPart.getOrNull(index + 1)
            ?: error("The response JSON pointer is invalid.")
        append(
            when (escape) {
                '0' -> '~'
                '1' -> '/'
                else -> error("The response JSON pointer is invalid.")
            },
        )
        index += 2
    }
}

private fun HttpMethod.toTransportMethod(): NextcloudApiMethod = when (this) {
    HttpMethod.GET -> NextcloudApiMethod.GET
    HttpMethod.POST -> NextcloudApiMethod.POST
    HttpMethod.PUT -> NextcloudApiMethod.PUT
    HttpMethod.PATCH -> NextcloudApiMethod.PATCH
    HttpMethod.DELETE -> NextcloudApiMethod.DELETE
}

private fun String.matchesApiPrefix(prefix: String): Boolean {
    val normalized = prefix.trimEnd('/')
    return this == normalized || startsWith("$normalized/")
}

internal fun String.httpOrigin(): String {
    val match = Regex("^(https?://[^/?#]+)").find(this.trim())
        ?: error("The Nextcloud server URL is invalid.")
    return match.groupValues[1]
}

private fun String.encodeUrlComponent(): String = buildString {
    for (byte in this@encodeUrlComponent.encodeToByteArray()) {
        val unsigned = byte.toInt() and 0xff
        val unreserved = unsigned in 'a'.code..'z'.code || unsigned in 'A'.code..'Z'.code ||
            unsigned in '0'.code..'9'.code || unsigned in listOf('-'.code, '.'.code, '_'.code, '~'.code)
        if (unreserved) append(unsigned.toChar()) else {
            append('%')
            append(DYNAMIC_HEX[unsigned ushr 4])
            append(DYNAMIC_HEX[unsigned and 0x0f])
        }
    }
}

private const val DYNAMIC_HEX = "0123456789ABCDEF"
private const val MAX_DYNAMIC_ERROR_BODY_CHARS = 8_192
private const val MAX_DYNAMIC_ERROR_MESSAGE_CHARS = 240
private const val MAX_DYNAMIC_VERSION_HINT_CHARACTERS = 128
private const val OCS_API_VIEWER_CATALOG_PATH = "/index.php/apps/ocs_api_viewer/apps"
private const val OCS_API_VIEWER_SPEC_PATH = "/index.php/apps/ocs_api_viewer/apps"
