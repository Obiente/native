package dev.obiente.nextcloudnative.nativeui.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonElement

@Serializable
data class NativeAppSchema(
    val schemaVersion: String,
    val app: AppIdentity,
    val confidence: Confidence,
    val resources: List<ResourceSpec> = emptyList(),
    val views: List<ViewSpec> = emptyList(),
    val actions: List<ActionSpec> = emptyList(),
    val relationships: List<ResourceRelationshipSpec> = emptyList(),
    val warnings: List<CompilerWarning> = emptyList(),
) {
    fun resource(id: String): ResourceSpec? = resources.firstOrNull { it.id == id }

    fun action(id: String): ActionSpec? = actions.firstOrNull { it.id == id }
}

@Serializable
data class ResourceRelationshipSpec(
    val parentResourceId: String,
    val childResourceId: String,
    val parentFieldId: String,
    val childFieldId: String?,
    val confidence: Confidence,
)

@Serializable
data class AppIdentity(
    val id: String,
    val name: String,
    val version: String,
)

@Serializable
enum class Confidence {
    low,
    medium,
    high,
    verified,
}

@Serializable
data class ResourceSpec(
    val id: String,
    val name: String,
    val confidence: Confidence,
    val fields: List<FieldSpec> = emptyList(),
    val evidence: List<Evidence> = emptyList(),
)

@Serializable
data class FieldSpec(
    val id: String,
    val label: String,
    val kind: FieldKind,
    val required: Boolean,
    val readOnly: Boolean,
    val format: String? = null,
    val enumValues: List<String>? = null,
)

@Serializable
enum class FieldKind {
    string,
    longText,
    integer,
    decimal,
    boolean,
    date,
    dateTime,
    currency,
    image,
    file,
    userReference,
    enumeration,
    @SerialName("object")
    objectValue,
    unknown,
}

@Serializable
data class ViewSpec(
    val id: String,
    val title: String,
    val resourceId: String,
    val component: NativeComponent,
    val sourceActionId: String,
    val confidence: Confidence,
    val evidence: List<Evidence> = emptyList(),
    val compositeDataGrid: CompositeDataGridSpec? = null,
)

@Serializable
data class CompositeDataGridSpec(
    val parentResourceId: String,
    val columnResourceId: String,
    val rowResourceId: String,
    val columnSourceActionId: String,
    val rowSourceActionId: String,
    val columnIdentityFieldId: String,
    val columnAliasFieldId: String?,
    val columnTitleFieldId: String,
    val columnTypeFieldId: String?,
    val columnOrderFieldId: String?,
    val rowCellMapFieldId: String,
)

@Serializable
enum class NativeComponent {
    dashboard,
    fileBrowser,
    collectionList,
    mediaGrid,
    detail,
    form,
    timeline,
    calendar,
    board,
    mailbox,
    contactList,
    taskList,
    dataTable,
    mediaLibrary,
    recipeList,
    documentEditor,
    conversationList,
    chatThread,
}

@Serializable
data class ActionSpec(
    val id: String,
    val label: String,
    val resourceId: String,
    val binding: ApiBinding,
    val intent: ActionIntent,
    val risk: ActionRisk,
    val requiresConfirmation: Boolean,
    val confidence: Confidence,
    val inputSchema: JsonElement? = null,
    val evidence: List<Evidence> = emptyList(),
    val effect: ActionEffect = ActionEffect.unspecified,
)

@Serializable
data class ApiBinding(
    val method: HttpMethod,
    val path: String,
    val operationId: String,
    val pathParameterNames: List<String> = emptyList(),
    val requiredPathParameterNames: List<String> = emptyList(),
    val queryParameterNames: List<String> = emptyList(),
    val requiredQueryParameterNames: List<String> = emptyList(),
    val bodyFieldNames: List<String> = emptyList(),
    val requiredBodyFieldNames: List<String> = emptyList(),
    val bodyContentType: String? = null,
    /** Exact validated request-body schema retained for semantic, schema-gated native writes. */
    val bodySchema: JsonElement? = null,
    val allowsObservedBodyFields: Boolean = false,
)

@Serializable
enum class HttpMethod {
    GET,
    POST,
    PUT,
    PATCH,
    DELETE,
}

@Serializable
enum class ActionIntent {
    list,
    read,
    create,
    update,
    delete,
    execute,
}

/**
 * Contract-derived user-visible effect of an action.
 *
 * HTTP methods do not describe product behavior: POST can create a record, toggle state, restore
 * one, reorder a collection, or run a batch command. Keeping the effect separate lets generic
 * renderers choose the right affordance and recovery behavior without knowing an app identity or
 * endpoint.
 */
@Serializable
enum class ActionEffect {
    unspecified,
    list,
    read,
    create,
    update,
    delete,
    permanentDelete,
    empty,
    toggle,
    archive,
    unarchive,
    restore,
    move,
    copy,
    reorder,
    batch,
    upload,
    assign,
    leave,
    clear,
    execute,
}

@Serializable
enum class ActionRisk {
    readOnly,
    mutating,
    destructive,
}

@Serializable
data class Evidence(
    val source: EvidenceSource,
    val detail: String,
)

@Serializable
enum class EvidenceSource {
    appMetadata,
    capability,
    openApi,
    accessibility,
    networkObservation,
    verifiedAdapter,
    verifiedAppPackage,
    appStoreLinkedSourceTag,
    localInference,
}

@Serializable
data class CompilerWarning(
    val code: String,
    val message: String,
)
