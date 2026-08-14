package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.app.publicContentSha256
import dev.obiente.nextcloudnative.nativeui.model.ActionEffect
import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.EvidenceSource
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal const val NATIVE_CREATE_MUTATION_NAMESPACE = "collection-create-v1"

internal enum class NativeCreateMutationPhase {
    Staged,
    TransportMayHaveObserved,
}

internal enum class NativeCreateMutationMatchKind {
    NewRecord,
    NestedRecord,
}

internal data class NativeCreateMutationPostcondition(
    val actionId: String,
    val readActionId: String,
    val resourceId: String,
    val bindingValues: Map<String, String>,
    val baselineRecordIds: Set<String>,
    val expectedRecordValues: Map<String, String>,
    val requestValues: Map<String, String>,
    val confirmed: Boolean,
    val phase: NativeCreateMutationPhase,
    val matchKind: NativeCreateMutationMatchKind,
    val parentRecordId: String?,
    val nestedCollectionFieldId: String?,
) {
    internal fun matches(record: NativeRecord): Boolean {
        if (!record.actionSafeIdentity || !record.actionBindingProvenanceValid) return false
        return when (matchKind) {
            NativeCreateMutationMatchKind.NewRecord ->
                record.id !in baselineRecordIds && expectedRecordValues.all { (fieldId, expected) ->
                    record.values[fieldId]?.trim() == expected
                }
            NativeCreateMutationMatchKind.NestedRecord -> {
                if (record.id != parentRecordId) return false
                val fieldId = nestedCollectionFieldId ?: return false
                val items = record.completeStructuredObjectList(fieldId) ?: return false
                items.any { values ->
                    expectedRecordValues.all { (expectedFieldId, expected) ->
                        values[expectedFieldId]?.trim() == expected
                    } && nativeCreateExpectedIdentity(
                        expectedRecordValues.keys.associateWith { expectedFieldId ->
                            values[expectedFieldId]?.trim().orEmpty()
                        },
                    ) !in baselineRecordIds
                }
            }
        }
    }
}

internal data class NativeCreateMutationRecoveryPlan(
    val action: ActionSpec,
    val readActionId: String,
    val resourceId: String,
    private val bindingValues: Map<String, String>,
    private val baselineRecordIds: Set<String>,
    private val expectedFieldIds: Set<String>,
    private val nestedBodyFieldId: String?,
    private val matchKind: NativeCreateMutationMatchKind = NativeCreateMutationMatchKind.NewRecord,
    private val parentRecordId: String? = null,
    private val nestedCollectionFieldId: String? = null,
) {
    val pendingKey: NativePendingMutationKey = NativePendingMutationKey(
        actionId = "$NATIVE_CREATE_MUTATION_NAMESPACE:${action.id}",
        targetRecordId = nativeCreateMutationScopeDigest(
            resourceId = resourceId,
            readActionId = readActionId,
            bindingValues = bindingValues,
            matchKind = matchKind,
            parentRecordId = parentRecordId,
            nestedCollectionFieldId = nestedCollectionFieldId,
        ),
    )

    internal fun stage(
        request: NativeActionRequest.Submit,
        phase: NativeCreateMutationPhase,
    ): Map<String, String>? {
        if (request.action.id != action.id || request.values.size > MAX_CREATE_REQUEST_VALUES) return null
        if (request.values.any { (name, value) ->
                name.isBlank() || name.length > MAX_CREATE_FIELD_ID_LENGTH ||
                    value.length > MAX_CREATE_VALUE_LENGTH || '\u0000' in value
            }
        ) {
            return null
        }
        val expected = expectedRecordValues(request.values) ?: return null
        if (expected.isEmpty() || expected.keys.any { it !in expectedFieldIds }) return null
        return mapOf(
            CREATE_MARKER_VERSION_KEY to CREATE_MARKER_VERSION,
            CREATE_MARKER_PHASE_KEY to phase.name,
            CREATE_MARKER_ACTION_KEY to action.id,
            CREATE_MARKER_READ_ACTION_KEY to readActionId,
            CREATE_MARKER_RESOURCE_KEY to resourceId,
            CREATE_MARKER_BINDINGS_KEY to bindingValues.encodeStringMap(),
            CREATE_MARKER_BASELINE_KEY to JsonArray(
                baselineRecordIds.sorted().map(::JsonPrimitive),
            ).toString(),
            CREATE_MARKER_EXPECTED_KEY to expected.encodeStringMap(),
            CREATE_MARKER_REQUEST_KEY to request.values.encodeStringMap(),
            CREATE_MARKER_CONFIRMED_KEY to request.confirmed.toString(),
            CREATE_MARKER_MATCH_KIND_KEY to matchKind.name,
            CREATE_MARKER_PARENT_RECORD_KEY to parentRecordId.orEmpty(),
            CREATE_MARKER_NESTED_COLLECTION_KEY to nestedCollectionFieldId.orEmpty(),
        )
    }

    private fun expectedRecordValues(requestValues: Map<String, String>): Map<String, String>? {
        val nestedField = nestedBodyFieldId
        if (nestedField == null) {
            return action.binding.bodyFieldNames.associateWith { fieldId ->
                requestValues[fieldId]?.trim()?.takeIf(String::isNotEmpty) ?: return null
            }
        }
        val item = requestValues[nestedField]?.let { encoded ->
            runCatching { Json.parseToJsonElement(encoded) as? JsonArray }.getOrNull()
        }?.singleOrNull() as? JsonObject ?: return null
        if (item.isEmpty() || item.keys.any { it !in expectedFieldIds }) return null
        return item.mapValues { (_, element) ->
            (element as? JsonPrimitive)?.contentOrNull ?: return null
        }.mapValues { (_, value) -> value.trim() }
            .takeIf { values -> values.values.all(String::isNotEmpty) }
    }
}

internal fun nativeChoresInviteMutationRecoveryPlan(
    schema: NativeAppSchema,
    activeReadAction: ActionSpec,
    resource: ResourceSpec,
    createPlan: NativeRecordFormActionPlan,
    records: List<NativeRecord>,
    navigationContext: Map<String, String>,
    collectionComplete: Boolean,
): NativeCreateMutationRecoveryPlan? {
    val action = createPlan.action
    if (
        schema.app.id != "chores" || schema.app.version != "0.1.0" || !collectionComplete ||
        action.intent != ActionIntent.create || action.effect != ActionEffect.create ||
        action.risk == ActionRisk.readOnly || action.binding.method != HttpMethod.POST ||
        action.binding.path != "/apps/chores/api/v1.0/team/{teamId}/invites" ||
        action.resourceId != resource.id || action.binding.bodyFieldNames != listOf("userId") ||
        action.binding.requiredBodyFieldNames.toSet() != setOf("userId") ||
        activeReadAction.intent !in setOf(ActionIntent.list, ActionIntent.read) ||
        activeReadAction.binding.method != HttpMethod.GET ||
        activeReadAction.binding.path != "/apps/chores/api/v1.0/team" ||
        activeReadAction.resourceId != resource.id ||
        action.confidence != Confidence.verified || activeReadAction.confidence != Confidence.verified ||
        action.evidence.none { evidence -> evidence.source == EvidenceSource.verifiedAppPackage } ||
        activeReadAction.evidence.none { evidence -> evidence.source == EvidenceSource.verifiedAppPackage }
    ) {
        return null
    }
    val team = records.singleOrNull()?.takeIf { record ->
        record.actionSafeIdentity && record.actionBindingProvenanceValid && record.id.isNotBlank()
    } ?: return null
    val teamId = navigationContext["teamId"]?.trim()?.takeIf(String::isNotEmpty) ?: return null
    if (team.id != teamId || teamId.length > MAX_CREATE_RECORD_ID_LENGTH) return null
    val invites = team.completeStructuredObjectList("invites") ?: return null
    val baseline = invites.map { invite ->
        val userId = invite["userId"]?.trim()?.takeIf(String::isNotEmpty) ?: return null
        nativeCreateExpectedIdentity(mapOf("userId" to userId))
    }
    if (baseline.distinct().size != baseline.size || baseline.size > MAX_CREATE_BASELINE_RECORDS) return null
    return NativeCreateMutationRecoveryPlan(
        action = action,
        readActionId = activeReadAction.id,
        resourceId = resource.id,
        bindingValues = mapOf("teamId" to teamId),
        baselineRecordIds = baseline.toSet(),
        expectedFieldIds = setOf("userId"),
        nestedBodyFieldId = null,
        matchKind = NativeCreateMutationMatchKind.NestedRecord,
        parentRecordId = team.id,
        nestedCollectionFieldId = "invites",
    )
}

internal fun nativeCreateMutationRecoveryPlan(
    schema: NativeAppSchema,
    activeReadAction: ActionSpec,
    resource: ResourceSpec,
    createPlan: NativeRecordFormActionPlan,
    records: List<NativeRecord>,
    navigationContext: Map<String, String>,
    collectionComplete: Boolean,
): NativeCreateMutationRecoveryPlan? {
    val action = createPlan.action
    if (
        !collectionComplete || records.size > MAX_CREATE_BASELINE_RECORDS ||
        action.intent != ActionIntent.create || action.effect != ActionEffect.create ||
        action.risk == ActionRisk.readOnly || action.binding.method != HttpMethod.POST ||
        action.confidence != Confidence.verified || activeReadAction.confidence != Confidence.verified ||
        action.evidence.none { evidence -> evidence.source == EvidenceSource.verifiedAppPackage } ||
        activeReadAction.evidence.none { evidence -> evidence.source == EvidenceSource.verifiedAppPackage } ||
        activeReadAction.intent !in setOf(ActionIntent.list, ActionIntent.read) ||
        activeReadAction.binding.method != HttpMethod.GET ||
        action.resourceId != resource.id || activeReadAction.resourceId != resource.id ||
        action.binding.path.substringBefore('?').trimEnd('/') !=
        activeReadAction.binding.path.substringBefore('?').trimEnd('/') ||
        schema.actions.count { candidate -> candidate.id == action.id } != 1 ||
        schema.actions.count { candidate -> candidate.id == activeReadAction.id } != 1 ||
        records.any { record ->
            !record.actionSafeIdentity || !record.actionBindingProvenanceValid || record.id.isBlank() ||
                record.id.length > MAX_CREATE_RECORD_ID_LENGTH
        } || records.map(NativeRecord::id).distinct().size != records.size
    ) {
        return null
    }
    val bindingNames = (
        activeReadAction.binding.pathParameterNames +
            activeReadAction.binding.requiredPathParameterNames +
            activeReadAction.binding.queryParameterNames +
            activeReadAction.binding.requiredQueryParameterNames
        ).distinct()
    val requiredNames = (
        activeReadAction.binding.requiredPathParameterNames +
            activeReadAction.binding.requiredQueryParameterNames
        ).toSet()
    val bindingValues = bindingNames.mapNotNull { name ->
        navigationContext[name]?.trim()?.takeIf(String::isNotEmpty)?.let { value -> name to value }
    }.toMap()
    if (!bindingValues.keys.containsAll(requiredNames)) return null
    if (bindingValues.any { (name, value) ->
            name.length > MAX_CREATE_FIELD_ID_LENGTH || value.length > MAX_CREATE_VALUE_LENGTH || '\u0000' in value
        }
    ) {
        return null
    }
    if (records.any { record ->
            bindingValues.any { (name, value) ->
                record.bindingContext[name]?.let { recordValue -> recordValue != value } == true
            }
        }
    ) {
        return null
    }
    val shape = action.nativeCreateExpectedShape(resource) ?: return null
    return NativeCreateMutationRecoveryPlan(
        action = action,
        readActionId = activeReadAction.id,
        resourceId = resource.id,
        bindingValues = bindingValues,
        baselineRecordIds = records.mapTo(linkedSetOf(), NativeRecord::id),
        expectedFieldIds = shape.expectedFieldIds,
        nestedBodyFieldId = shape.nestedBodyFieldId,
    )
}

internal suspend fun executeNativeCreateMutation(
    plan: NativeCreateMutationRecoveryPlan,
    request: NativeActionRequest.Submit,
    actionExecutor: NativeActionExecutor,
    pendingMutationStore: NativePendingMutationStore,
): NativeActionExecutionResult {
    val key = plan.pendingKey
    val existing = runCatching { pendingMutationStore.load(key) }.getOrElse { failure ->
        return NativeActionExecutionResult.Failure(
            failure.message ?: "The saved create recovery marker could not be read.",
            NativeActionFailureOutcome.Unknown,
        )
    }
    var stagedMarker = existing
    var persistedRequest = request
    if (existing != null) {
        val pending = nativeCreateMutationPostcondition(key, existing)
            ?: return NativeActionExecutionResult.Failure(
                "The saved create recovery marker is invalid.",
                NativeActionFailureOutcome.Unknown,
            )
        val matchesCurrentRequest = pending.requestValues == request.values &&
            pending.confirmed == request.confirmed
        if (runCatching { pendingMutationStore.postconditionSatisfied(key, existing) }.getOrDefault(false)) {
            runCatching { pendingMutationStore.clear(key) }.getOrElse { failure ->
                return NativeActionExecutionResult.Failure(
                    failure.message ?: "The confirmed create recovery marker could not be cleared.",
                    NativeActionFailureOutcome.Unknown,
                )
            }
            if (matchesCurrentRequest) {
                return NativeActionExecutionResult.Success("The create request is confirmed by the server.")
            }
            stagedMarker = null
        } else if (pending.phase == NativeCreateMutationPhase.TransportMayHaveObserved) {
            return NativeActionExecutionResult.Failure(
                "The earlier create request is still awaiting authoritative server confirmation.",
                NativeActionFailureOutcome.Unknown,
            )
        } else if (!matchesCurrentRequest) {
            runCatching { pendingMutationStore.clear(key) }.getOrElse { failure ->
                return NativeActionExecutionResult.Failure(
                    failure.message ?: "The superseded staged create could not be cleared.",
                    NativeActionFailureOutcome.Rejected,
                )
            }
            stagedMarker = null
        } else {
            persistedRequest = NativeActionRequest.Submit(
                action = plan.action,
                values = pending.requestValues,
                confirmed = pending.confirmed,
            )
        }
    }
    if (stagedMarker == null) {
        stagedMarker = plan.stage(request, NativeCreateMutationPhase.Staged)
            ?: return NativeActionExecutionResult.Failure(
                "This create request could not be staged safely.",
                NativeActionFailureOutcome.Rejected,
            )
        runCatching { pendingMutationStore.save(key, stagedMarker) }.getOrElse { failure ->
            return NativeActionExecutionResult.Failure(
                failure.message ?: "This create request could not be staged safely.",
                NativeActionFailureOutcome.Rejected,
            )
        }
    }
    val transportMarker = stagedMarker +
        (CREATE_MARKER_PHASE_KEY to NativeCreateMutationPhase.TransportMayHaveObserved.name)
    runCatching { pendingMutationStore.save(key, transportMarker) }.getOrElse { failure ->
        return NativeActionExecutionResult.Failure(
            failure.message ?: "This create request could not enter its durable send phase.",
            NativeActionFailureOutcome.Rejected,
        )
    }
    val result = actionExecutor.execute(persistedRequest)
    if ((result as? NativeActionExecutionResult.Failure)?.outcome == NativeActionFailureOutcome.Rejected) {
        // A definitive rejection is safe to retry. Restore the pre-transport phase before clearing
        // so a failed clear cannot leave a known-rejected request permanently marked ambiguous.
        return runCatching {
            pendingMutationStore.save(key, checkNotNull(stagedMarker))
            pendingMutationStore.clear(key)
        }.fold(
            onSuccess = { result },
            onFailure = { failure ->
                NativeActionExecutionResult.Failure(
                    failure.message ?: "The rejected create recovery marker could not be cleared.",
                    NativeActionFailureOutcome.Unknown,
                )
            },
        )
    }
    if (runCatching { pendingMutationStore.postconditionSatisfied(key, transportMarker) }.getOrDefault(false)) {
        return clearConfirmedCreateMarker(pendingMutationStore, key)
    }
    return when (result) {
        is NativeActionExecutionResult.Success -> NativeActionExecutionResult.Failure(
            "The server accepted the create request, but refreshed data does not confirm it yet.",
            NativeActionFailureOutcome.Unknown,
        )
        is NativeActionExecutionResult.Failure -> result
    }
}

internal fun nativeCreateMutationPostcondition(
    key: NativePendingMutationKey,
    values: Map<String, String>,
): NativeCreateMutationPostcondition? {
    if (!key.actionId.startsWith("$NATIVE_CREATE_MUTATION_NAMESPACE:")) return null
    if (values.keys != CREATE_MARKER_KEYS || values[CREATE_MARKER_VERSION_KEY] != CREATE_MARKER_VERSION) return null
    val actionId = values[CREATE_MARKER_ACTION_KEY]?.takeSafeCreateId() ?: return null
    if (key.actionId != "$NATIVE_CREATE_MUTATION_NAMESPACE:$actionId") return null
    val readActionId = values[CREATE_MARKER_READ_ACTION_KEY]?.takeSafeCreateId() ?: return null
    val resourceId = values[CREATE_MARKER_RESOURCE_KEY]?.takeSafeCreateId() ?: return null
    val bindings = values[CREATE_MARKER_BINDINGS_KEY]?.decodeStringMap() ?: return null
    val baseline = values[CREATE_MARKER_BASELINE_KEY]?.decodeStringSet(MAX_CREATE_BASELINE_RECORDS) ?: return null
    val expected = values[CREATE_MARKER_EXPECTED_KEY]?.decodeStringMap() ?: return null
    val request = values[CREATE_MARKER_REQUEST_KEY]?.decodeStringMap() ?: return null
    if (expected.isEmpty() || request.isEmpty()) return null
    val confirmed = when (values[CREATE_MARKER_CONFIRMED_KEY]) {
        "true" -> true
        "false" -> false
        else -> return null
    }
    val phase = NativeCreateMutationPhase.entries.firstOrNull {
        it.name == values[CREATE_MARKER_PHASE_KEY]
    } ?: return null
    val matchKind = NativeCreateMutationMatchKind.entries.firstOrNull {
        it.name == values[CREATE_MARKER_MATCH_KIND_KEY]
    } ?: return null
    val parentRecordId = values[CREATE_MARKER_PARENT_RECORD_KEY]
        ?.takeIf(String::isNotEmpty)
        ?.takeSafeCreateRecordId()
    val nestedCollectionFieldId = values[CREATE_MARKER_NESTED_COLLECTION_KEY]
        ?.takeIf(String::isNotEmpty)
        ?.takeSafeCreateId()
    if (
        (matchKind == NativeCreateMutationMatchKind.NewRecord &&
            (parentRecordId != null || nestedCollectionFieldId != null)) ||
        (matchKind == NativeCreateMutationMatchKind.NestedRecord &&
            (parentRecordId == null || nestedCollectionFieldId == null))
    ) {
        return null
    }
    if (
        key.targetRecordId != nativeCreateMutationScopeDigest(
            resourceId = resourceId,
            readActionId = readActionId,
            bindingValues = bindings,
            matchKind = matchKind,
            parentRecordId = parentRecordId,
            nestedCollectionFieldId = nestedCollectionFieldId,
        )
    ) {
        return null
    }
    return NativeCreateMutationPostcondition(
        actionId = actionId,
        readActionId = readActionId,
        resourceId = resourceId,
        bindingValues = bindings,
        baselineRecordIds = baseline,
        expectedRecordValues = expected,
        requestValues = request,
        confirmed = confirmed,
        phase = phase,
        matchKind = matchKind,
        parentRecordId = parentRecordId,
        nestedCollectionFieldId = nestedCollectionFieldId,
    )
}

private suspend fun clearConfirmedCreateMarker(
    store: NativePendingMutationStore,
    key: NativePendingMutationKey,
): NativeActionExecutionResult = runCatching { store.clear(key) }.fold(
    onSuccess = { NativeActionExecutionResult.Success("The created record is confirmed by the server.") },
    onFailure = { failure ->
        NativeActionExecutionResult.Failure(
            failure.message ?: "The confirmed create recovery marker could not be cleared.",
            NativeActionFailureOutcome.Unknown,
        )
    },
)

private data class NativeCreateExpectedShape(
    val expectedFieldIds: Set<String>,
    val nestedBodyFieldId: String?,
)

private fun ActionSpec.nativeCreateExpectedShape(resource: ResourceSpec): NativeCreateExpectedShape? {
    val bodyFields = binding.bodyFieldNames.distinct()
    if (bodyFields.isEmpty() || bodyFields.size > MAX_CREATE_EXPECTED_FIELDS) return null
    val resourceFields = resource.fields.mapTo(hashSetOf()) { field -> field.id }
    val bodySchema = binding.bodySchema as? JsonObject
    if (bodyFields.size == 1 && bodySchema != null) {
        val bodyField = bodyFields.single()
        val property = (bodySchema["properties"] as? JsonObject)?.get(bodyField) as? JsonObject
        val items = property?.takeIf { schema -> schema["type"].jsonContent() == "array" }
            ?.get("items") as? JsonObject
        val itemFields = (items?.get("properties") as? JsonObject)?.keys.orEmpty()
        if (
            property?.get("maxItems").jsonContent()?.toIntOrNull() == 1 &&
            itemFields.isNotEmpty() && itemFields.size <= MAX_CREATE_EXPECTED_FIELDS &&
            resourceFields.containsAll(itemFields)
        ) {
            return NativeCreateExpectedShape(itemFields, bodyField)
        }
    }
    return bodyFields.takeIf(resourceFields::containsAll)
        ?.toSet()
        ?.let { fields -> NativeCreateExpectedShape(fields, null) }
}

private fun JsonElement?.jsonContent(): String? = (this as? JsonPrimitive)?.contentOrNull

private fun Map<String, String>.encodeStringMap(): String = JsonObject(
    toSortedMap().mapValues { (_, value) -> JsonPrimitive(value) },
).toString()

private fun String.decodeStringMap(): Map<String, String>? {
    if (length > MAX_CREATE_ENCODED_VALUE_LENGTH) return null
    val objectValue = runCatching { Json.parseToJsonElement(this) as? JsonObject }.getOrNull() ?: return null
    if (objectValue.size > MAX_CREATE_REQUEST_VALUES) return null
    return objectValue.mapValues { (name, element) ->
        if (name.isBlank() || name.length > MAX_CREATE_FIELD_ID_LENGTH) return null
        (element as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
            ?.takeIf { value -> value.length <= MAX_CREATE_VALUE_LENGTH && '\u0000' !in value }
            ?: return null
    }
}

private fun String.decodeStringSet(limit: Int): Set<String>? {
    if (length > MAX_CREATE_ENCODED_VALUE_LENGTH) return null
    val values = runCatching { Json.parseToJsonElement(this) as? JsonArray }.getOrNull() ?: return null
    if (values.size > limit) return null
    val decoded = values.map { element ->
        (element as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
            ?.takeIf { value -> value.isNotBlank() && value.length <= MAX_CREATE_RECORD_ID_LENGTH }
            ?: return null
    }
    return decoded.toSet().takeIf { it.size == decoded.size }
}

private fun String.takeSafeCreateId(): String? = takeIf { value ->
    value.isNotBlank() && value.length <= MAX_CREATE_FIELD_ID_LENGTH && '\u0000' !in value
}

private fun String.takeSafeCreateRecordId(): String? = takeIf { value ->
    value.isNotBlank() && value.length <= MAX_CREATE_RECORD_ID_LENGTH && '\u0000' !in value
}

private fun nativeCreateMutationScopeDigest(
    resourceId: String,
    readActionId: String,
    bindingValues: Map<String, String>,
    matchKind: NativeCreateMutationMatchKind,
    parentRecordId: String?,
    nestedCollectionFieldId: String?,
): String = publicContentSha256(
    buildString {
        listOf(
            resourceId,
            readActionId,
            matchKind.name,
            parentRecordId.orEmpty(),
            nestedCollectionFieldId.orEmpty(),
        ).forEach { value ->
            append(value.length)
            append(':')
            append(value)
            append('|')
        }
        bindingValues.toSortedMap().forEach { (name, value) ->
            append(name.length)
            append(':')
            append(name)
            append('=')
            append(value.length)
            append(':')
            append(value)
            append('|')
        }
    }.encodeToByteArray(),
)

private fun NativeRecord.completeStructuredObjectList(fieldId: String): List<Map<String, String?>>? {
    val list = structuredValues[fieldId] as? NativeStructuredValue.ListValue ?: return null
    if (list.omittedItems != 0) return null
    return list.items.map { item ->
        val objectValue = item as? NativeStructuredValue.ObjectValue ?: return null
        if (objectValue.omittedEntries != 0) return null
        objectValue.entries.associate { entry ->
            val scalar = entry.value as? NativeStructuredValue.Scalar ?: return null
            entry.key to scalar.value
        }
    }
}

private fun nativeCreateExpectedIdentity(values: Map<String, String>): String = publicContentSha256(
    values.toSortedMap().entries.joinToString("\u0000") { (name, value) -> "$name\u0000$value" }
        .encodeToByteArray(),
)

private const val CREATE_MARKER_VERSION = "1"
private const val CREATE_MARKER_VERSION_KEY = "version"
private const val CREATE_MARKER_PHASE_KEY = "phase"
private const val CREATE_MARKER_ACTION_KEY = "actionId"
private const val CREATE_MARKER_READ_ACTION_KEY = "readActionId"
private const val CREATE_MARKER_RESOURCE_KEY = "resourceId"
private const val CREATE_MARKER_BINDINGS_KEY = "bindingValues"
private const val CREATE_MARKER_BASELINE_KEY = "baselineRecordIds"
private const val CREATE_MARKER_EXPECTED_KEY = "expectedRecordValues"
private const val CREATE_MARKER_REQUEST_KEY = "requestValues"
private const val CREATE_MARKER_CONFIRMED_KEY = "confirmed"
private const val CREATE_MARKER_MATCH_KIND_KEY = "matchKind"
private const val CREATE_MARKER_PARENT_RECORD_KEY = "parentRecordId"
private const val CREATE_MARKER_NESTED_COLLECTION_KEY = "nestedCollectionFieldId"
private val CREATE_MARKER_KEYS = setOf(
    CREATE_MARKER_VERSION_KEY,
    CREATE_MARKER_PHASE_KEY,
    CREATE_MARKER_ACTION_KEY,
    CREATE_MARKER_READ_ACTION_KEY,
    CREATE_MARKER_RESOURCE_KEY,
    CREATE_MARKER_BINDINGS_KEY,
    CREATE_MARKER_BASELINE_KEY,
    CREATE_MARKER_EXPECTED_KEY,
    CREATE_MARKER_REQUEST_KEY,
    CREATE_MARKER_CONFIRMED_KEY,
    CREATE_MARKER_MATCH_KIND_KEY,
    CREATE_MARKER_PARENT_RECORD_KEY,
    CREATE_MARKER_NESTED_COLLECTION_KEY,
)

private const val MAX_CREATE_BASELINE_RECORDS = 512
private const val MAX_CREATE_EXPECTED_FIELDS = 64
private const val MAX_CREATE_REQUEST_VALUES = 96
private const val MAX_CREATE_FIELD_ID_LENGTH = 256
private const val MAX_CREATE_RECORD_ID_LENGTH = 512
private const val MAX_CREATE_VALUE_LENGTH = 32 * 1024
private const val MAX_CREATE_ENCODED_VALUE_LENGTH = 96 * 1024
