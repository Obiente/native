package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import dev.obiente.nextcloudnative.nativeui.model.ViewSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

/*
 * Row writes for column-defined grids.
 *
 * A grid is a parent record (a table, a sheet, a view) whose columns are records of their own and
 * whose rows store one cell per column. Nothing here names an app or a version:
 * - the operations are the create, update, and delete actions the contract declares at the route
 *   positions of the row list read (see NativeContractActions);
 * - the cell payload is the one body property the contract declares as a key-value map; its keys
 *   are column identities and its values are cell values, mirroring the stored cell list
 *   `[{columnId, value}]` of each row;
 * - each column's editor comes from the wording of its type (`text`, `number`, `selection`,
 *   `datetime` and their variants) and the options, limits, and mandatory flag the column states;
 * - representations the contract and the data do not determine (links, rich text, people, and a
 *   boolean or date-time column without stored examples) are never encoded. Such cells are kept
 *   unchanged, and a row that holds one cannot be replaced as a whole.
 */

internal enum class NativeGridColumnKind(val editable: Boolean) {
    Text(true),
    LongText(true),
    Number(true),
    Rating(true),
    Percent(true),
    Choice(true),
    MultiChoice(true),
    Toggle(true),
    Date(true),
    Time(true),
    DateTime(true),
    Unsupported(false),
}

/** How a boolean or date-time column stores values, learned from cells the server returned. */
internal enum class NativeGridCellForm { StringBoolean, JsonBoolean, SpaceDateTime, IsoDateTime }

internal data class NativeGridColumnOption(val id: JsonPrimitive, val label: String) {
    val key: String get() = id.content
}

internal data class NativeGridColumn(
    val key: String,
    val title: String,
    val kind: NativeGridColumnKind,
    val mandatory: Boolean,
    val options: List<NativeGridColumnOption> = emptyList(),
    val numberMin: Double? = null,
    val numberMax: Double? = null,
    val textMaxLength: Int? = null,
    val storedForm: NativeGridCellForm? = null,
) {
    /** Boolean and date-time cells are encoded only once a stored example fixes their representation. */
    val editable: Boolean
        get() = kind.editable && when (kind) {
            NativeGridColumnKind.Toggle -> storedForm in setOf(NativeGridCellForm.StringBoolean, NativeGridCellForm.JsonBoolean)
            NativeGridColumnKind.DateTime -> storedForm in setOf(NativeGridCellForm.SpaceDateTime, NativeGridCellForm.IsoDateTime)
            else -> true
        }
}

internal data class NativeGridRowWritePlan(
    val route: NativeCollectionRoute,
    val parentId: String,
    val listValues: List<String>,
    val cellField: String,
    val columns: List<NativeGridColumn>,
    val create: NativeContractAction?,
    val update: NativeItemWrite?,
    val delete: NativeItemWrite?,
) {
    val editableColumns: List<NativeGridColumn> get() = columns.filter(NativeGridColumn::editable)
}

/** Stored cells of one row keyed by column identity. */
internal data class NativeGridRow(
    val rowId: String,
    val cells: Map<String, JsonElement>,
    /** The row names the grid parent itself, so a global item route cannot address a foreign row. */
    val namesParent: Boolean,
    /** The exact stored JSON of the row's other fields, echoed by a replacement. */
    val wire: Map<String, JsonElement> = emptyMap(),
)

internal fun nativeGridRowWritePlan(
    schema: NativeAppSchema,
    view: ViewSpec,
    datasetContext: NativeDatasetContext,
    rows: List<NativeRecord> = emptyList(),
): NativeGridRowWritePlan? {
    val grid = view.compositeDataGrid ?: return null
    val parent = datasetContext.parentRecord?.takeIf { record ->
        record.actionSafeIdentity && record.actionBindingProvenanceValid &&
            datasetContext.parentResourceId == grid.parentResourceId
    } ?: return null
    val route = schema.action(grid.rowSourceActionId)?.nativeCollectionRoute() ?: return null
    val listValues = route.listValues(parent, datasetContext) ?: return null
    val permissions = parent.nativeWritePermissions(datasetContext.currentUserId)?.takeIf { it.any } ?: return null
    val columnRecords = datasetContext.relatedRecords[grid.columnResourceId] ?: return null
    // Columns that name a parent other than this one mean the column load was not scoped to it.
    val foreign = columnRecords.any { record ->
        record.valueNamed(route.parentReferenceKey)?.let { it != parent.id } == true
    }
    if (foreign) return null
    val create = schema.contractWrite(NativeContractWriteKind.Create, route.listShape)
        ?.takeIf { permissions.create && it.placeholders.size == listValues.size && it.action.cellMapField() != null }
    val update = schema.itemWrite(NativeContractWriteKind.Update, route)
        ?.takeIf { permissions.update && it.write.action.cellMapField() != null }
        ?.takeIf { it.write.action.binding.method == HttpMethod.PATCH || it.write.declaresReplacementRead }
    val delete = schema.itemWrite(NativeContractWriteKind.Delete, route)?.takeIf { permissions.delete }
    // Create and update must agree on the property that carries the cells.
    val cellFields = listOfNotNull(create?.action, update?.write?.action).mapNotNull { it.cellMapField() }.distinct()
    if (cellFields.size > 1) return null
    val cellField = cellFields.singleOrNull().orEmpty()
    val stored = rows.mapNotNull { row -> nativeGridStoredCells(row, cellField) }
    val columns = columnRecords.map { record ->
        parseNativeGridColumn(record, grid.columnIdentityFieldId, grid.columnTypeFieldId, grid.columnTitleFieldId, stored)
            ?: return null
    }
    if (columns.isEmpty() || columns.map(NativeGridColumn::key).distinct().size != columns.size) return null
    if (create == null && update == null && delete == null) return null
    return NativeGridRowWritePlan(route, parent.id, listValues, cellField, columns, create, update, delete)
}

/** The single body property declared as an open key-value map, which carries the cells. */
private fun ActionSpec.cellMapField(): String? {
    val properties = (binding.bodySchema as? JsonObject)?.get("properties") as? JsonObject ?: return null
    return properties.entries.filter { (name, schema) -> name in binding.bodyFieldNames && schema.isOpenMapSchema() }
        .singleOrNull()?.key
}

private fun JsonElement.isOpenMapSchema(): Boolean {
    val schema = this as? JsonObject ?: return false
    val alternatives = listOf("oneOf", "anyOf").flatMap { key -> (schema[key] as? JsonArray).orEmpty() }
    if (alternatives.isNotEmpty()) return alternatives.any(JsonElement::isOpenMapSchema)
    if ((schema["type"] as? JsonPrimitive)?.contentOrNull != "object") return false
    if ((schema["properties"] as? JsonObject)?.isNotEmpty() == true) return false
    val additional = schema["additionalProperties"] ?: return false
    return (additional as? JsonPrimitive)?.booleanOrNull != false
}

internal fun parseNativeGridColumn(
    record: NativeRecord,
    identityFieldId: String,
    typeFieldId: String?,
    titleFieldId: String,
    storedRows: List<Map<String, JsonElement>> = emptyList(),
): NativeGridColumn? {
    if (!record.actionSafeIdentity) return null
    val key = record.values[identityFieldId]?.trim()?.takeIf { it.isNotEmpty() && it.length <= 64 } ?: return null
    val title = record.values[titleFieldId]?.trim()?.takeIf(String::isNotEmpty)?.take(200) ?: "Column $key"
    val subtype = record.valueNamed("subtype") ?: record.valueNamed("variant")
    var kind = nativeGridColumnKind(typeFieldId?.let(record.values::get), subtype)
    val options = if (kind == NativeGridColumnKind.Choice || kind == NativeGridColumnKind.MultiChoice) {
        val raw = listOf("selectionoptions", "options", "choices").firstNotNullOfOrNull(record::valueNamed)
        parseGridOptions(raw) ?: emptyList<NativeGridColumnOption>().also { kind = NativeGridColumnKind.Unsupported }
    } else {
        emptyList()
    }
    fun number(vararg names: String) = names.firstNotNullOfOrNull(record::valueNamed)?.toDoubleOrNull()?.takeIf(Double::isFinite)
    val samples = storedRows.mapNotNull { it[key] }.filterNot { it is JsonNull }
    return NativeGridColumn(
        key = key,
        title = title,
        kind = kind,
        mandatory = listOf("mandatory", "required", "isrequired").firstNotNullOfOrNull(record::valueNamed)
            ?.toBooleanStrictOrNull() == true,
        options = options,
        numberMin = number("numbermin", "minimum", "min"),
        numberMax = number("numbermax", "maximum", "max"),
        textMaxLength = listOf("textmaxlength", "maxlength").firstNotNullOfOrNull(record::valueNamed)
            ?.toIntOrNull()?.takeIf { it > 0 },
        storedForm = when (kind) {
            // Every stored cell must use the one encoding the editor will write. Mixed or
            // unrecognized cells leave the column read-only rather than converting one silently.
            NativeGridColumnKind.Toggle -> samples.filter { it !is JsonNull }.map { sample ->
                val primitive = sample as? JsonPrimitive
                when {
                    primitive == null -> null
                    primitive.isString && primitive.content in setOf("true", "false") -> NativeGridCellForm.StringBoolean
                    !primitive.isString && primitive.booleanOrNull != null -> NativeGridCellForm.JsonBoolean
                    else -> null
                }
            }.distinct().singleOrNull()
            NativeGridColumnKind.DateTime -> samples.firstNotNullOfOrNull { sample ->
                val text = (sample as? JsonPrimitive)?.contentOrNull.orEmpty()
                when {
                    text.length >= 16 && text[10] == ' ' && text.take(10).isGridDate() -> NativeGridCellForm.SpaceDateTime
                    text.length >= 16 && text[10] == 'T' && text.take(10).isGridDate() -> NativeGridCellForm.IsoDateTime
                    else -> null
                }
            }
            else -> null
        },
    )
}

/** Maps the wording of a column type and its variant to an editor; unknown wording edits nothing. */
internal fun nativeGridColumnKind(type: String?, subtype: String?): NativeGridColumnKind {
    val main = type?.contractKey().orEmpty()
    val sub = subtype?.contractKey().orEmpty()
    return when (main) {
        "text", "string" -> when (sub) {
            "", "line", "short", "plain" -> NativeGridColumnKind.Text
            "long", "multiline", "textarea", "paragraph" -> NativeGridColumnKind.LongText
            else -> NativeGridColumnKind.Unsupported
        }
        "number", "numeric", "decimal", "integer" -> when (sub) {
            "" -> NativeGridColumnKind.Number
            "stars", "rating" -> NativeGridColumnKind.Rating
            "progress", "percent", "percentage" -> NativeGridColumnKind.Percent
            else -> NativeGridColumnKind.Unsupported
        }
        "selection", "select", "choice", "enum", "option" -> when (sub) {
            "", "single" -> NativeGridColumnKind.Choice
            "multi", "multiple" -> NativeGridColumnKind.MultiChoice
            "check", "checkbox", "boolean", "toggle" -> NativeGridColumnKind.Toggle
            else -> NativeGridColumnKind.Unsupported
        }
        "datetime" -> when (sub) {
            "" -> NativeGridColumnKind.DateTime
            "date" -> NativeGridColumnKind.Date
            "time" -> NativeGridColumnKind.Time
            else -> NativeGridColumnKind.Unsupported
        }
        "date" -> NativeGridColumnKind.Date.takeIf { sub.isEmpty() } ?: NativeGridColumnKind.Unsupported
        "time" -> NativeGridColumnKind.Time.takeIf { sub.isEmpty() } ?: NativeGridColumnKind.Unsupported
        "boolean", "bool", "checkbox" -> NativeGridColumnKind.Toggle
        else -> NativeGridColumnKind.Unsupported
    }
}

private fun parseGridOptions(raw: String?): List<NativeGridColumnOption>? {
    val array = raw.contractJsonOrNull() as? JsonArray ?: return null
    if (array.size > MAX_GRID_OPTIONS) return null
    val options = array.map { element ->
        val option = element as? JsonObject ?: return null
        val id = (option["id"] as? JsonPrimitive)?.takeUnless { it is JsonNull } ?: return null
        val label = listOf("label", "name", "title", "value").firstNotNullOfOrNull { key ->
            (option[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
        } ?: return null
        NativeGridColumnOption(id, label.take(200))
    }
    return options.takeIf { list -> list.map(NativeGridColumnOption::key).distinct().size == list.size }
}

/** The stored cell list `[{<column reference>, value}]` of [record], keyed by column identity. */
private fun nativeGridStoredCells(record: NativeRecord, cellField: String): Map<String, JsonElement>? {
    val array = record.values[cellField].contractJsonOrNull() as? JsonArray ?: return null
    val cells = linkedMapOf<String, JsonElement>()
    array.forEach { element ->
        val cell = element as? JsonObject ?: return null
        if ("value" !in cell) return null
        val references = cell.filterKeys { it.contractKey() in CELL_COLUMN_REFERENCES }
        val column = (references.values.singleOrNull() as? JsonPrimitive)?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= 64 } ?: return null
        if (cells.put(column, cell.getValue("value")) != null) return null
    }
    return cells
}

internal fun NativeGridRowWritePlan.row(record: NativeRecord): NativeGridRow? {
    if (!record.actionSafeIdentity || !record.actionBindingProvenanceValid) return null
    val rowId = record.id.takeIf(String::isSafeContractPathValue) ?: return null
    val cells = if (cellField.isEmpty()) emptyMap() else nativeGridStoredCells(record, cellField) ?: return null
    return NativeGridRow(rowId, cells, namesParent = record.valueNamed(route.parentReferenceKey) == parentId, wire = record.wireValues)
}

/** The draft text shown for a stored cell value. Unknown shapes produce an empty draft. */
internal fun NativeGridColumn.draftFrom(value: JsonElement?): String {
    val primitive = value as? JsonPrimitive
    val text = primitive?.takeUnless { it is JsonNull }?.contentOrNull.orEmpty()
    return when (kind) {
        NativeGridColumnKind.Number -> text.toDoubleOrNull()?.let(::formatGridNumber).orEmpty()
        NativeGridColumnKind.Rating, NativeGridColumnKind.Percent -> text.toDoubleOrNull()?.toLong()?.toString().orEmpty()
        NativeGridColumnKind.Choice -> text.takeIf { id -> options.any { it.key == id } }.orEmpty()
        NativeGridColumnKind.MultiChoice -> (value as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .filter { id -> options.any { it.key == id } }
            .sorted().joinToString(",")
        NativeGridColumnKind.Toggle -> if (text == "true") "true" else "false"
        NativeGridColumnKind.DateTime -> text.take(16).replace('T', ' ')
        else -> text
    }
}

/** Whether a stored cell has content that this editor would have to send back unchanged. */
private fun JsonElement?.hasContent(): Boolean = when (this) {
    null, JsonNull -> false
    is JsonPrimitive -> contentOrNull?.isNotEmpty() == true
    is JsonArray -> isNotEmpty()
    is JsonObject -> isNotEmpty()
}

internal fun formatGridNumber(value: Double): String =
    if (value % 1.0 == 0.0 && kotlin.math.abs(value) < 1e15) value.toLong().toString() else value.toString()

/** Returns a user-facing problem with [draft], or null when it can be sent. */
internal fun NativeGridColumn.validateDraft(draft: String): String? {
    val value = draft.trim()
    if (value.isEmpty() || (kind == NativeGridColumnKind.Toggle && !mandatory)) {
        return if (mandatory && kind != NativeGridColumnKind.Toggle) "$title is required." else null
    }
    return when (kind) {
        NativeGridColumnKind.Text, NativeGridColumnKind.LongText ->
            textMaxLength?.takeIf { draft.length > it }?.let { "Use at most $it characters." }
        NativeGridColumnKind.Number -> {
            val number = value.toDoubleOrNull()?.takeIf(Double::isFinite) ?: return "Enter a number."
            when {
                numberMin != null && number < numberMin -> "Enter at least ${formatGridNumber(numberMin)}."
                numberMax != null && number > numberMax -> "Enter at most ${formatGridNumber(numberMax)}."
                else -> null
            }
        }
        NativeGridColumnKind.Rating -> if (value.toIntOrNull() !in 0..5) "Choose 0 to 5 stars." else null
        NativeGridColumnKind.Percent -> if (value.toIntOrNull() !in 0..100) "Enter 0 to 100." else null
        NativeGridColumnKind.Choice -> if (options.none { it.key == value }) "Choose one of the options." else null
        NativeGridColumnKind.MultiChoice ->
            if (value.split(',').any { part -> options.none { it.key == part.trim() } }) "Choose from the listed options." else null
        NativeGridColumnKind.DateTime -> if (!value.isGridDateTime()) "Use YYYY-MM-DD HH:MM." else null
        NativeGridColumnKind.Date -> if (!value.isGridDate()) "Use YYYY-MM-DD." else null
        NativeGridColumnKind.Time -> if (!value.isGridTime()) "Use HH:MM." else null
        NativeGridColumnKind.Toggle -> null
        NativeGridColumnKind.Unsupported -> "This column cannot be edited here."
    }
}

/** Encodes a validated draft in the representation the column's options or stored cells establish. */
internal fun NativeGridColumn.encodeDraft(draft: String): JsonElement {
    val value = draft.trim()
    if (value.isEmpty() && kind != NativeGridColumnKind.Toggle) {
        return when (kind) {
            NativeGridColumnKind.Text, NativeGridColumnKind.LongText -> JsonPrimitive("")
            NativeGridColumnKind.MultiChoice -> JsonArray(emptyList())
            else -> JsonNull
        }
    }
    return when (kind) {
        NativeGridColumnKind.Text, NativeGridColumnKind.LongText -> JsonPrimitive(draft)
        NativeGridColumnKind.Number -> value.toDoubleOrNull()?.let(::JsonPrimitive) ?: JsonNull
        NativeGridColumnKind.Rating, NativeGridColumnKind.Percent -> JsonPrimitive(value.toIntOrNull() ?: 0)
        NativeGridColumnKind.Choice -> options.firstOrNull { it.key == value }?.id ?: JsonNull
        NativeGridColumnKind.MultiChoice -> JsonArray(
            value.split(',').map(String::trim).distinct().sorted()
                .mapNotNull { part -> options.firstOrNull { it.key == part }?.id },
        )
        NativeGridColumnKind.Toggle -> when (storedForm) {
            NativeGridCellForm.JsonBoolean -> JsonPrimitive(value == "true")
            else -> JsonPrimitive(if (value == "true") "true" else "false")
        }
        NativeGridColumnKind.DateTime ->
            JsonPrimitive(if (storedForm == NativeGridCellForm.IsoDateTime) value.replace(' ', 'T') else value)
        NativeGridColumnKind.Date, NativeGridColumnKind.Time -> JsonPrimitive(value)
        NativeGridColumnKind.Unsupported -> error("An unsupported column is never encoded.")
    }
}

internal sealed interface NativeGridRowRequestResult {
    data class Ready(val request: NativeActionRequest.Submit) : NativeGridRowRequestResult

    data class Invalid(val fieldErrors: Map<String, String>) : NativeGridRowRequestResult

    data object NoChanges : NativeGridRowRequestResult

    /** The write is not offered for this row; [reason] explains why in user terms. */
    data class Unavailable(val reason: String) : NativeGridRowRequestResult
}

internal fun NativeGridRowWritePlan.createRequest(drafts: Map<String, String>): NativeGridRowRequestResult {
    val create = create ?: return unavailable("This table's contract does not offer adding rows.")
    val errors = validate(drafts, editableColumns)
    if (errors.isNotEmpty()) return NativeGridRowRequestResult.Invalid(errors)
    if (columns.any { it.mandatory && !it.editable }) {
        return unavailable("A required column uses a type that cannot be filled in here.")
    }
    val data = buildJsonObject {
        editableColumns.forEach { column ->
            val draft = drafts[column.key].orEmpty()
            // Unset optional cells are omitted so the grid's own defaults apply.
            if (draft.isNotBlank() || (column.kind == NativeGridColumnKind.Toggle && column.mandatory)) {
                put(column.key, column.encodeDraft(draft))
            }
        }
    }
    return ready(create.action, create.placeholders.zip(listValues).toMap() + (cellField to data.toString()))
}

private fun JsonElement?.isMinuteDateTimeOrEmpty(): Boolean {
    val text = (this as? JsonPrimitive)?.contentOrNull ?: return this == null || this is JsonNull
    return text.isEmpty() || text.length == MINUTE_DATE_TIME_LENGTH
}

/** `YYYY-MM-DD HH:MM` or its `T` form: the whole value the editor can show and write back. */
private const val MINUTE_DATE_TIME_LENGTH = 16

/**
 * PATCH sends only the changed cells, after the row is read again and those cells are found
 * unchanged since the editor opened. PUT replaces the row, so it is offered only when the
 * contract declares the same-route read, and it is a full echo: cells the person did not change
 * travel exactly as stored, never re-encoded, and every other declared body field is echoed from
 * the row as returned. Immediately before the write the row is read again and nothing is sent when
 * any stored cell or echoed field differs from the copy the editor opened; after the write the row
 * is read back and each cell compared with what was sent. A row holding a value this editor cannot
 * keep (a column type it cannot encode, or a cell of a column this grid does not list, as in a
 * filtered view) is not replaced, so nothing is cleared.
 */
internal fun NativeGridRowWritePlan.updateRequest(row: NativeGridRow, drafts: Map<String, String>): NativeGridRowRequestResult {
    val update = update ?: return unavailable("This table's contract does not offer editing rows.")
    val values = itemValues(update, row) ?: return unavailable("This row cannot be matched to its table safely.")
    val changed = editableColumns.filter { column -> drafts[column.key]?.let { it != column.draftFrom(row.cells[column.key]) } == true }
    if (changed.isEmpty()) return NativeGridRowRequestResult.NoChanges
    val errors = validate(drafts, changed)
    if (errors.isNotEmpty()) return NativeGridRowRequestResult.Invalid(errors)
    // The editor holds a date and minutes. A stored value with seconds or a time zone would lose
    // them when rewritten, so such a cell is left as it is; untouched it still travels unchanged.
    changed.firstOrNull { column -> column.kind == NativeGridColumnKind.DateTime && !row.cells[column.key].isMinuteDateTimeOrEmpty() }
        ?.let { column ->
            return NativeGridRowRequestResult.Invalid(
                mapOf(column.key to "This date and time has seconds or a time zone that cannot be kept here, so it cannot be changed here."),
            )
        }
    val edited = changed.associate { column -> column.key to column.encodeDraft(drafts.getValue(column.key)) }
    val verifyCells: (NativeRecord) -> NativeWriteVerification = { current -> verifyCells(current, edited, row.cells) }
    if (update.write.action.binding.method != HttpMethod.PUT) {
        // Only the changed cells travel, so only they are compared with a fresh read first.
        val editedKeys = edited.keys
        val readBack = NativeWriteReadBack(
            row.rowId, emptyMap(), emptyMap(), echoUntouched = false, verifyCells, setOf(cellField),
            preReadBaseline = true,
            customBaselineHolds = { current ->
                nativeGridStoredCells(current, cellField)?.let { stored ->
                    editedKeys.all { key -> sameWireValue(stored[key], row.cells[key]) }
                } == true
            },
        )
        return ready(update.write.action, values + (cellField to JsonObject(edited).toString()), readBack = readBack)
    }
    val listed = columns.mapTo(hashSetOf(), NativeGridColumn::key)
    val unkept = columns.any { !it.editable && row.cells[it.key].hasContent() } ||
        row.cells.any { (key, value) -> key !in listed && value.hasContent() }
    if (unkept) return unavailable("This row has a value this editor cannot keep, so it is not edited here.")
    val data = buildJsonObject {
        editableColumns.forEach { column -> (edited[column.key] ?: row.cells[column.key])?.let { put(column.key, it) } }
    }
    val echo = when (val plan = nativeFullEchoUpdatePlan(update.write.action, row.wire, setOf(cellField), update.contextInputs + scopingInputs(update, row))) {
        is NativeFullEchoUpdatePlan.Echo -> plan.echo
        is NativeFullEchoUpdatePlan.ReadOnly -> return unavailable(plan.message)
        is NativeFullEchoUpdatePlan.Sparse -> emptyMap()
    }
    val snapshot = row.cells
    val precondition = NativeWritePrecondition(
        readValues = values.filterKeys { it in update.write.placeholders },
        targetId = row.rowId,
    ) { current ->
        nativeGridStoredCells(current, cellField) == snapshot &&
            echo.all { (name, value) -> sameWireValue(current.wireValues.valueNamed(name), value) }
    }
    val readBack = NativeWriteReadBack(
        targetId = row.rowId,
        previous = mapOf(cellField to JsonObject(snapshot)),
        untouched = echo,
        echoUntouched = true,
        verifyTarget = verifyCells,
        customFields = setOf(cellField),
        verifyRestoreTarget = { current ->
            val restored = nativeGridStoredCells(current, cellField)
            when {
                restored == null -> NativeWriteVerification.Unidentified
                sameWireValue(JsonObject(restored), JsonObject(snapshot)) -> NativeWriteVerification.Confirmed
                else -> NativeWriteVerification.NotApplied("The previous cell values were not restored.")
            }
        },
    )
    return ready(update.write.action, values + (cellField to data.toString()), precondition, readBack)
}

/**
 * A cell-map row's representation is its stored cells. A declared body input outside the cell map
 * that is optional, accepts null, and is not part of what the row read returned (such as a view
 * that scopes the request) is a request scope, not stored row data, so it is not echoed or sent.
 * An unreturned input that is required or not nullable still keeps the row read-only.
 */
private fun NativeGridRowWritePlan.scopingInputs(update: NativeItemWrite, row: NativeGridRow): Set<String> {
    val schema = update.write.action.binding.bodySchema as? JsonObject ?: return emptySet()
    val properties = schema["properties"] as? JsonObject ?: return emptySet()
    val required = (schema["required"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty().toSet()
    return properties.filter { (name, property) ->
        name != cellField && name !in required && row.wire.valueNamed(name) == null && JsonNull.fitsDeclaredSchema(property)
    }.keys
}

/**
 * Compares the cells read back with the write: an edited cell must hold what was sent, and every
 * other cell what the editor opened. All edits reverting to their stored values means not applied.
 */
private fun NativeGridRowWritePlan.verifyCells(
    current: NativeRecord,
    edited: Map<String, JsonElement>,
    stored: Map<String, JsonElement>,
): NativeWriteVerification {
    val cells = nativeGridStoredCells(current, cellField) ?: return NativeWriteVerification.Unidentified
    val adjusted = edited.filter { (key, value) -> !sameWireValue(cells[key], value) }.keys
    val drifted = stored.filter { (key, value) -> key !in edited && !sameWireValue(cells[key], value) }.keys
    val reverted = edited.isNotEmpty() && adjusted.size == edited.size && edited.keys.all { key -> sameWireValue(cells[key], stored[key]) }
    return when {
        reverted && drifted.isEmpty() -> NativeWriteVerification.NotApplied("The app accepted the change, but it was not applied.")
        adjusted.isEmpty() && drifted.isEmpty() -> NativeWriteVerification.Confirmed
        else -> NativeWriteVerification.Adjusted(adjusted.mapTo(linkedSetOf()) { "$cellField.$it" }, drifted.mapTo(linkedSetOf()) { "$cellField.$it" })
    }
}

/** How an unconfirmed edit may be repeated: a replacement only after a fresh read. */
internal val NativeGridRowWritePlan.updateRetry: NativeEditorWriteRetry
    get() = if (update?.write?.action?.binding?.method == HttpMethod.PUT) NativeEditorWriteRetry.AfterRefresh else NativeEditorWriteRetry.Idempotent

internal fun NativeGridRowWritePlan.deleteRequest(row: NativeGridRow): NativeGridRowRequestResult {
    val delete = delete ?: return unavailable("This table's contract does not offer deleting rows.")
    val values = itemValues(delete, row) ?: return unavailable("This row cannot be matched to its table safely.")
    return ready(delete.write.action, values)
}

internal fun NativeGridRowWritePlan.itemValues(write: NativeItemWrite, row: NativeGridRow): Map<String, String>? =
    write.values(listValues, parentId, row.rowId, row.namesParent)

private fun validate(drafts: Map<String, String>, columns: List<NativeGridColumn>): Map<String, String> =
    columns.mapNotNull { column -> column.validateDraft(drafts[column.key].orEmpty())?.let { column.key to it } }.toMap()

private fun ready(
    action: ActionSpec,
    values: Map<String, String>,
    precondition: NativeWritePrecondition? = null,
    readBack: NativeWriteReadBack? = null,
) = NativeGridRowRequestResult.Ready(NativeActionRequest.Submit(action, values, confirmed = true, precondition = precondition, readBack = readBack))

private fun unavailable(reason: String) = NativeGridRowRequestResult.Unavailable(reason)

internal fun NativeRecord.valueNamed(name: String): String? {
    val key = name.contractKey()
    return values.entries.firstOrNull { it.key.contractKey() == key }?.value?.trim()?.takeIf(String::isNotEmpty)
}

private fun String.isGridDate(): Boolean {
    if (length != 10 || this[4] != '-' || this[7] != '-') return false
    val year = substring(0, 4).toIntOrNull() ?: return false
    val month = substring(5, 7).toIntOrNull() ?: return false
    val day = substring(8, 10).toIntOrNull() ?: return false
    if (year < 1 || month !in 1..12) return false
    val leap = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
    val days = intArrayOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)[month - 1]
    return day in 1..days
}

private fun String.isGridTime(): Boolean {
    if (length != 5 || this[2] != ':') return false
    val hour = substring(0, 2).toIntOrNull() ?: return false
    val minute = substring(3, 5).toIntOrNull() ?: return false
    return all { it.isDigit() || it == ':' } && hour in 0..23 && minute in 0..59
}

private fun String.isGridDateTime(): Boolean =
    length == 16 && this[10] == ' ' && substring(0, 10).isGridDate() && substring(11).isGridTime()

/** ISO calendar date check shared by record editors. */
internal fun String.isIsoCalendarDate(): Boolean = isGridDate()

/** 24-hour HH:MM check shared by record editors. */
internal fun String.isClockTime(): Boolean = isGridTime()

private val CELL_COLUMN_REFERENCES = setOf("columnid", "column", "fieldid", "field", "key")
private const val MAX_GRID_OPTIONS = 500

/**
 * Inline cell editing for grid surfaces. A column-defined grid stores cells as `{column, value}`
 * entries, so the generic row-object reconstruction would send that wrapper and the server would
 * store it as the cell value. Such grids are edited through the row editor above, which encodes
 * each value for its column.
 */
internal fun nativeInlineCellEditPlan(
    schema: NativeAppSchema,
    resource: ResourceSpec,
    projection: NativeTableProjection,
    record: NativeRecord,
    field: FieldSpec,
): NativeCellEditPlan? =
    if (projection.composite) null else nativeCellEditPlan(schema, resource, projection, record, field)
