package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec

internal data class NativeBoardEditableField(
    val field: FieldSpec,
    val bodyFieldName: String,
)

internal data class NativeBoardEditPlan(
    val action: ActionSpec,
    val fields: List<NativeBoardEditableField>,
    val initialValues: Map<String, String>,
    private val bindingValues: Map<String, String>,
) {
    fun request(changes: Map<String, String>): NativeActionRequest.Submit {
        val submitted = fields.associate { editable ->
            editable.bodyFieldName to changes[editable.field.id].orEmpty().trim()
        }
        return NativeActionRequest.Submit(
            action = action,
            values = bindingValues + submitted,
            confirmed = true,
        )
    }
}

internal data class NativeBoardMoveTarget(
    val key: String,
    val title: String,
)

internal data class NativeBoardMovePlan(
    val action: ActionSpec,
    val laneBodyFieldName: String,
    val currentLaneKey: String,
    val targets: List<NativeBoardMoveTarget>,
    private val bindingValues: Map<String, String>,
) {
    fun request(targetLaneKey: String): NativeActionRequest.Submit {
        require(targets.any { it.key == targetLaneKey }) { "Choose a declared board lane." }
        return NativeActionRequest.Submit(
            action = action,
            values = bindingValues + (laneBodyFieldName to targetLaneKey),
            confirmed = true,
        )
    }
}

internal data class NativeBoardCardActionPlan(
    val edit: NativeBoardEditPlan?,
    val move: NativeBoardMovePlan?,
    val directActions: List<NativeBoardDirectActionPlan> = emptyList(),
)

internal enum class NativeBoardDirectActionKind {
    Complete,
    Reopen,
    Archive,
    Unarchive,
    Delete,
}

internal data class NativeBoardDirectActionPlan(
    val action: ActionSpec,
    val kind: NativeBoardDirectActionKind,
    val label: String,
    private val bindingValues: Map<String, String>,
) {
    fun request(): NativeActionRequest.Submit = NativeActionRequest.Submit(
        action = action,
        values = bindingValues,
        confirmed = true,
    )
}

internal data class NativeBoardCreatePlan(
    val action: ActionSpec,
    val lane: NativeBoardLane,
    val titleBodyFieldName: String,
    val descriptionBodyFieldName: String?,
    private val bindingValues: Map<String, String>,
) {
    fun request(title: String, description: String = ""): NativeActionRequest.Submit {
        val normalizedTitle = title.trim()
        require(normalizedTitle.isNotBlank()) { "Card title is required." }
        val submitted = buildMap {
            put(titleBodyFieldName, normalizedTitle)
            descriptionBodyFieldName?.let { put(it, description.trim()) }
        }
        return NativeActionRequest.Submit(
            action = action,
            values = bindingValues + submitted,
            confirmed = true,
        )
    }
}

internal enum class NativeBoardMoveVerification {
    WaitingForRefresh,
    Confirmed,
    NotMoved,
}

internal fun verifyNativeBoardMove(
    lanes: List<NativeBoardLane>,
    recordId: String,
    targetLaneKey: String,
    beforeFingerprint: String,
    refreshCompleted: Boolean,
): NativeBoardMoveVerification {
    val currentFingerprint = nativeBoardFingerprint(lanes)
    if (!refreshCompleted && currentFingerprint == beforeFingerprint) {
        return NativeBoardMoveVerification.WaitingForRefresh
    }
    return if (lanes.firstOrNull { it.key == targetLaneKey }?.records?.any { it.id == recordId } == true) {
        NativeBoardMoveVerification.Confirmed
    } else {
        NativeBoardMoveVerification.NotMoved
    }
}

internal fun nativeBoardFingerprint(lanes: List<NativeBoardLane>): String = lanes.joinToString("|") { lane ->
    lane.key + ":" + lane.records.joinToString(",") { record -> record.id }
}

/**
 * Resolves card mutations exclusively from schema shape and the selected record.
 *
 * There is deliberately no app identifier here. A Deck card, CRM opportunity, issue, or any other
 * workflow item receives the same actions when its contract exposes a uniquely bindable update or
 * lane mutation. Ambiguous or response-only identities remain read-only.
 */
internal fun nativeBoardCardActionPlan(
    schema: NativeAppSchema,
    resource: ResourceSpec,
    record: NativeRecord,
    lanes: List<NativeBoardLane>,
): NativeBoardCardActionPlan {
    val selectedLane = lanes.firstOrNull { lane ->
        lane.records.any { laneRecord -> laneRecord.id == record.id }
    }
    if (selectedLane?.actionBindingProvenanceValid == false) {
        return NativeBoardCardActionPlan(edit = null, move = null)
    }
    val laneContext = selectedLane?.contextValues.orEmpty().filterKeys { key ->
        val semantic = key.boardSemanticId()
        semantic != "id" && (semantic.endsWith("id") || semantic in BOARD_LANE_BODY_FIELD_NAMES)
    }
    val recordBindingValues = record.safeActionBindingValues()
        ?: return NativeBoardCardActionPlan(edit = null, move = null)
    val bindingValues = safeActionBindingValues(laneContext, recordBindingValues)
        ?: return NativeBoardCardActionPlan(edit = null, move = null)
    val actions = schema.actions.filter { action ->
        action.resourceId.sameBoardActionResource(resource.id) &&
            action.risk == ActionRisk.mutating &&
            action.binding.method in BOARD_MUTATION_METHODS &&
            action.binding.bodyFieldNames.isNotEmpty() &&
            action.binding.bodyContentType.normalizedContentType() in BOARD_BODY_CONTENT_TYPES
    }
    return NativeBoardCardActionPlan(
        edit = actions.mapNotNull { action ->
            action.toBoardEditCandidate(resource, bindingValues)
        }.singleHighestOrNull(),
        move = actions.mapNotNull { action ->
            action.toBoardMoveCandidate(record, lanes, bindingValues)
        }.singleHighestOrNull(),
        directActions = nativeBoardDirectActions(schema, resource, record, bindingValues),
    )
}

/**
 * Plans lane-scoped creation only when every required path/query/body value has one exact source.
 *
 * Empty lanes retain their parent context during nested-board expansion, so creation does not need
 * an existing card or an app-specific route assumption.
 */
internal fun nativeBoardLaneCreatePlan(
    schema: NativeAppSchema,
    resource: ResourceSpec,
    lane: NativeBoardLane,
): NativeBoardCreatePlan? {
    if (!lane.actionBindingProvenanceValid) return null
    return schema.actions.mapNotNull { action ->
        if (
            !action.resourceId.sameBoardActionResource(resource.id) ||
            action.intent != ActionIntent.create ||
            action.risk != ActionRisk.mutating ||
            action.binding.method != HttpMethod.POST ||
            action.binding.bodyContentType.normalizedContentType() !in BOARD_BODY_CONTENT_TYPES
        ) {
            return@mapNotNull null
        }
        val laneAliases = (
            action.binding.pathParameterNames +
                action.binding.queryParameterNames +
                action.binding.bodyFieldNames
            )
            .filter { name -> name.boardSemanticId() in BOARD_LANE_BODY_FIELD_NAMES }
            .associateWith { lane.key }
        val bindingValues = safeActionBindingValues(lane.contextValues, laneAliases)
            ?: return@mapNotNull null
        val titleField = action.binding.bodyFieldNames
            .mapNotNull { name ->
                BOARD_CREATE_TITLE_FIELDS[name.boardSemanticId()]?.let { priority -> name to priority }
            }
            .maxByOrNull(Pair<String, Int>::second)
            ?.first
            ?: return@mapNotNull null
        val descriptionField = action.binding.bodyFieldNames.singleOrNull {
            it.boardSemanticId() in BOARD_CREATE_DESCRIPTION_FIELDS
        }
        if (action.binding.hasFlatBoardActionCollision(listOfNotNull(titleField, descriptionField))) {
            return@mapNotNull null
        }
        val available = bindingValues.keys + titleField + listOfNotNull(descriptionField)
        if (!action.binding.canResolveExactBoardValues(available, action.resourceId)) return@mapNotNull null
        val semantic = "${
            action.id
        } ${action.label} ${action.binding.operationId} ${action.binding.path}".boardSemanticWords()
        val rank = when {
            "create" in semantic -> 500
            "add" in semantic -> 450
            "new" in semantic -> 400
            else -> 300
        } + action.binding.preferredBoardCreateRouteRank() +
            if (descriptionField != null) 10 else 0
        RankedBoardPlan(
            NativeBoardCreatePlan(
                action = action,
                lane = lane,
                titleBodyFieldName = titleField,
                descriptionBodyFieldName = descriptionField,
                bindingValues = bindingValues,
            ),
            rank,
        )
    }.singleHighestOrNull()
}

private fun nativeBoardDirectActions(
    schema: NativeAppSchema,
    resource: ResourceSpec,
    record: NativeRecord,
    bindingValues: Map<String, String>,
): List<NativeBoardDirectActionPlan> {
    val done = record.boardState(BOARD_DONE_FIELD_NAMES)
    val archived = record.boardState(BOARD_ARCHIVED_FIELD_NAMES)
    val candidates = schema.actions.mapNotNull { action ->
        if (!action.resourceId.sameBoardActionResource(resource.id)) return@mapNotNull null
        val kind = action.boardDirectActionKind() ?: return@mapNotNull null
        val resolvedBindingValues = action.withBoardIdentityAliases(
            values = bindingValues,
            recordId = record.id,
            canonicalIdentityAvailable = record.actionSafeIdentity,
        )
            ?: return@mapNotNull null
        if (
            kind == NativeBoardDirectActionKind.Delete &&
            (action.risk != ActionRisk.destructive || action.binding.method != HttpMethod.DELETE)
        ) {
            return@mapNotNull null
        }
        if (
            kind != NativeBoardDirectActionKind.Delete &&
            (action.risk != ActionRisk.mutating || action.binding.method !in BOARD_MUTATION_METHODS)
        ) {
            return@mapNotNull null
        }
        if (action.binding.bodyFieldNames.any { it.boardSemanticId() !in BOARD_DIRECT_IDENTITY_FIELDS }) {
            return@mapNotNull null
        }
        if (action.binding.hasFlatBoardActionCollision(action.binding.bodyFieldNames)) {
            return@mapNotNull null
        }
        if (!action.binding.canResolveExactBoardValues(resolvedBindingValues.keys, action.resourceId)) {
            return@mapNotNull null
        }
        if (
            kind == NativeBoardDirectActionKind.Complete && done != false ||
            kind == NativeBoardDirectActionKind.Reopen && done != true ||
            kind == NativeBoardDirectActionKind.Archive && archived != false ||
            kind == NativeBoardDirectActionKind.Unarchive && archived != true
        ) {
            return@mapNotNull null
        }
        val rank = action.binding.preferredBoardMoveRouteRank() +
            if (action.binding.path.trimEnd('/').substringAfterLast('/').boardSemanticId() in
                BOARD_DIRECT_ROUTE_NAMES
            ) {
                500
            } else {
                300
            }
        RankedBoardPlan(
            NativeBoardDirectActionPlan(
                action = action,
                kind = kind,
                label = kind.boardActionLabel(),
                bindingValues = resolvedBindingValues,
            ),
            rank,
        )
    }
    return NativeBoardDirectActionKind.entries.mapNotNull { kind ->
        candidates.filter { candidate -> candidate.plan.kind == kind }.singleHighestOrNull()
    }
}

private fun ActionSpec.withBoardIdentityAliases(
    values: Map<String, String>,
    recordId: String,
    canonicalIdentityAvailable: Boolean,
): Map<String, String>? {
    val aliases = buildMap<String, String> {
        if (!canonicalIdentityAvailable) return@buildMap
        val declaredNames = binding.pathParameterNames + binding.queryParameterNames + binding.bodyFieldNames
        declaredNames.forEach { name ->
            val normalized = name.boardSemanticId()
            val stem = normalized.removeSuffix("id")
            if (
                normalized.endsWith("id") &&
                stem.sameBoardActionResource(resourceId) &&
                keys.none { existing -> existing.boardSemanticId() == normalized }
            ) {
                put(name, recordId)
            }
        }
    }
    return safeActionBindingValues(values, aliases)
}

private fun ActionSpec.boardDirectActionKind(): NativeBoardDirectActionKind? {
    if (intent == ActionIntent.delete && binding.method == HttpMethod.DELETE) {
        return NativeBoardDirectActionKind.Delete
    }
    val terminal = binding.path.trimEnd('/').substringAfterLast('/').boardSemanticId()
    val semantic = "$id $label ${binding.operationId}".boardSemanticWords()
    return when {
        terminal in BOARD_REOPEN_ROUTE_NAMES ||
            semantic.any { it in BOARD_REOPEN_ROUTE_NAMES } -> NativeBoardDirectActionKind.Reopen
        terminal in BOARD_COMPLETE_ROUTE_NAMES ||
            semantic.any { it in BOARD_COMPLETE_ROUTE_NAMES } -> NativeBoardDirectActionKind.Complete
        terminal in BOARD_UNARCHIVE_ROUTE_NAMES ||
            semantic.any { it in BOARD_UNARCHIVE_ROUTE_NAMES } -> NativeBoardDirectActionKind.Unarchive
        terminal in BOARD_ARCHIVE_ROUTE_NAMES ||
            semantic.any { it in BOARD_ARCHIVE_ROUTE_NAMES } -> NativeBoardDirectActionKind.Archive
        else -> null
    }
}

private fun NativeBoardDirectActionKind.boardActionLabel(): String = when (this) {
    NativeBoardDirectActionKind.Complete -> "Mark complete"
    NativeBoardDirectActionKind.Reopen -> "Reopen"
    NativeBoardDirectActionKind.Archive -> "Archive"
    NativeBoardDirectActionKind.Unarchive -> "Unarchive"
    NativeBoardDirectActionKind.Delete -> "Delete"
}

private fun NativeRecord.boardState(fieldNames: Set<String>): Boolean? {
    val stateKey = values.keys.firstOrNull { key -> key.boardSemanticId() in fieldNames }
        ?: return null
    // APIs such as Deck serialize an explicitly incomplete card as `"done": null`. The key is
    // authoritative contract/data evidence; only a missing key means the state is unknown.
    val value = values[stateKey] ?: return false
    val normalized = value.trim().lowercase()
    return when (normalized) {
        "", "0", "false", "no", "null", "none" -> false
        "1", "true", "yes" -> true
        else -> normalized.toLongOrNull()?.let { it > 0 } ?: true
    }
}

private data class RankedBoardPlan<T>(val plan: T, val rank: Int)

private fun ActionSpec.toBoardEditCandidate(
    resource: ResourceSpec,
    bindingValues: Map<String, String>,
): RankedBoardPlan<NativeBoardEditPlan>? {
    if (intent != ActionIntent.update) return null
    val editableFields = binding.bodyFieldNames.mapNotNull { bodyName ->
        if (bodyName.boardSemanticId() in BOARD_NON_EDIT_FIELD_NAMES) return@mapNotNull null
        val field = resource.fields.singleOrNull {
            it.id.boardSemanticId() == bodyName.boardSemanticId()
        } ?: return@mapNotNull null
        if (field.readOnly) return@mapNotNull null
        NativeBoardEditableField(field, bodyName)
    }
    if (editableFields.isEmpty()) return null
    if (binding.hasFlatBoardActionCollision(editableFields.map(NativeBoardEditableField::bodyFieldName))) {
        return null
    }
    val available = bindingValues.keys + editableFields.map(NativeBoardEditableField::bodyFieldName)
    if (!binding.canResolveExactBoardValues(available, resourceId)) return null
    val initialValues = editableFields.associate { editable ->
        editable.field.id to bindingValues.valueForBoardName(editable.field.id).orEmpty()
    }
    val semantic = "$id $label ${binding.operationId} ${binding.path}".boardSemanticWords()
    val rank = when {
        "edit" in semantic -> 400
        "update" in semantic -> 350
        "patch" in semantic -> 320
        else -> 250
    } + editableFields.size.coerceAtMost(20)
    return RankedBoardPlan(
        NativeBoardEditPlan(this, editableFields, initialValues, bindingValues),
        rank,
    )
}

private fun ActionSpec.toBoardMoveCandidate(
    record: NativeRecord,
    lanes: List<NativeBoardLane>,
    bindingValues: Map<String, String>,
): RankedBoardPlan<NativeBoardMovePlan>? {
    if (intent !in setOf(ActionIntent.update, ActionIntent.execute)) return null
    val laneField = binding.bodyFieldNames.singleOrNull { it.boardSemanticId() in BOARD_LANE_BODY_FIELD_NAMES }
        ?: return null
    if (binding.hasFlatBoardActionCollision(listOf(laneField))) return null
    val currentLane = record.values.entries.firstOrNull {
        it.key.boardSemanticId() in BOARD_LANE_BODY_FIELD_NAMES
    }?.value?.trim()?.takeIf(String::isNotBlank) ?: return null
    val targets = lanes.asSequence()
        .filter { it.key != currentLane }
        .map { NativeBoardMoveTarget(it.key, it.title) }
        .distinctBy(NativeBoardMoveTarget::key)
        .toList()
    if (targets.isEmpty()) return null
    val available = bindingValues.keys + laneField
    if (!binding.canResolveExactBoardValues(available, resourceId)) return null
    val semantic = "$id $label ${binding.operationId} ${binding.path}".boardSemanticWords()
    val rank = when {
        "move" in semantic -> 500
        "reorder" in semantic -> 450
        "relocate" in semantic -> 430
        else -> 300
    } + binding.preferredBoardMoveRouteRank()
    return RankedBoardPlan(
        NativeBoardMovePlan(this, laneField, currentLane, targets, bindingValues),
        rank,
    )
}

private fun dev.obiente.nextcloudnative.nativeui.model.ApiBinding.canResolveExactBoardValues(
    available: Set<String>,
    actionResourceId: String,
): Boolean {
    val normalized = available.mapTo(mutableSetOf(), String::boardSemanticId)
    fun String.resolvable(): Boolean {
        val normalizedName = boardSemanticId()
        if (normalizedName in normalized) return true
        val identityStem = normalizedName.removeSuffix("id")
        return "id" in normalized &&
            normalizedName.endsWith("id") &&
            identityStem.sameBoardActionResource(actionResourceId)
    }
    return requiredPathParameterNames.all(String::resolvable) &&
        requiredQueryParameterNames.all(String::resolvable) &&
        requiredBodyFieldNames.all { it.boardSemanticId() in normalized }
}

/**
 * NativeActionRequest currently carries one flat value map. A submitted body field therefore
 * cannot safely have a different value from a path or query parameter with the same semantic name.
 */
private fun dev.obiente.nextcloudnative.nativeui.model.ApiBinding.hasFlatBoardActionCollision(
    submittedBodyFields: Collection<String>,
): Boolean {
    val routeFields = (pathParameterNames + queryParameterNames).mapTo(mutableSetOf(), String::boardSemanticId)
    return submittedBodyFields.any { it.boardSemanticId() in routeFields }
}

private fun <T> List<RankedBoardPlan<T>>.singleHighestOrNull(): T? {
    val highest = maxOfOrNull(RankedBoardPlan<T>::rank) ?: return null
    return filter { it.rank == highest }.singleOrNull()?.plan
}

private fun Map<String, String>.valueForBoardName(name: String): String? =
    entries.firstOrNull { it.key.boardSemanticId() == name.boardSemanticId() }?.value

private fun String?.normalizedContentType(): String? = this?.substringBefore(';')?.trim()?.lowercase()

private fun String.boardSemanticWords(): Set<String> = lowercase()
    .map { if (it.isLetterOrDigit()) it else ' ' }
    .joinToString("")
    .split(' ')
    .filter(String::isNotBlank)
    .toSet()

private fun String.boardSemanticId(): String = lowercase().filter(Char::isLetterOrDigit)

private fun String.sameBoardActionResource(other: String): Boolean {
    fun String.singular(): String = boardSemanticId().let {
        when {
            it.endsWith("ies") && it.length > 3 -> it.dropLast(3) + "y"
            it.endsWith("s") && !it.endsWith("ss") && it.length > 1 -> it.dropLast(1)
            else -> it
        }
    }
    return singular() == other.singular()
}

/**
 * Prefer a card-scoped mutation over a nested board/stack REST route when both are signed.
 *
 * This is route-shape ranking, not an app special case. It avoids rebinding stale source-lane path
 * parameters during a cross-lane move and matches the shorter mutation shape used by native board
 * clients. A unique nested route remains fully usable when it is the only declared contract.
 */
private fun dev.obiente.nextcloudnative.nativeui.model.ApiBinding.preferredBoardMoveRouteRank(): Int {
    val normalizedPath = path.lowercase()
    val nestedContainerPenalty = requiredPathParameterNames.count {
        it.boardSemanticId() in setOf("boardid", "stackid", "laneid", "columnid", "listid")
    } * 40
    val cardScoped = if (
        normalizedPath.contains("/cards/{cardid}/") ||
        normalizedPath.contains("/cards/{card}/")
    ) 100 else 0
    val nonVersionedNativeRoute = if (!normalizedPath.contains("/api/v")) 20 else 0
    return cardScoped + nonVersionedNativeRoute - nestedContainerPenalty
}

/**
 * Prefer a direct lane/body create route over a deeply nested API variant when both were signed.
 *
 * The chosen route still has to resolve every declared value exactly. Ranking only breaks a tie
 * between already-safe contracts and keeps equal duplicate contracts read-only.
 */
private fun dev.obiente.nextcloudnative.nativeui.model.ApiBinding.preferredBoardCreateRouteRank(): Int {
    val normalizedPath = path.lowercase()
    val laneInBody = if (bodyFieldNames.any { it.boardSemanticId() in BOARD_LANE_BODY_FIELD_NAMES }) 100 else 0
    val nestedParentPenalty = requiredPathParameterNames.count {
        it.boardSemanticId() in setOf("boardid", "stackid", "laneid", "columnid", "listid")
    } * 40
    val nonVersionedNativeRoute = if (!normalizedPath.contains("/api/v")) 30 else 0
    return laneInBody + nonVersionedNativeRoute - nestedParentPenalty
}

private val BOARD_MUTATION_METHODS = setOf(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH)
private val BOARD_BODY_CONTENT_TYPES = setOf(
    "application/json",
    "application/x-www-form-urlencoded",
    "multipart/form-data",
)
private val BOARD_LANE_BODY_FIELD_NAMES = setOf(
    "stackid",
    "laneid",
    "columnid",
    "listid",
    "stageid",
    "stack",
    "lane",
    "column",
    "list",
    "stage",
    "status",
    "state",
)
private val BOARD_NON_EDIT_FIELD_NAMES = BOARD_LANE_BODY_FIELD_NAMES + setOf(
    "id",
    "cardid",
    "taskid",
    "itemid",
    "order",
    "position",
    "sort",
    "sortorder",
    "index",
    "boardid",
    "projectid",
)
private val BOARD_CREATE_TITLE_FIELDS = mapOf(
    "subject" to 1,
    "name" to 2,
    "title" to 3,
)
private val BOARD_CREATE_DESCRIPTION_FIELDS = setOf("description", "details", "body", "content", "notes")
private val BOARD_DIRECT_IDENTITY_FIELDS = setOf("id", "cardid", "taskid", "itemid")
private val BOARD_DONE_FIELD_NAMES = setOf("done", "completed", "isdone", "iscompleted")
private val BOARD_ARCHIVED_FIELD_NAMES = setOf("archived", "isarchived")
private val BOARD_COMPLETE_ROUTE_NAMES = setOf("done", "complete", "completed", "finish")
private val BOARD_REOPEN_ROUTE_NAMES = setOf("undone", "reopen", "uncomplete", "incomplete")
private val BOARD_ARCHIVE_ROUTE_NAMES = setOf("archive", "archived")
private val BOARD_UNARCHIVE_ROUTE_NAMES = setOf("unarchive", "restore")
private val BOARD_DIRECT_ROUTE_NAMES = BOARD_COMPLETE_ROUTE_NAMES + BOARD_REOPEN_ROUTE_NAMES +
    BOARD_ARCHIVE_ROUTE_NAMES + BOARD_UNARCHIVE_ROUTE_NAMES
