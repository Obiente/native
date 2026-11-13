package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.ViewSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/*
 * Reviewed Tables row writes.
 *
 * The Tables OpenAPI document declares the row `data` body as `string | object<object>`, which
 * does not describe the accepted values. The reviewed server source defines them:
 * - Api1Controller::createRowInTable, createRowInView and updateRow cast each `data` key to an
 *   integer column ID and pass the value to the column type parser:
 *   https://github.com/nextcloud/tables/blob/v2.4.0/lib/Controller/Api1Controller.php
 * - RowService::updateSet merges the submitted cells into the stored row, so an update sends only
 *   the cells the person changed:
 *   https://github.com/nextcloud/tables/blob/v2.4.0/lib/Service/RowService.php
 * - Each column type's accepted value is defined by its parser in
 *   https://github.com/nextcloud/tables/tree/v2.4.0/lib/Service/ColumnTypes
 *
 * Only Tables 2.x is accepted. Every action still comes from the verified contract; this adapter
 * adds no route, and column types it cannot encode exactly are kept unchanged.
 */

internal enum class NativeTablesColumnType(val editable: Boolean) {
    TextLine(true),
    TextLong(true),
    TextLink(true),
    TextRich(false),
    Number(true),
    NumberStars(true),
    NumberProgress(true),
    Selection(true),
    SelectionMulti(true),
    SelectionCheck(true),
    DateTime(true),
    Date(true),
    Time(true),
    Unsupported(false),
}

internal data class NativeTablesSelectionOption(val id: Long, val label: String)

internal data class NativeTablesColumn(
    val id: Long,
    val title: String,
    val type: NativeTablesColumnType,
    val mandatory: Boolean,
    val options: List<NativeTablesSelectionOption> = emptyList(),
    val numberMin: Double? = null,
    val numberMax: Double? = null,
    val numberDecimals: Int? = null,
    val textMaxLength: Int? = null,
)

internal sealed interface NativeTablesRowScope {
    val id: Long

    data class Table(override val id: Long) : NativeTablesRowScope

    data class View(override val id: Long) : NativeTablesRowScope
}

internal data class NativeTablesRowWritePlan(
    val scope: NativeTablesRowScope,
    val columns: List<NativeTablesColumn>,
    val create: NativeReviewedAction?,
    val update: NativeReviewedAction?,
    val delete: NativeReviewedAction?,
) {
    val editableColumns: List<NativeTablesColumn> get() = columns.filter { it.type.editable }
}

/** Raw stored cells of one row, keyed by column ID, or null when the row is not editable. */
internal data class NativeTablesRowCells(val rowId: Long, val cells: Map<Long, JsonElement>)

internal fun NativeAppSchema.supportsReviewedTablesRowWrites(): Boolean =
    app.id == "tables" && app.reviewedMajorMinor()?.first == 2

internal fun nativeTablesRowWritePlan(
    schema: NativeAppSchema,
    view: ViewSpec,
    datasetContext: NativeDatasetContext,
): NativeTablesRowWritePlan? {
    if (!schema.supportsReviewedTablesRowWrites()) return null
    val grid = view.compositeDataGrid ?: return null
    val parent = datasetContext.parentRecord?.takeIf { record ->
        record.actionSafeIdentity && record.actionBindingProvenanceValid &&
            datasetContext.parentResourceId == grid.parentResourceId
    } ?: return null
    val parentId = parent.id.reviewedPositiveId() ?: return null
    val scope = when (grid.parentResourceId) {
        "tables" -> NativeTablesRowScope.Table(parentId)
        "views" -> NativeTablesRowScope.View(parentId)
        else -> return null
    }
    val permissions = parent.tablesSharePermissions() ?: return null
    val columnRecords = datasetContext.relatedRecords[grid.columnResourceId] ?: return null
    val columns = columnRecords.map { record -> parseNativeTablesColumn(record) ?: return null }
    if (columns.isEmpty() || columns.map(NativeTablesColumn::id).distinct().size != columns.size) return null
    if (scope is NativeTablesRowScope.Table) {
        // Table columns name their table. A column of another table means the load was not scoped.
        val foreign = columnRecords.any { record ->
            record.values["tableId"]?.let { it.reviewedPositiveId() != parentId } == true
        }
        if (foreign) return null
    }
    val create = when (scope) {
        is NativeTablesRowScope.Table -> schema.reviewedAction(HttpMethod.POST, TABLE_ROWS_ROUTE, setOf("data"))
        is NativeTablesRowScope.View -> schema.reviewedAction(HttpMethod.POST, VIEW_ROWS_ROUTE, setOf("data"))
    }?.takeIf { it.placeholders.size == 1 && permissions.create }
    val update = schema.reviewedAction(HttpMethod.PUT, ROW_ROUTE, setOf("data"))
        ?.takeIf { action ->
            action.placeholders.size == 1 && permissions.update &&
                (scope is NativeTablesRowScope.Table || "viewId" in action.action.binding.bodyFieldNames)
        }
    val delete = when (scope) {
        is NativeTablesRowScope.Table -> schema.reviewedAction(HttpMethod.DELETE, ROW_ROUTE)
            ?.takeIf { it.placeholders.size == 1 }
        is NativeTablesRowScope.View -> schema.reviewedAction(HttpMethod.DELETE, VIEW_ROW_ROUTE)
            ?.takeIf { it.placeholders.size == 2 }
    }?.takeIf { permissions.delete }
    if (create == null && update == null && delete == null) return null
    return NativeTablesRowWritePlan(scope, columns, create, update, delete)
}

private data class NativeTablesPermissions(val create: Boolean, val update: Boolean, val delete: Boolean)

/**
 * An owned table or view grants every row permission. A shared one grants only the flags its
 * `onSharePermissions` object declares; missing or malformed flags grant nothing.
 */
private fun NativeRecord.tablesSharePermissions(): NativeTablesPermissions? {
    val shared = values["isShared"]?.trim()?.toBooleanStrictOrNull() ?: return null
    if (!shared) return NativeTablesPermissions(create = true, update = true, delete = true)
    val flags = values["onSharePermissions"].reviewedJsonOrNull() as? JsonObject
        ?: return NativeTablesPermissions(create = false, update = false, delete = false)
    fun flag(name: String) = (flags[name] as? JsonPrimitive)?.booleanOrNull == true
    return NativeTablesPermissions(create = flag("create"), update = flag("update"), delete = flag("delete"))
}

internal fun parseNativeTablesColumn(record: NativeRecord): NativeTablesColumn? {
    if (!record.actionSafeIdentity) return null
    val id = record.id.reviewedPositiveId() ?: return null
    val title = record.values["title"]?.trim()?.takeIf(String::isNotEmpty)?.take(200) ?: "Column $id"
    val type = nativeTablesColumnType(record.values["type"], record.values["subtype"])
    val options = if (type in setOf(NativeTablesColumnType.Selection, NativeTablesColumnType.SelectionMulti)) {
        parseTablesSelectionOptions(record.values["selectionOptions"]) ?: return NativeTablesColumn(
            id, title, NativeTablesColumnType.Unsupported, mandatory = false,
        )
    } else {
        emptyList()
    }
    return NativeTablesColumn(
        id = id,
        title = title,
        type = type,
        mandatory = record.values["mandatory"]?.trim()?.toBooleanStrictOrNull() == true,
        options = options,
        numberMin = record.values["numberMin"]?.trim()?.toDoubleOrNull()?.takeIf(Double::isFinite),
        numberMax = record.values["numberMax"]?.trim()?.toDoubleOrNull()?.takeIf(Double::isFinite),
        numberDecimals = record.values["numberDecimals"]?.trim()?.toIntOrNull()?.takeIf { it in 0..10 },
        textMaxLength = record.values["textMaxLength"]?.trim()?.toIntOrNull()?.takeIf { it > 0 },
    )
}

internal fun nativeTablesColumnType(type: String?, subtype: String?): NativeTablesColumnType {
    val main = type?.trim()?.lowercase().orEmpty()
    val sub = subtype?.trim()?.lowercase().orEmpty()
    return when (main to sub) {
        "text" to "line" -> NativeTablesColumnType.TextLine
        "text" to "long" -> NativeTablesColumnType.TextLong
        "text" to "link" -> NativeTablesColumnType.TextLink
        "text" to "rich" -> NativeTablesColumnType.TextRich
        "number" to "" -> NativeTablesColumnType.Number
        "number" to "stars" -> NativeTablesColumnType.NumberStars
        "number" to "progress" -> NativeTablesColumnType.NumberProgress
        "selection" to "" -> NativeTablesColumnType.Selection
        "selection" to "multi" -> NativeTablesColumnType.SelectionMulti
        "selection" to "check" -> NativeTablesColumnType.SelectionCheck
        "datetime" to "" -> NativeTablesColumnType.DateTime
        "datetime" to "date" -> NativeTablesColumnType.Date
        "datetime" to "time" -> NativeTablesColumnType.Time
        else -> NativeTablesColumnType.Unsupported
    }
}

private fun parseTablesSelectionOptions(raw: String?): List<NativeTablesSelectionOption>? {
    val array = raw.reviewedJsonOrNull() as? JsonArray ?: return null
    if (array.size > MAX_SELECTION_OPTIONS) return null
    val options = array.map { element ->
        val option = element as? JsonObject ?: return null
        val id = (option["id"] as? JsonPrimitive)?.longOrNull ?: return null
        val label = (option["label"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
            ?: return null
        NativeTablesSelectionOption(id, label.take(200))
    }
    return options.takeIf { list -> list.map(NativeTablesSelectionOption::id).distinct().size == list.size }
}

/** Reads the stored `data` array of one row. Rows without a complete cell list are not editable. */
internal fun nativeTablesRowCells(record: NativeRecord): NativeTablesRowCells? {
    if (!record.actionSafeIdentity || !record.actionBindingProvenanceValid) return null
    val rowId = record.id.reviewedPositiveId() ?: return null
    val array = record.values["data"].reviewedJsonOrNull() as? JsonArray ?: return null
    val cells = linkedMapOf<Long, JsonElement>()
    array.forEach { element ->
        val cell = element as? JsonObject ?: return null
        val columnId = (cell["columnId"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 } ?: return null
        if (cells.put(columnId, cell["value"] ?: JsonNull) != null) return null
    }
    return NativeTablesRowCells(rowId, cells)
}

/** The draft text shown for a stored cell value. Unknown shapes produce an empty draft. */
internal fun NativeTablesColumn.draftFrom(value: JsonElement?): String {
    val primitive = value as? JsonPrimitive
    val text = primitive?.takeUnless { it is JsonNull }?.contentOrNull.orEmpty()
    return when (type) {
        NativeTablesColumnType.TextLink -> {
            val link = text.reviewedJsonOrNull() as? JsonObject
            (link?.get("value") as? JsonPrimitive)?.contentOrNull ?: text
        }
        NativeTablesColumnType.Number -> text.toDoubleOrNull()?.let(::formatTablesNumber).orEmpty()
        NativeTablesColumnType.NumberStars,
        NativeTablesColumnType.NumberProgress,
        -> text.toDoubleOrNull()?.toLong()?.toString().orEmpty()
        NativeTablesColumnType.Selection -> text.toLongOrNull()
            ?.takeIf { id -> options.any { it.id == id } }?.toString().orEmpty()
        NativeTablesColumnType.SelectionMulti -> (value as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.longOrNull }
            .filter { id -> options.any { it.id == id } }
            .sorted().joinToString(",")
        NativeTablesColumnType.SelectionCheck -> if (text == "true") "true" else "false"
        else -> text
    }
}

private fun formatTablesNumber(value: Double): String =
    if (value % 1.0 == 0.0 && kotlin.math.abs(value) < 1e15) value.toLong().toString() else value.toString()

/** Returns a user-facing problem with [draft], or null when it can be sent. */
internal fun NativeTablesColumn.validateDraft(draft: String): String? {
    val value = draft.trim()
    if (value.isEmpty() || (type == NativeTablesColumnType.SelectionCheck && !mandatory)) {
        return if (mandatory && type != NativeTablesColumnType.SelectionCheck) "$title is required." else null
    }
    return when (type) {
        NativeTablesColumnType.TextLine, NativeTablesColumnType.TextLong ->
            textMaxLength?.takeIf { draft.length > it }?.let { "Use at most $it characters." }
        NativeTablesColumnType.TextLink ->
            if (!value.startsWith("https://") && !value.startsWith("http://")) "Enter a web address starting with https://." else null
        NativeTablesColumnType.Number -> {
            val number = value.toDoubleOrNull()?.takeIf(Double::isFinite) ?: return "Enter a number."
            when {
                numberMin != null && number < numberMin -> "Enter at least ${formatTablesNumber(numberMin)}."
                numberMax != null && number > numberMax -> "Enter at most ${formatTablesNumber(numberMax)}."
                else -> null
            }
        }
        NativeTablesColumnType.NumberStars -> if (value.toIntOrNull() !in 0..5) "Choose 0 to 5 stars." else null
        NativeTablesColumnType.NumberProgress -> if (value.toIntOrNull() !in 0..100) "Enter 0 to 100." else null
        NativeTablesColumnType.Selection ->
            if (options.none { it.id.toString() == value }) "Choose one of the options." else null
        NativeTablesColumnType.SelectionMulti ->
            if (value.split(',').any { part -> options.none { it.id.toString() == part.trim() } }) {
                "Choose from the listed options."
            } else {
                null
            }
        NativeTablesColumnType.DateTime -> if (!value.isTablesDateTime()) "Use YYYY-MM-DD HH:MM." else null
        NativeTablesColumnType.Date -> if (!value.isTablesDate()) "Use YYYY-MM-DD." else null
        NativeTablesColumnType.Time -> if (!value.isTablesTime()) "Use HH:MM." else null
        NativeTablesColumnType.SelectionCheck -> null
        NativeTablesColumnType.TextRich, NativeTablesColumnType.Unsupported -> "This column cannot be edited here."
    }
}

/** Encodes a validated draft exactly as the column type parser accepts it. */
internal fun NativeTablesColumn.encodeDraft(draft: String): JsonElement {
    val value = draft.trim()
    return when (type) {
        NativeTablesColumnType.TextLine, NativeTablesColumnType.TextLong -> JsonPrimitive(draft)
        // The parser's JSON form is its default case; the plain-URL form truncates some hosts.
        NativeTablesColumnType.TextLink -> if (value.isEmpty()) {
            JsonPrimitive("")
        } else {
            JsonPrimitive(
                buildJsonObject {
                    put("title", value)
                    put("value", value)
                    put("providerId", "url")
                }.toString(),
            )
        }
        NativeTablesColumnType.Number -> value.toDoubleOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive("")
        NativeTablesColumnType.NumberStars,
        NativeTablesColumnType.NumberProgress,
        -> JsonPrimitive(value.toIntOrNull() ?: 0)
        NativeTablesColumnType.Selection -> value.toLongOrNull()?.let(::JsonPrimitive) ?: JsonNull
        NativeTablesColumnType.SelectionMulti -> JsonArray(
            value.split(',').mapNotNull { it.trim().toLongOrNull() }.distinct().sorted().map(::JsonPrimitive),
        )
        NativeTablesColumnType.SelectionCheck -> JsonPrimitive(if (value == "true") "true" else "false")
        NativeTablesColumnType.DateTime,
        NativeTablesColumnType.Date,
        NativeTablesColumnType.Time,
        -> JsonPrimitive(value)
        NativeTablesColumnType.TextRich, NativeTablesColumnType.Unsupported ->
            error("Column type ${type.name} is never encoded.")
    }
}

internal sealed interface NativeTablesRowRequestResult {
    data class Ready(val request: NativeActionRequest.Submit) : NativeTablesRowRequestResult

    data class Invalid(val fieldErrors: Map<Long, String>) : NativeTablesRowRequestResult

    data object NoChanges : NativeTablesRowRequestResult

    data object Unavailable : NativeTablesRowRequestResult
}

internal fun NativeTablesRowWritePlan.createRequest(drafts: Map<Long, String>): NativeTablesRowRequestResult {
    val create = create ?: return NativeTablesRowRequestResult.Unavailable
    val errors = validate(drafts, editableColumns)
    if (errors.isNotEmpty()) return NativeTablesRowRequestResult.Invalid(errors)
    val data = buildJsonObject {
        editableColumns.forEach { column ->
            val draft = drafts[column.id].orEmpty()
            // Unset optional cells are omitted so the table's own defaults apply.
            if (draft.isNotBlank() || column.type == NativeTablesColumnType.SelectionCheck) {
                put(column.id.toString(), column.encodeDraft(draft))
            }
        }
    }
    return ready(create.action, mapOf(create.placeholders.single() to scope.id.toString(), "data" to data.toString()))
}

internal fun NativeTablesRowWritePlan.updateRequest(
    row: NativeTablesRowCells,
    drafts: Map<Long, String>,
): NativeTablesRowRequestResult {
    val update = update ?: return NativeTablesRowRequestResult.Unavailable
    val changed = editableColumns.filter { column ->
        drafts[column.id]?.let { it != column.draftFrom(row.cells[column.id]) } == true
    }
    if (changed.isEmpty()) return NativeTablesRowRequestResult.NoChanges
    val errors = validate(drafts, changed)
    if (errors.isNotEmpty()) return NativeTablesRowRequestResult.Invalid(errors)
    val data = buildJsonObject {
        changed.forEach { column -> put(column.id.toString(), column.encodeDraft(drafts.getValue(column.id))) }
    }
    val values = buildMap {
        put(update.placeholders.single(), row.rowId.toString())
        put("data", data.toString())
        if (scope is NativeTablesRowScope.View) put("viewId", scope.id.toString())
    }
    return ready(update.action, values)
}

internal fun NativeTablesRowWritePlan.deleteRequest(row: NativeTablesRowCells): NativeTablesRowRequestResult {
    val delete = delete ?: return NativeTablesRowRequestResult.Unavailable
    val values = when (scope) {
        is NativeTablesRowScope.Table -> mapOf(delete.placeholders.single() to row.rowId.toString())
        // Route order is /views/{view}/rows/{row}; bind by position, never by guessing names.
        is NativeTablesRowScope.View -> mapOf(
            delete.placeholders[0] to scope.id.toString(),
            delete.placeholders[1] to row.rowId.toString(),
        )
    }
    return ready(delete.action, values)
}

private fun validate(drafts: Map<Long, String>, columns: List<NativeTablesColumn>): Map<Long, String> =
    columns.mapNotNull { column -> column.validateDraft(drafts[column.id].orEmpty())?.let { column.id to it } }.toMap()

private fun ready(action: ActionSpec, values: Map<String, String>) =
    NativeTablesRowRequestResult.Ready(NativeActionRequest.Submit(action, values, confirmed = true))

private fun String.isTablesDate(): Boolean {
    if (length != 10 || this[4] != '-' || this[7] != '-') return false
    val year = substring(0, 4).toIntOrNull() ?: return false
    val month = substring(5, 7).toIntOrNull() ?: return false
    val day = substring(8, 10).toIntOrNull() ?: return false
    if (year < 1 || month !in 1..12) return false
    val leap = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
    val days = intArrayOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)[month - 1]
    return day in 1..days
}

private fun String.isTablesTime(): Boolean {
    if (length != 5 || this[2] != ':') return false
    val hour = substring(0, 2).toIntOrNull() ?: return false
    val minute = substring(3, 5).toIntOrNull() ?: return false
    return all { it.isDigit() || it == ':' } && hour in 0..23 && minute in 0..59
}

private fun String.isTablesDateTime(): Boolean =
    length == 16 && this[10] == ' ' && substring(0, 10).isTablesDate() && substring(11).isTablesTime()

private val TABLE_ROWS_ROUTE = reviewedRoute("/index.php/apps/tables/api/1/tables/{}/rows")
private val VIEW_ROWS_ROUTE = reviewedRoute("/index.php/apps/tables/api/1/views/{}/rows")
private val ROW_ROUTE = reviewedRoute("/index.php/apps/tables/api/1/rows/{}")
private val VIEW_ROW_ROUTE = reviewedRoute("/index.php/apps/tables/api/1/views/{}/rows/{}")
private const val MAX_SELECTION_OPTIONS = 500

/**
 * Inline cell editing for table surfaces. Tables declares its row `data` body as an object of
 * objects, so the generic row-object reconstruction would send a `{columnId, value}` wrapper that
 * the column parser stores as the cell value. Tables rows are edited through the reviewed row
 * editor instead, which encodes each value for its column type.
 */
internal fun nativeInlineCellEditPlan(
    schema: NativeAppSchema,
    resource: dev.obiente.nextcloudnative.nativeui.model.ResourceSpec,
    projection: NativeTableProjection,
    record: NativeRecord,
    field: dev.obiente.nextcloudnative.nativeui.model.FieldSpec,
): NativeCellEditPlan? =
    if (projection.composite && schema.app.id == "tables") null
    else nativeCellEditPlan(schema, resource, projection, record, field)
