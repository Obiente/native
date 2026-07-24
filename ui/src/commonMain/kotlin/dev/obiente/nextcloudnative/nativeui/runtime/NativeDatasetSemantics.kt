package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.CompositeDataGridSpec
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import kotlin.math.abs
import kotlin.math.round
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal data class NativeBoardLane(
    val key: String,
    val title: String,
    val records: List<NativeRecord>,
    /** Stable parent/lane values retained for exact contract-backed card creation. */
    val contextValues: Map<String, String> = emptyMap(),
)

internal data class NativeChartPoint(
    val label: String,
    val value: Double,
)

internal data class NativeDatasetInsights(
    val measure: FieldSpec,
    val recordCount: Int,
    val total: Double,
    val average: Double,
    val dimension: FieldSpec?,
    val points: List<NativeChartPoint>,
)

internal data class NativeTableProjection(
    val resource: ResourceSpec,
    val records: List<NativeRecord>,
    val cellsByRecord: Map<String, Map<String, NativeProjectedCell>> = emptyMap(),
    val frozenFieldId: String? = null,
    val composite: Boolean = false,
    val projectedFieldIds: Set<String> = emptySet(),
)

internal data class NativeProjectedCell(
    val sourceFieldId: String,
    val cellKey: String,
    val value: String?,
    val contextValues: Map<String, String>,
    val valueShape: NativeCellValueShape,
    val declaredKind: FieldKind? = null,
)

internal enum class NativeCellValueShape {
    scalar,
    nullValue,
    complex,
}

internal data class NativeCellEditPlan(
    val action: ActionSpec,
    val field: FieldSpec,
    val recordId: String,
    val originalValue: String,
    val valueFieldName: String,
    val preservedValues: Map<String, String>,
    private val rowObjectEdit: NativeRowObjectEdit? = null,
) {
    fun request(newValue: String): NativeActionRequest.Submit {
        val submittedValue = rowObjectEdit?.reconstruct(newValue, field) ?: newValue
        return NativeActionRequest.Submit(
            action = action,
            values = preservedValues + (valueFieldName to submittedValue),
            confirmed = true,
        )
    }
}

internal data class NativeRowObjectEdit(
    val original: JsonObject,
    val targetKey: String,
) {
    fun reconstruct(newValue: String, field: FieldSpec): String {
        val replacement = newValue.toProjectedCellJson(field)
        val current = original[targetKey]
        val updatedCell = if (current is JsonObject && "value" in current) {
            JsonObject(current + ("value" to replacement))
        } else {
            replacement
        }
        return JsonObject(original + (targetKey to updatedCell)).toString()
    }
}

private data class NativeCellEditCandidate(
    val action: ActionSpec,
    val valueFieldName: String,
    val rowObjectEdit: NativeRowObjectEdit?,
    val rank: Int,
)

internal fun nativeCellEditPlan(
    schema: NativeAppSchema,
    resource: ResourceSpec,
    projection: NativeTableProjection,
    record: NativeRecord,
    field: FieldSpec,
): NativeCellEditPlan? {
    val cell = projection.cellsByRecord[record.id]?.get(field.id) ?: return null
    if (cell.valueShape == NativeCellValueShape.complex) return null
    if (cell.valueShape == NativeCellValueShape.nullValue && cell.declaredKind == null) return null
    val preserved = buildMap {
        record.values.forEach { (key, value) -> if (value != null) put(key, value) }
        if (record.canResolveUnsafeActionIdentity()) putIfAbsent("id", record.id)
        putAll(cell.contextValues)
    }
    val candidates = schema.actions.mapNotNull { action ->
        if (!action.resourceId.sameTabularResourceShape(resource.id) || action.intent != ActionIntent.update ||
            action.risk != ActionRisk.mutating || action.binding.method !in setOf(HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.POST)
        ) return@mapNotNull null
        if (action.binding.bodyContentType?.substringBefore(';')?.trim()?.lowercase() !in SUPPORTED_INLINE_BODY_TYPES) {
            return@mapNotNull null
        }
        val rowObjectCandidate = action.binding.bodyFieldNames.mapNotNull { bodyField ->
            if (!action.binding.bodyFieldExplicitlyAcceptsObject(bodyField)) return@mapNotNull null
            record.rowObjectEdit(bodyField, cell)?.let { edit -> bodyField to edit }
        }.singleOrNull()
        val valueField = when {
            cell.cellKey in action.binding.bodyFieldNames -> cell.cellKey
            field.label in action.binding.bodyFieldNames -> field.label
            "value" in action.binding.bodyFieldNames -> "value"
            rowObjectCandidate != null -> rowObjectCandidate.first
            action.binding.declaresCellIdentity() ->
                action.binding.bodyFieldNames.singleOrNull { it.semanticCellFieldId() in CELL_VALUE_BODY_FIELD_IDS }
            else -> return@mapNotNull null
        } ?: return@mapNotNull null
        val available = preserved.keys + valueField
        if (!action.binding.requiredPathParameterNames.all { it.canResolveFrom(available) } ||
            !action.binding.requiredQueryParameterNames.all { it.canResolveFrom(available) } ||
            !action.binding.requiredBodyFieldNames.all { it in available }
        ) return@mapNotNull null
        val rank = when {
            valueField == cell.cellKey -> 4
            valueField == field.label -> 3
            valueField == "value" -> 2
            rowObjectCandidate != null -> 2
            else -> 1
        }
        NativeCellEditCandidate(action, valueField, rowObjectCandidate?.second, rank)
    }
    val highestRank = candidates.maxOfOrNull(NativeCellEditCandidate::rank) ?: return null
    val preferred = candidates.filter { it.rank == highestRank }
    if (preferred.size != 1) return null
    val candidate = preferred.single()
    return NativeCellEditPlan(
        action = candidate.action,
        field = field,
        recordId = record.id,
        originalValue = cell.value.orEmpty(),
        valueFieldName = candidate.valueFieldName,
        preservedValues = preserved,
        rowObjectEdit = candidate.rowObjectEdit,
    )
}

internal fun validateNativeCellEdit(field: FieldSpec, value: String): String? {
    val trimmed = value.trim()
    if (field.required && trimmed.isBlank()) return "${field.label} is required."
    if (trimmed.isBlank()) return null
    return when (field.kind) {
        FieldKind.integer -> if (trimmed.toLongOrNull() == null) "Enter a whole number." else null
        FieldKind.decimal, FieldKind.currency ->
            if (trimmed.toDoubleOrNull()?.isFinite() != true) "Enter a valid number." else null
        FieldKind.boolean -> if (trimmed !in setOf("true", "false")) "Enter true or false." else null
        else -> null
    }
}

data class NativeDatasetContext(
    val parentResourceId: String? = null,
    val parentRecord: NativeRecord? = null,
    val relatedRecords: Map<String, List<NativeRecord>> = emptyMap(),
)

internal data class HydratedNativeDataset(
    val resource: ResourceSpec,
    val records: List<NativeRecord>,
    /** Present only when a nested lane response declares the full lane hierarchy. */
    val boardLanes: List<NativeBoardLane>? = null,
)

/**
 * Expands a lane collection containing nested cards/items/tasks into a flat board dataset.
 * The rule is shape- and key-driven, so Deck, project trackers, and workflow APIs can share it.
 */
internal fun expandNestedBoardDataset(
    resource: ResourceSpec,
    records: List<NativeRecord>,
): HydratedNativeDataset? = expandNestedBoardDataset(null, resource, records)

internal fun expandNestedBoardDataset(
    schema: NativeAppSchema?,
    resource: ResourceSpec,
    records: List<NativeRecord>,
): HydratedNativeDataset? {
    val nestedFieldId = records.asSequence()
        .flatMap { record -> record.structuredValues.keys.asSequence() }
        .distinct()
        .firstOrNull { fieldId ->
            fieldId.semanticFieldId() in NESTED_BOARD_ITEM_FIELD_NAMES && records.any { record ->
                record.structuredValues[fieldId] is NativeStructuredValue.ListValue
            }
        } ?: return null
    val childObjects = records.flatMap { laneRecord ->
        val laneTitle = laneRecord.presentationValue("title")
            ?: laneRecord.presentationValue("name")
            ?: laneRecord.id
        val items = (laneRecord.structuredValues[nestedFieldId] as? NativeStructuredValue.ListValue)
            ?.items.orEmpty()
        items.mapIndexedNotNull { index, item ->
            val child = item as? NativeStructuredValue.ObjectValue ?: return@mapIndexedNotNull null
            NestedBoardChild(laneRecord, laneTitle, index, child)
        }
    }
    val declaredChildResource = schema?.resources
        ?.filter { candidate ->
            candidate.id.sameBoardResourceShape(nestedFieldId) ||
                candidate.name.sameBoardResourceShape(nestedFieldId)
        }
        ?.singleOrNull()
    val childResourceId = declaredChildResource?.id ?: nestedFieldId
    val childActions = schema?.actions.orEmpty().filter { action ->
        action.resourceId.sameBoardResourceShape(childResourceId)
    }
    val declaredWritableIds = childActions.asSequence()
        .filter { action ->
            action.intent == ActionIntent.update &&
                action.risk == ActionRisk.mutating &&
                action.binding.method in setOf(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH)
        }
        .flatMap { action -> action.binding.bodyFieldNames.asSequence() }
        .mapTo(mutableSetOf(), String::semanticFieldId)

    val observedFields = linkedMapOf<String, FieldSpec>()
    childObjects.forEach { child ->
        child.value.entries.forEach { entry ->
            observedFields.putIfAbsent(entry.key, entry.toNestedBoardField(declaredWritableIds))
        }
    }
    if (observedFields.keys.none { it.semanticFieldId() in BOARD_LANE_ID_NAMES }) {
        observedFields["laneId"] = FieldSpec("laneId", "Lane", FieldKind.string, false, true)
    }
    val declaredIdentityIds: Set<String> = declaredChildResource?.fields.orEmpty()
        .filter { it.id.semanticFieldId() in NESTED_BOARD_IDENTITY_NAMES }
        .mapTo(mutableSetOf<String>()) { it.id.semanticFieldId() }
        .apply {
            if (childActions.any { action ->
                    action.binding.requiredPathParameterNames.any { parameter ->
                        parameter.isResourceIdentityFor(childResourceId)
                    }
                }
            ) {
                add("id")
            }
        }
    val childRecords = childObjects.map { child ->
        child to child.toNativeBoardRecord(childResourceId, declaredIdentityIds, declaredWritableIds)
    }
    val lanes = records.map { laneRecord ->
        val laneTitle = laneRecord.presentationValue("title")
            ?: laneRecord.presentationValue("name")
            ?: laneRecord.id
        NativeBoardLane(
            key = laneRecord.id,
            title = laneTitle,
            records = childRecords.filter { (child, _) -> child.laneRecord.id == laneRecord.id }
                .map { (_, record) -> record },
            contextValues = laneRecord.actionBindingValues(allowUnsafeIdentity = true) + mapOf(
                "laneId" to laneRecord.id,
                "stackId" to laneRecord.id,
            ),
        )
    }
    return HydratedNativeDataset(
        resource = declaredChildResource?.copy(
            fields = declaredChildResource.fields + observedFields.values.filter { observed ->
                declaredChildResource.fields.none { it.id.equals(observed.id, ignoreCase = true) }
            },
        ) ?: ResourceSpec(
            id = nestedFieldId,
            name = nestedFieldId.humanizeSemanticValue(),
            confidence = resource.confidence,
            fields = observedFields.values.toList(),
            evidence = resource.evidence,
        ),
        records = childRecords.map { (_, record) -> record },
        boardLanes = lanes,
    )
}

private data class NestedBoardChild(
    val laneRecord: NativeRecord,
    val laneTitle: String,
    val index: Int,
    val value: NativeStructuredValue.ObjectValue,
)

private fun NestedBoardChild.toNativeBoardRecord(
    resourceId: String,
    declaredIdentityIds: Set<String>,
    declaredWritableIds: Set<String>,
): NativeRecord {
    val scalarValues = value.entries.mapNotNull { entry ->
        (entry.value as? NativeStructuredValue.Scalar)?.value?.let { scalar -> entry.key to scalar }
    }.toMap()
    val identityEntry = NESTED_BOARD_IDENTITY_NAMES.firstNotNullOfOrNull { identityName ->
        scalarValues.entries.firstOrNull { (key, _) -> key.semanticFieldId() == identityName }
    }
    val identity = identityEntry?.value ?: "${laneRecord.id}:$index"
    val laneField = scalarValues.keys.firstOrNull { key -> key.semanticFieldId() in BOARD_LANE_ID_NAMES }
        ?: "laneId"
    val inheritedContext = laneRecord.values.filterKeys { key ->
        key.semanticFieldId().endsWith("id") && key.semanticFieldId() != "id"
    }
    val nestedStructures = value.entries.mapNotNull { entry ->
        entry.value.takeIf { nested -> nested !is NativeStructuredValue.Scalar }?.let { nested -> entry.key to nested }
    }.toMap()
    return NativeRecord(
        id = identity,
        values = inheritedContext + scalarValues + mapOf(
            laneField to (scalarValues[laneField] ?: laneRecord.id),
            NATIVE_SYNTHETIC_RESOURCE_FIELD to resourceId,
        ),
        displayValues = mapOf(laneField to laneTitle),
        ephemeralFields = value.entries.map { entry -> entry.toNestedBoardField(declaredWritableIds) },
        actionSafeIdentity = identityEntry?.key?.semanticFieldId() in declaredIdentityIds,
        structuredValues = nestedStructures,
    )
}

private fun NativeStructuredEntry.toNestedBoardField(declaredWritableIds: Set<String> = emptySet()): FieldSpec = FieldSpec(
    id = key,
    label = label,
    kind = when (val nested = value) {
        is NativeStructuredValue.ListValue, is NativeStructuredValue.ObjectValue -> FieldKind.objectValue
        is NativeStructuredValue.Scalar -> when (nested.kind) {
            NativeStructuredScalarKind.boolean -> FieldKind.boolean
            NativeStructuredScalarKind.number -> FieldKind.decimal
            NativeStructuredScalarKind.nullValue, NativeStructuredScalarKind.string ->
                if (nested.value.orEmpty().length > 160) FieldKind.longText else FieldKind.string
        }
    },
    required = false,
    readOnly = key.semanticFieldId() !in declaredWritableIds,
)

private val NESTED_BOARD_ITEM_FIELD_NAMES = setOf("cards", "items", "tasks")
private val NESTED_BOARD_IDENTITY_NAMES = listOf("databaseid", "id", "uuid")
private val BOARD_LANE_ID_NAMES = setOf("stackid", "laneid", "columnid", "listid", "stageid")
private fun String.semanticFieldId(): String = lowercase().filter(Char::isLetterOrDigit)
private fun String.sameBoardResourceShape(other: String): Boolean {
    fun String.singular(): String = semanticFieldId().let {
        when {
            it.endsWith("ies") && it.length > 3 -> it.dropLast(3) + "y"
            it.endsWith("s") && !it.endsWith("ss") && it.length > 1 -> it.dropLast(1)
            else -> it
        }
    }
    return singular() == other.singular()
}

private fun String.isResourceIdentityFor(resourceId: String): Boolean {
    val normalized = semanticFieldId()
    if (normalized == "id") return true
    if (!normalized.endsWith("id") || normalized.length <= 2) return false
    return normalized.dropLast(2).sameBoardResourceShape(resourceId)
}

/** Resolves presentation labels while retaining every original value for requests and actions. */
internal fun hydrateNativeDataset(
    schema: NativeAppSchema,
    resource: ResourceSpec,
    records: List<NativeRecord>,
    context: NativeDatasetContext,
): HydratedNativeDataset {
    val currency = context.parentRecord?.semanticCurrency()
    val contextualResource = if (currency == null) {
        resource
    } else {
        resource.copy(fields = resource.fields.map { field ->
            if (field.isCurrencyMeasure()) field.copy(kind = FieldKind.currency, format = currency) else field
        })
    }
    val displayRecords = records.map { record ->
        val resolved = contextualResource.fields.mapNotNull { field ->
            val raw = record.values[field.id]?.trim()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val label = resolveForeignLabel(schema, contextualResource, field, raw, context) ?: return@mapNotNull null
            field.id to label
        }.toMap()
        if (resolved.isEmpty()) record else record.copy(displayValues = record.displayValues + resolved)
    }
    return HydratedNativeDataset(contextualResource, displayRecords)
}

/**
 * Promotes schema-declared maps of logical cells into columns. A common API shape is
 * `alias -> { value: ... }`; rendering the whole object as one JSON cell would technically be
 * generic but unusable. This projection is shape-driven and therefore also works for survey,
 * inventory, CRM, and other table-like APIs.
 */
internal fun nativeTableProjection(
    resource: ResourceSpec,
    records: List<NativeRecord>,
    columnResource: ResourceSpec? = null,
    columnRecords: List<NativeRecord> = emptyList(),
    composite: CompositeDataGridSpec? = null,
): NativeTableProjection {
    if (records.isEmpty() && composite == null) return NativeTableProjection(resource, records)
    val sourceField = composite?.rowCellMapFieldId?.let { id -> resource.fields.firstOrNull { it.id == id } }
        ?: resource.fields
            .asSequence()
            .filter { it.kind == FieldKind.objectValue }
            .mapNotNull { field ->
                val maps = records.mapNotNull { record -> record.values[field.id].cellMapOrNull(field) }
                val keys = maps.flatMap { it.keys }.distinct()
                if (maps.isEmpty() || keys.isEmpty()) null else field to (maps.size * keys.size)
            }
            .maxByOrNull(Pair<FieldSpec, Int>::second)
            ?.first
        ?: return NativeTableProjection(resource, records)
    val cellsByRecord = records.associate { record -> record.id to record.values[sourceField.id].cellMapOrNull(sourceField).orEmpty() }
    val observedKeys = cellsByRecord.values.flatMap { it.keys }.distinct()
    val definitions = if (composite != null && columnResource != null) {
        columnRecords.mapIndexedNotNull { index, record ->
            val identity = record.values[composite.columnIdentityFieldId]?.trim()?.takeIf(String::isNotBlank)
                ?: return@mapIndexedNotNull null
            val alias = composite.columnAliasFieldId?.let { record.values[it]?.trim()?.takeIf(String::isNotBlank) }
            val title = record.values[composite.columnTitleFieldId]?.trim()?.takeIf(String::isNotBlank)
                ?: alias
                ?: identity
            val type = composite.columnTypeFieldId?.let { record.values[it]?.trim() }
            val order = composite.columnOrderFieldId?.let { record.values[it].nativeNumberOrNull() }
            NativeColumnDefinition(identity, alias, title, type, order, index)
        }.sortedWith(compareBy<NativeColumnDefinition> { it.order ?: Double.MAX_VALUE }.thenBy(NativeColumnDefinition::index))
    } else {
        emptyList()
    }
    val definitionByKey = linkedMapOf<String, NativeColumnDefinition>()
    definitions.forEach { definition ->
        val key = definition.alias?.takeIf { it in observedKeys }
            ?: observedKeys.firstOrNull { observedKey ->
                cellsByRecord.values.any { cells -> cells[observedKey]?.contextValues?.get("columnId") == definition.identity }
            }
            ?: definition.alias
            ?: definition.identity
        definitionByKey.putIfAbsent(key, definition)
    }
    val keys = (definitionByKey.keys + observedKeys).distinct()
    val projectedFields = keys.map { key ->
        val cells = cellsByRecord.values.mapNotNull { it[key] }
        val definition = definitionByKey[key]
        FieldSpec(
            id = projectedCellId(sourceField.id, key),
            label = definition?.title ?: cells.firstNotNullOfOrNull(NativeCell::label) ?: key.humanizeSemanticValue(),
            kind = definition?.type.nativeColumnKind() ?: cells.map(NativeCell::kind).mostSpecificCellKind(),
            required = false,
            readOnly = true,
        )
    }
    val projectedRecords = records.map { record ->
        val cells = cellsByRecord[record.id].orEmpty()
        record.copy(
            values = record.values + keys.associate { key ->
                projectedCellId(sourceField.id, key) to cells[key]?.value
            },
        )
    }
    val projectedCellsByRecord = records.associate { record ->
        record.id to keys.mapNotNull { key ->
            val cell = cellsByRecord[record.id]?.get(key) ?: return@mapNotNull null
            projectedCellId(sourceField.id, key) to NativeProjectedCell(
                sourceFieldId = sourceField.id,
                cellKey = key,
                value = cell.value,
                contextValues = cell.contextValues,
                valueShape = cell.valueShape,
                declaredKind = definitionByKey[key]?.type.nativeColumnKind(),
            )
        }.toMap()
    }
    return NativeTableProjection(
        resource = resource.copy(fields = projectedFields + resource.fields.filterNot { it.id == sourceField.id }),
        records = projectedRecords,
        cellsByRecord = projectedCellsByRecord,
        frozenFieldId = resource.fields.firstOrNull { it.semanticId() in FROZEN_ROW_TITLE_IDS }?.id
            ?: resource.fields.firstOrNull { it.semanticId() in IDENTITY_FIELD_IDS }
                ?.takeUnless { composite != null }
                ?.id,
        composite = composite != null,
        projectedFieldIds = projectedFields.mapTo(linkedSetOf(), FieldSpec::id),
    )
}

private data class NativeColumnDefinition(
    val identity: String,
    val alias: String?,
    val title: String,
    val type: String?,
    val order: Double?,
    val index: Int,
)

private fun String?.nativeColumnKind(): FieldKind? = when (this?.lowercase()?.filter(Char::isLetterOrDigit)) {
    null, "" -> null
    "number", "decimal", "float", "double" -> FieldKind.decimal
    "integer", "int" -> FieldKind.integer
    "boolean", "bool", "checkbox" -> FieldKind.boolean
    "date" -> FieldKind.date
    "datetime", "timestamp" -> FieldKind.dateTime
    "selection", "select", "enum" -> FieldKind.enumeration
    "user", "users", "usergroup" -> FieldKind.userReference
    "longtext", "textarea", "richtext" -> FieldKind.longText
    else -> FieldKind.string
}

/**
 * Builds lanes from field meaning and returned values rather than from an app identifier.
 * This lets Deck-style cards, generic workflow items, CRM opportunities, and similar resources
 * share one renderer.
 */
internal fun nativeBoardLanes(
    resource: ResourceSpec,
    records: List<NativeRecord>,
): List<NativeBoardLane> {
    if (records.isEmpty()) return emptyList()
    val laneField = resource.fields
        .withIndex()
        .maxWithOrNull(
            compareBy<IndexedValue<FieldSpec>> { it.value.boardLanePriority() }
                .thenByDescending(IndexedValue<FieldSpec>::index),
        )
        ?.value
        ?.takeIf { it.boardLanePriority() > 0 && records.any { record -> !record.values[it.id].isNullOrBlank() } }
        ?: return listOf(
            NativeBoardLane(
                "all",
                resource.name,
                sortBoardRecords(resource, records),
                records.firstOrNull()?.actionBindingValues(allowUnsafeIdentity = true).orEmpty(),
            ),
        )

    val orderedKeys = records.map { it.values[laneField.id].orEmpty().trim().ifBlank { UNASSIGNED_LANE } }.distinct()
    return orderedKeys.map { key ->
        val laneRecords = records.filter {
            it.values[laneField.id].orEmpty().trim().ifBlank { UNASSIGNED_LANE } == key
        }
        NativeBoardLane(
            key = key,
            title = laneRecords.firstNotNullOfOrNull { it.displayValues[laneField.id] }
                ?: boardLaneTitle(laneField, key),
            records = sortBoardRecords(
                resource,
                laneRecords,
            ),
            contextValues = laneRecords.firstOrNull()?.actionBindingValues(allowUnsafeIdentity = true).orEmpty(),
        )
    }
}

/**
 * Finds a useful aggregate and optional categorical breakdown for arbitrary record collections.
 * Only declared numeric fields with successfully parsed values participate, so identifiers and
 * opaque payloads can never silently become a chart.
 */
internal fun nativeDatasetInsights(
    resource: ResourceSpec,
    records: List<NativeRecord>,
): NativeDatasetInsights? {
    if (records.isEmpty()) return null
    val measure = resource.fields
        .mapNotNull { field ->
            val values = records.mapNotNull { record -> record.presentationValue(field.id).nativeNumberOrNull() }
            if (values.isEmpty() || field.measurePriority() <= 0) null else Triple(field, values, field.measurePriority())
        }
        .maxWithOrNull(compareBy<Triple<FieldSpec, List<Double>, Int>> { it.third })
        ?: return null
    val (measureField, values) = measure
    val dimension = resource.fields
        .filter { it.id != measureField.id }
        .mapNotNull { field ->
            val populated = records.mapNotNull { it.presentationValue(field.id)?.trim()?.takeIf(String::isNotBlank) }
            val distinct = populated.distinct().size
            val priority = field.dimensionPriority(records.any { field.id in it.displayValues })
            if (priority <= 0 || distinct !in 2..12) null else field to priority
        }
        .maxByOrNull { it.second }
        ?.first

    val points = dimension?.let { field ->
        records.mapNotNull { record ->
            val numeric = record.presentationValue(measureField.id).nativeNumberOrNull() ?: return@mapNotNull null
            val rawLabel = record.presentationValue(field.id)?.trim()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            chartLabel(field, rawLabel) to numeric
        }
            .groupBy(Pair<String, Double>::first, Pair<String, Double>::second)
            .map { (label, groupedValues) -> NativeChartPoint(label, groupedValues.sum()) }
            .sortedByDescending { abs(it.value) }
            .take(MAX_CHART_POINTS)
    }.orEmpty()

    return NativeDatasetInsights(
        measure = measureField,
        recordCount = records.size,
        total = values.sum(),
        average = values.average(),
        dimension = dimension,
        points = points,
    )
}

internal fun formatNativeMetric(field: FieldSpec, value: Double): String {
    val rounded = if (abs(value) < Double.MAX_VALUE / 100.0) round(value * 100.0) / 100.0 else value
    val normalized = when {
        rounded == rounded.toLong().toDouble() -> rounded.toLong().toString()
        else -> rounded.toString().trimEnd('0').trimEnd('.')
    }
    return if (field.kind == FieldKind.currency && !field.format.isNullOrBlank()) {
        "${field.format} $normalized"
    } else {
        normalized
    }
}

private fun sortBoardRecords(resource: ResourceSpec, records: List<NativeRecord>): List<NativeRecord> {
    val orderField = resource.fields.maxByOrNull(FieldSpec::boardOrderPriority)
        ?.takeIf { it.boardOrderPriority() > 0 }
        ?: return records
    return records.withIndex()
        .sortedWith(
            compareBy<IndexedValue<NativeRecord>> {
                it.value.values[orderField.id].nativeNumberOrNull() ?: Double.MAX_VALUE
            }.thenBy(IndexedValue<NativeRecord>::index),
        )
        .map(IndexedValue<NativeRecord>::value)
}

private fun FieldSpec.boardLanePriority(): Int = when (semanticId()) {
    "stackid" -> 1_000
    "stack" -> 980
    "columnid" -> 960
    "column" -> 940
    "laneid" -> 920
    "lane" -> 900
    "listid" -> 880
    "list" -> 860
    "stageid" -> 840
    "stage" -> 820
    "status" -> 800
    "state" -> 780
    "group" -> 760
    else -> 0
}

private fun FieldSpec.boardOrderPriority(): Int = when (semanticId()) {
    "order" -> 500
    "position" -> 480
    "sortorder" -> 460
    "sort" -> 440
    "index" -> 420
    else -> 0
}

private fun FieldSpec.measurePriority(): Int {
    if (kind !in setOf(FieldKind.integer, FieldKind.decimal, FieldKind.currency)) return 0
    val id = semanticId()
    if (id == "id" || id.endsWith("id") || id in NON_MEASURE_FIELDS) return 0
    val semantic = when (id) {
        "amount" -> 1_000
        "total", "totalspent", "totalexpense", "totalexpenses", "spending", "spent",
        "thismonthexpenses", "thismonthincome" -> 960
        "balance", "currentbalance", "availablebalance", "closingbalance", "cost", "price", "value" -> 920
        "budget", "budgetamount", "budgeted", "openingbalance",
        "expense", "expenses", "income", "revenue" -> 880
        "accruedinterest", "average", "avgtransaction", "creditlimit", "minimum",
        "minimumpayment", "overdraftlimit" -> 760
        "quantity", "count" -> 500
        else -> 0
    }
    return semantic + when (kind) {
        FieldKind.currency -> 300
        FieldKind.decimal -> 200
        FieldKind.integer -> 100
        else -> 0
    }
}

private fun FieldSpec.dimensionPriority(hasResolvedForeignLabels: Boolean = false): Int {
    if (hasResolvedForeignLabels) {
        return when (foreignKeyBase()) {
            "category" -> 1_000
            "paymentmode", "paymentmethod" -> 960
            "status", "state", "stage" -> 920
            "account", "group", "project" -> 880
            "member", "payer", "owner", "assignee", "user" -> 840
            else -> 620
        }
    }
    if (kind !in setOf(FieldKind.string, FieldKind.enumeration, FieldKind.userReference, FieldKind.date, FieldKind.dateTime)) {
        return 0
    }
    val id = semanticId()
    if (id == "id" || id.endsWith("id") || id in TECHNICAL_DIMENSIONS) return 0
    return when (id) {
        "category", "categoryname" -> 1_000
        "paymentmode", "paymentmethod" -> 960
        "status", "state", "stage", "type" -> 920
        "account", "group", "groupname", "project", "projectname" -> 880
        "payer", "payername", "owner", "assignee" -> 840
        "tag", "label" -> 800
        "budgetperiod", "frequency", "period" -> 760
        "date", "created", "updated", "modified" -> 700
        else -> if (kind == FieldKind.enumeration) 620 else 0
    }
}

private fun boardLaneTitle(field: FieldSpec, key: String): String {
    if (key == UNASSIGNED_LANE) return "Unassigned"
    val humanValue = key.humanizeSemanticValue()
    return if (field.semanticId().endsWith("id") && key.all(Char::isDigit)) {
        "${field.label.removeSuffix(" ID").removeSuffix(" id")} $humanValue"
    } else {
        humanValue
    }
}

private fun chartLabel(field: FieldSpec, value: String): String = when (field.kind) {
    FieldKind.date, FieldKind.dateTime -> value.take(7)
    else -> value.humanizeSemanticValue()
}

private fun FieldSpec.semanticId(): String = id.lowercase().filter(Char::isLetterOrDigit)

private fun String?.nativeNumberOrNull(): Double? {
    val parsed = this?.trim()?.toDoubleOrNull() ?: return null
    return parsed.takeIf(Double::isFinite)
}

private data class NativeCell(
    val value: String?,
    val label: String?,
    val kind: FieldKind,
    val contextValues: Map<String, String>,
    val valueShape: NativeCellValueShape,
)

private fun String?.cellMapOrNull(field: FieldSpec): Map<String, NativeCell>? {
    val root = runCatching { Json.parseToJsonElement(this ?: return null) }.getOrNull() ?: return null
    val semanticallyCellular = field.id.lowercase().filter(Char::isLetterOrDigit) in CELL_MAP_FIELD_IDS
    return when (root) {
        is JsonObject -> {
            if (root.isEmpty()) return null
            val nestedCells = root.values.all { value -> value is JsonObject && "value" in value }
            val primitiveMap = root.values.all { it is JsonPrimitive || it is JsonNull }
            if (!nestedCells && !(primitiveMap && semanticallyCellular)) return null
            root.mapValues { (_, element) -> element.toNativeCell() }
        }
        is JsonArray -> {
            if (!semanticallyCellular || root.isEmpty()) return null
            root.mapNotNull { element ->
                val wrapper = element as? JsonObject ?: return@mapNotNull null
                if ("value" !in wrapper) return@mapNotNull null
                val key = CELL_ARRAY_KEY_IDS.firstNotNullOfOrNull { candidate ->
                    (wrapper[candidate] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
                } ?: return@mapNotNull null
                key to wrapper.toNativeCell()
            }.toMap().takeIf(Map<String, NativeCell>::isNotEmpty)
        }
        else -> null
    }
}

private fun JsonElement.toNativeCell(): NativeCell {
    val wrapper = this as? JsonObject
    val element = wrapper?.get("value") ?: this
    val primitive = element as? JsonPrimitive
    val value = when (element) {
        JsonNull -> null
        is JsonPrimitive -> element.contentOrNull
        else -> element.toString()
    }
    val label = listOf("label", "name", "title", "displayName")
        .firstNotNullOfOrNull { key -> (wrapper?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
    val kind = when {
        primitive == null || primitive.isString -> FieldKind.string
        primitive.contentOrNull in setOf("true", "false") -> FieldKind.boolean
        primitive.contentOrNull?.toLongOrNull() != null -> FieldKind.integer
        primitive.contentOrNull?.toDoubleOrNull() != null -> FieldKind.decimal
        else -> FieldKind.string
    }
    val contextValues = wrapper.orEmpty().mapNotNull { (key, metadataValue) ->
        if (key in CELL_PRESENTATION_KEYS) return@mapNotNull null
        (metadataValue as? JsonPrimitive)?.contentOrNull?.let { key to it }
    }.toMap()
    val valueShape = when (element) {
        JsonNull -> NativeCellValueShape.nullValue
        is JsonPrimitive -> NativeCellValueShape.scalar
        else -> NativeCellValueShape.complex
    }
    return NativeCell(value, label, kind, contextValues, valueShape)
}

private fun List<FieldKind>.mostSpecificCellKind(): FieldKind = when {
    isEmpty() -> FieldKind.string
    all { it == FieldKind.boolean } -> FieldKind.boolean
    all { it == FieldKind.integer } -> FieldKind.integer
    all { it == FieldKind.integer || it == FieldKind.decimal } -> FieldKind.decimal
    else -> FieldKind.string
}

private fun projectedCellId(sourceFieldId: String, key: String): String = "$sourceFieldId.$key"

private fun String.canResolveFrom(available: Set<String>): Boolean =
    this in available || length > 2 && endsWith("Id", ignoreCase = true) && "id" in available

/**
 * A projected cell can use a generic payload name such as `data` only when the route is explicitly
 * scoped to one column/property/field. This covers table-style `row/{id}/column/{columnId}` writes
 * without mistaking whole-row `row/{id}` data replacement for an inline scalar edit.
 */
private fun dev.obiente.nextcloudnative.nativeui.model.ApiBinding.declaresCellIdentity(): Boolean =
    (pathParameterNames + queryParameterNames + bodyFieldNames)
        .any { it.semanticCellFieldId() in CELL_IDENTITY_FIELD_IDS }

private fun dev.obiente.nextcloudnative.nativeui.model.ApiBinding.bodyFieldExplicitlyAcceptsObject(
    fieldName: String,
): Boolean {
    val root = bodySchema as? JsonObject ?: return false
    val property = (root["properties"] as? JsonObject)?.get(fieldName) as? JsonObject ?: return false
    if ((property["type"] as? JsonPrimitive)?.contentOrNull == "object") return true
    return (property["oneOf"] as? JsonArray).orEmpty().any { option ->
        ((option as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull == "object"
    }
}

private fun NativeRecord.rowObjectEdit(
    bodyFieldName: String,
    cell: NativeProjectedCell,
): NativeRowObjectEdit? {
    val declaredBodyValue = values[bodyFieldName]
    val candidates = listOfNotNull(declaredBodyValue, values[cell.sourceFieldId]).distinct()
    return candidates.firstNotNullOfOrNull { encoded ->
        val root = runCatching { Json.parseToJsonElement(encoded) }.getOrNull()
            ?.toLosslessCellObject()
            ?: return@firstNotNullOfOrNull null
        val columnIdentity = cell.contextValues.entries.firstOrNull { (key, value) ->
            key.semanticCellFieldId() in CELL_IDENTITY_FIELD_IDS && value.isNotBlank()
        }?.value
        val targetKeys = root.entries.mapNotNull { (key, value) ->
            val wrapperIdentity = (value as? JsonObject)?.entries?.firstOrNull { (metadataKey, metadataValue) ->
                columnIdentity != null &&
                    metadataKey.semanticCellFieldId() in CELL_IDENTITY_FIELD_IDS &&
                    (metadataValue as? JsonPrimitive)?.contentOrNull == columnIdentity
            }
            key.takeIf {
                key == cell.cellKey ||
                    columnIdentity != null && key == columnIdentity ||
                    wrapperIdentity != null
            }
        }.distinct()
        targetKeys.singleOrNull()?.let { targetKey -> NativeRowObjectEdit(root, targetKey) }
    }
}

private fun JsonElement.toLosslessCellObject(): JsonObject? = when (this) {
    is JsonObject -> this
    is JsonArray -> {
        val entries = map { element ->
            val wrapper = element as? JsonObject ?: return null
            val identity = wrapper.entries.firstNotNullOfOrNull { (key, value) ->
                key.semanticCellFieldId().takeIf { it in CELL_IDENTITY_FIELD_IDS }
                    ?.let { (value as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
            } ?: return null
            identity to wrapper
        }
        if (entries.map { it.first }.distinct().size != entries.size) return null
        JsonObject(entries.toMap())
    }
    else -> null
}

private fun String.toProjectedCellJson(field: FieldSpec): JsonElement = when (field.kind) {
    FieldKind.boolean -> JsonPrimitive(toBooleanStrictOrNull() ?: error("Enter true or false."))
    FieldKind.integer -> JsonPrimitive(toLongOrNull() ?: error("Enter a whole number."))
    FieldKind.decimal, FieldKind.currency -> JsonPrimitive(
        toDoubleOrNull()?.takeIf(Double::isFinite) ?: error("Enter a valid number."),
    )
    else -> JsonPrimitive(this)
}

private fun String.semanticCellFieldId(): String = lowercase().filter(Char::isLetterOrDigit)

private fun String.sameTabularResourceShape(other: String): Boolean {
    fun String.singular(): String = semanticCellFieldId().let { value ->
        when {
            value.endsWith("ies") && value.length > 3 -> value.dropLast(3) + "y"
            value.endsWith("s") && !value.endsWith("ss") && value.length > 1 -> value.dropLast(1)
            else -> value
        }
    }
    return singular() == other.singular()
}

private fun resolveForeignLabel(
    schema: NativeAppSchema,
    currentResource: ResourceSpec,
    field: FieldSpec,
    raw: String,
    context: NativeDatasetContext,
): String? {
    val foreignBase = field.foreignKeyBase() ?: return null
    val relationshipResources = schema.relationships.filter { relationship ->
        relationship.childResourceId == currentResource.id && relationship.childFieldId == field.id
    }.map { it.parentResourceId }
    val semanticResources = schema.resources.filter { candidate ->
        candidate.id.relationBase() == foreignBase || candidate.name.relationBase() == foreignBase
    }.map(ResourceSpec::id)
    val candidateIds = (relationshipResources + semanticResources).distinct()
    candidateIds.forEach { resourceId ->
        val relatedResource = schema.resource(resourceId) ?: return@forEach
        val parentMatch = context.parentRecord?.takeIf {
            context.parentResourceId == resourceId && it.matchesForeignIdentity(relatedResource, raw)
        }
        val relatedMatch = context.relatedRecords[resourceId]
            .orEmpty()
            .firstOrNull { it.matchesForeignIdentity(relatedResource, raw) }
        (parentMatch ?: relatedMatch)?.semanticTitle(relatedResource)?.let { return it }
    }
    return null
}

private fun NativeRecord.matchesForeignIdentity(resource: ResourceSpec, raw: String): Boolean {
    if (id == raw) return true
    return resource.fields.any { field ->
        field.id.lowercase().filter(Char::isLetterOrDigit) in IDENTITY_FIELD_IDS && values[field.id] == raw
    }
}

private fun NativeRecord.semanticTitle(resource: ResourceSpec): String? {
    val preferred = listOf("displayname", "name", "title", "subject", "summary", "label", "description")
    preferred.forEach { semanticId ->
        resource.fields.firstOrNull { it.id.lowercase().filter(Char::isLetterOrDigit) == semanticId }
            ?.let { field -> presentationValue(field.id)?.trim()?.takeIf(String::isNotBlank)?.let { return it } }
    }
    return null
}

private fun NativeRecord.semanticCurrency(): String? {
    val entry = values.entries.firstOrNull { (fieldId, value) ->
        fieldId.lowercase().filter(Char::isLetterOrDigit) in CURRENCY_FIELD_IDS && !value.isNullOrBlank()
    } ?: return null
    return entry.value?.trim()?.takeIf { value ->
        value.length in 1..12 && value.none(Char::isISOControl)
    }
}

private fun FieldSpec.isCurrencyMeasure(): Boolean {
    if (kind !in setOf(FieldKind.integer, FieldKind.decimal, FieldKind.currency)) return false
    return id.lowercase().filter(Char::isLetterOrDigit) in CURRENCY_MEASURE_IDS
}

private fun FieldSpec.foreignKeyBase(): String? {
    val normalized = id.lowercase().filter(Char::isLetterOrDigit)
    return normalized.takeIf { it.length > 2 && it.endsWith("id") }?.dropLast(2)?.relationBase()
}

private fun String.relationBase(): String {
    val normalized = lowercase().filter(Char::isLetterOrDigit)
    return when {
        normalized.endsWith("ies") && normalized.length > 3 -> normalized.dropLast(3) + "y"
        normalized.endsWith("ses") && normalized.length > 3 -> normalized.dropLast(2)
        normalized.endsWith('s') && normalized.length > 1 -> normalized.dropLast(1)
        else -> normalized
    }
}

private fun String.humanizeSemanticValue(): String = buildString(length) {
    var previousWasLowercase = false
    this@humanizeSemanticValue.forEach { character ->
        when {
            character == '_' || character == '-' -> {
                if (isNotEmpty() && last() != ' ') append(' ')
                previousWasLowercase = false
            }
            character.isUpperCase() && previousWasLowercase -> {
                append(' ')
                append(character)
                previousWasLowercase = false
            }
            else -> {
                append(character)
                previousWasLowercase = character.isLowerCase()
            }
        }
    }
}.trim().replaceFirstChar { it.uppercase() }

private const val UNASSIGNED_LANE = "__unassigned__"
private const val MAX_CHART_POINTS = 6

private val CELL_MAP_FIELD_IDS = setOf("data", "databyalias", "values", "cells", "fields", "attributes")
private val CELL_ARRAY_KEY_IDS = listOf("alias", "technicalName", "columnId", "column_id", "key", "id", "name")
private val CELL_PRESENTATION_KEYS = setOf("value", "label", "name", "title", "displayName")
private val CELL_IDENTITY_FIELD_IDS = setOf(
    "columnid",
    "fieldid",
    "propertyid",
    "attributeid",
    "cellid",
)
private val CELL_VALUE_BODY_FIELD_IDS = setOf("data", "content", "payload", "cellvalue")
private val SUPPORTED_INLINE_BODY_TYPES = setOf("application/json", "application/x-www-form-urlencoded")

private val IDENTITY_FIELD_IDS = setOf("id", "uuid", "token")
private val FROZEN_ROW_TITLE_IDS = setOf("title", "name", "label", "subject", "summary")
private val CURRENCY_FIELD_IDS = setOf("currency", "currencyname", "currencycode", "unit", "unitname")
private val CURRENCY_MEASURE_IDS = setOf(
    "amount", "total", "totalspent", "totalexpense", "totalexpenses", "spending", "spent",
    "thismonthexpenses", "thismonthincome",
    "balance", "currentbalance", "availablebalance", "closingbalance", "openingbalance",
    "cost", "price", "value", "expense", "expenses", "income", "revenue",
    "budget", "budgetamount", "budgeted", "accruedinterest", "average", "avgtransaction",
    "creditlimit", "minimum", "minimumpayment", "overdraftlimit",
)

private val NON_MEASURE_FIELDS = setOf(
    "order", "position", "sort", "sortorder", "index", "timestamp", "lastchanged", "accesslevel",
)

private val TECHNICAL_DIMENSIONS = setOf(
    "etag", "href", "token", "permissions", "permission", "url", "uri",
)
