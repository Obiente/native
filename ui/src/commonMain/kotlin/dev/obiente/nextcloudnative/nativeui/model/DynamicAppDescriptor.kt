package dev.obiente.nextcloudnative.nativeui.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement

const val DYNAMIC_APP_DESCRIPTOR_VERSION: String = "1.0"

@Serializable
data class DynamicAppDescriptor(
    val descriptorVersion: String,
    val app: AppIdentity,
    val endpointPolicy: EndpointPolicy,
    val capabilities: List<CapabilityFact> = emptyList(),
    val permissions: List<PermissionSpec> = emptyList(),
    val resources: List<DynamicResource> = emptyList(),
    val layouts: List<DynamicLayout> = emptyList(),
    val links: List<DynamicLink> = emptyList(),
    val forms: List<DynamicForm> = emptyList(),
    val actions: List<DynamicAction> = emptyList(),
    val warnings: List<DynamicWarning> = emptyList(),
)

@Serializable
data class EndpointPolicy(
    val serverOrigin: String,
    val approvedApiPrefixes: List<String> = emptyList(),
)

@Serializable
data class CapabilityFact(
    val id: String,
    val value: JsonElement,
    val confidence: Confidence,
    val provenance: Provenance,
)

@Serializable
data class PermissionSpec(
    val id: String,
    val label: String,
    val kind: PermissionKind,
    val state: PermissionState,
    val confidence: Confidence,
    val provenance: Provenance,
)

@Serializable
enum class PermissionKind {
    authenticatedSession,
    apiScope,
    serverRole,
    resourceAcl,
}

@Serializable
enum class PermissionState {
    required,
    granted,
    denied,
    unknown,
}

@Serializable
data class DynamicResource(
    val id: String,
    val label: String,
    val collection: Boolean,
    val fields: List<DynamicField> = emptyList(),
    val capabilityIds: List<String> = emptyList(),
    val permissionIds: List<String> = emptyList(),
    val confidence: Confidence,
    val provenance: List<Provenance> = emptyList(),
)

@Serializable
data class DynamicField(
    val id: String,
    val label: String,
    val kind: FieldKind,
    val required: Boolean,
    val readOnly: Boolean,
    val nullable: Boolean,
    val multiple: Boolean,
    val format: String? = null,
    val enumValues: List<String>? = null,
    val confidence: Confidence,
    val provenance: List<Provenance> = emptyList(),
)

@Serializable
data class DynamicLayout(
    val id: String,
    val title: String,
    val resourceId: String,
    val kind: LayoutKind,
    val fields: List<LayoutField> = emptyList(),
    val sourceActionId: String? = null,
    val confidence: Confidence,
    val provenance: List<Provenance> = emptyList(),
)

@Serializable
enum class LayoutKind {
    list,
    detail,
    grid,
}

@Serializable
data class LayoutField(
    val fieldId: String,
    val role: LayoutFieldRole,
    val visible: Boolean,
)

@Serializable
enum class LayoutFieldRole {
    identity,
    title,
    subtitle,
    body,
    image,
    metadata,
}

@Serializable
data class DynamicLink(
    val id: String,
    val label: String,
    val resourceId: String,
    val sourceFieldId: String,
    val target: DynamicLinkTarget,
    val confidence: Confidence,
    val provenance: List<Provenance> = emptyList(),
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
sealed interface DynamicLinkTarget {
    @Serializable
    @SerialName("fieldUrl")
    data class FieldUrl(val allowExternal: Boolean) : DynamicLinkTarget

    @Serializable
    @SerialName("action")
    data class Action(val actionId: String) : DynamicLinkTarget
}

@Serializable
data class DynamicForm(
    val id: String,
    val title: String,
    val resourceId: String,
    val actionId: String,
    val fields: List<FormField> = emptyList(),
    val confidence: Confidence,
    val provenance: List<Provenance> = emptyList(),
)

@Serializable
data class FormField(
    val fieldId: String,
    val label: String,
    val kind: FieldKind,
    val required: Boolean,
    val format: String? = null,
    val enumValues: List<String>? = null,
)

@Serializable
data class DynamicAction(
    val id: String,
    val label: String,
    val resourceId: String,
    val intent: ActionIntent,
    val risk: ActionRisk,
    val requiresConfirmation: Boolean,
    val binding: DynamicHttpBinding,
    /** Hidden read actions tried only when this preferred GET fails or returns no records. */
    val fallbackActionIds: List<String> = emptyList(),
    /** True when this action exists only as a runtime fallback and must not become a UI surface. */
    val fallbackOnly: Boolean = false,
    val capabilityIds: List<String> = emptyList(),
    val permissionIds: List<String> = emptyList(),
    val confidence: Confidence,
    val provenance: List<Provenance> = emptyList(),
    val effect: ActionEffect = ActionEffect.unspecified,
)

@Serializable
data class DynamicHttpBinding(
    val method: HttpMethod,
    val path: String,
    val pathParameters: List<HttpParameter> = emptyList(),
    val queryParameters: List<HttpParameter> = emptyList(),
    val body: HttpBody? = null,
    val auth: List<AuthRequirement> = emptyList(),
    /** Whether this declared endpoint requires Nextcloud's CSRF-safe API request header. */
    val apiRequestHeader: Boolean = false,
    val ocs: OcsMetadata? = null,
)

@Serializable
data class HttpParameter(
    val name: String,
    val required: Boolean,
    val schema: JsonElement,
    val source: ParameterSource,
)

@Serializable
enum class ParameterSource {
    userInput,
    resourceField,
    runtimeContext,
}

@Serializable
data class HttpBody(
    val contentType: String,
    val required: Boolean,
    val schema: JsonElement,
)

@Serializable
data class AuthRequirement(
    val scheme: String,
    val kind: AuthKind,
    val scopes: List<String> = emptyList(),
)

@Serializable
enum class AuthKind {
    nextcloudSession,
    basic,
    bearer,
    cookie,
    apiKey,
    oAuth2,
    openIdConnect,
}

@Serializable
data class OcsMetadata(
    val apiRequestHeader: Boolean,
    val responseDataPointer: String,
    val responseMetaPointer: String,
    val formatQueryParameter: String? = null,
)

@Serializable
data class Provenance(
    val kind: ProvenanceKind,
    val source: String,
    val detail: String,
)

@Serializable
enum class ProvenanceKind {
    appMetadata,
    capability,
    advertisedOpenApi,
    successfulReadObservation,
    verifiedAdapter,
    verifiedAppPackage,
    appStoreLinkedSourceTag,
    deterministicInference,
}

@Serializable
data class DynamicWarning(
    val code: String,
    val message: String,
)

@Serializable
data class DynamicDiscoveryInput(
    val app: AppIdentity,
    val endpointPolicy: EndpointPolicy,
    val capabilities: List<CapabilityFact> = emptyList(),
    val advertisedOpenApi: AdvertisedOpenApi? = null,
    val successfulReads: List<SuccessfulReadObservation> = emptyList(),
)

@Serializable
data class AdvertisedOpenApi(
    val documentUrl: String,
    val document: JsonElement,
    val trust: OpenApiTrust = OpenApiTrust.sameOriginAdvertisement,
)

@Serializable
enum class OpenApiTrust {
    sameOriginAdvertisement,
    nextcloudSignedAppPackage,
    nextcloudSignedCompatibleAppPackage,
    appStoreLinkedExactGitHubTag,
    appStoreLinkedCompatibleGitHubTag,
}

@Serializable
data class SuccessfulReadObservation(
    val operationId: String? = null,
    val label: String? = null,
    val path: String,
    val queryParameters: List<ObservedQueryParameter> = emptyList(),
    val status: Int,
    val contentType: String,
    val response: JsonElement,
    val permissionIds: List<String> = emptyList(),
    val ocs: Boolean,
)

@Serializable
data class ObservedQueryParameter(
    val name: String,
    val required: Boolean,
    val schema: JsonElement,
)
