package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.ApiBinding
import dev.obiente.nextcloudnative.nativeui.model.AppIdentity
import dev.obiente.nextcloudnative.nativeui.model.CompositeDataGridSpec
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.Evidence
import dev.obiente.nextcloudnative.nativeui.model.EvidenceSource
import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.NativeComponent
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import dev.obiente.nextcloudnative.nativeui.model.ViewSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeTablesRowWritesTest {
    @Test
    fun createEncodesEachColumnTypeUnderItsColumnIdAndBindsTheTable() {
        val plan = assertNotNull(plan())
        assertEquals(NativeTablesRowScope.Table(5), plan.scope)
        val drafts = mapOf(
            1L to "Synthetic item", 2L to "12.5", 3L to "11", 4L to "11,10", 5L to "true",
            6L to "2026-01-02", 7L to "https://files.example.test/a", 9L to "3", 10L to "08:30",
        )
        val request = assertIs<NativeTablesRowRequestResult.Ready>(plan.createRequest(drafts)).request
        assertEquals("api1-create-row-in-table", request.action.id)
        assertEquals("5", request.values["id"])
        val data = Json.parseToJsonElement(request.values.getValue("data")).jsonObject
        assertEquals(JsonPrimitive("Synthetic item"), data["1"])
        assertEquals(JsonPrimitive(12.5), data["2"])
        assertEquals(JsonPrimitive(11L), data["3"])
        assertEquals(JsonArray(listOf(JsonPrimitive(10L), JsonPrimitive(11L))), data["4"])
        assertEquals(JsonPrimitive("true"), data["5"])
        assertEquals(JsonPrimitive("2026-01-02"), data["6"])
        val link = Json.parseToJsonElement(data.getValue("7").jsonPrimitive.content).jsonObject
        assertEquals("https://files.example.test/a", link["value"]?.jsonPrimitive?.content)
        assertEquals(JsonPrimitive(3), data["9"])
        assertEquals(JsonPrimitive("08:30"), data["10"])
        assertTrue("8" !in data, "A user and group column is never encoded.")
    }

    @Test
    fun updateSendsOnlyChangedCellsAndReportsNoChanges() {
        val plan = assertNotNull(plan())
        val row = assertNotNull(nativeTablesRowCells(row()))
        val unchanged = plan.editableColumns.associate { it.id to it.draftFrom(row.cells[it.id]) }
        assertEquals(NativeTablesRowRequestResult.NoChanges, plan.updateRequest(row, unchanged))
        val request = assertIs<NativeTablesRowRequestResult.Ready>(
            plan.updateRequest(row, unchanged + (2L to "40")),
        ).request
        assertEquals("api1-update-row", request.action.id)
        assertEquals("9", request.values["rowId"])
        assertEquals<Map<String, JsonElement>>(
            mapOf("2" to JsonPrimitive(40.0)),
            Json.parseToJsonElement(request.values.getValue("data")).jsonObject,
        )
        assertNull(request.values["viewId"])
    }

    @Test
    fun viewScopeCreatesInTheViewPassesViewIdAndBindsDeleteByRoutePosition() {
        val plan = assertNotNull(plan(parentResource = "views"))
        assertEquals(NativeTablesRowScope.View(5), plan.scope)
        val row = assertNotNull(nativeTablesRowCells(row()))
        val create = assertIs<NativeTablesRowRequestResult.Ready>(plan.createRequest(mapOf(1L to "x"))).request
        assertEquals("api1-create-row-in-view", create.action.id)
        assertEquals("5", create.values["viewId"])
        val update = assertIs<NativeTablesRowRequestResult.Ready>(plan.updateRequest(row, mapOf(1L to "y"))).request
        assertEquals("5", update.values["viewId"])
        val delete = assertIs<NativeTablesRowRequestResult.Ready>(plan.deleteRequest(row)).request
        assertEquals("api1-delete-row-by-view", delete.action.id)
        assertEquals(mapOf("viewId" to "5", "rowId" to "9"), delete.values)
    }

    @Test
    fun validationExplainsMandatoryRangeDateAndChoiceProblems() {
        val plan = assertNotNull(plan())
        val errors = assertIs<NativeTablesRowRequestResult.Invalid>(
            plan.createRequest(mapOf(1L to "", 2L to "500", 3L to "99", 6L to "2026-02-30", 10L to "25:00")),
        ).fieldErrors
        assertEquals(setOf(1L, 2L, 3L, 6L, 10L), errors.keys)
        assertEquals("Item is required.", errors[1])
    }

    @Test
    fun noPlanWithoutTrustedProvenanceSupportedVersionOrScopedColumns() {
        assertNull(plan(evidence = EvidenceSource.networkObservation))
        assertNull(plan(version = "3.0.0"))
        assertNull(plan(appId = "synthetic"))
        assertNull(plan(columns = columns() + column("20", "Foreign", "text", "line", tableId = "77")))
        assertNull(plan(parent = NativeRecord("5", mapOf("id" to "5", "isShared" to "false"), actionSafeIdentity = false)))
        assertNull(plan(parent = NativeRecord("5", mapOf("id" to "5"))), "Unknown share state grants nothing.")
    }

    @Test
    fun sharedTablesGrantOnlyDeclaredPermissions() {
        val readOnly = NativeRecord("5", mapOf("id" to "5", "isShared" to "true",
            "onSharePermissions" to """{"read":true,"create":true,"update":false,"delete":false}"""))
        val plan = assertNotNull(plan(parent = readOnly))
        assertNotNull(plan.create)
        assertNull(plan.update)
        assertNull(plan.delete)
        val malformed = NativeRecord("5", mapOf("id" to "5", "isShared" to "true", "onSharePermissions" to "nope"))
        assertNull(plan(parent = malformed))
    }

    @Test
    fun malformedRowCellsAreNotEditable() {
        assertNull(nativeTablesRowCells(row(data = """[{"columnId":1,"value":"a"},{"columnId":1,"value":"b"}]""")))
        assertNull(nativeTablesRowCells(row(data = """[{"value":"a"}]""")))
        assertNull(nativeTablesRowCells(row(data = "not json")))
        assertNull(nativeTablesRowCells(row().copy(actionSafeIdentity = false)))
    }

    @Test
    fun genericInlineCellEditingIsDisabledForTablesGrids() {
        val schema = schema()
        val rows = schema.resource("rows")!!
        val projection = NativeTableProjection(rows, listOf(row()), composite = true)
        assertNull(nativeInlineCellEditPlan(schema, rows, projection, row(), rows.fields.first()))
    }

    @Test
    fun clearingOptionalValuesUsesTheParserEmptyForms() {
        val columns = columns().associateBy { parseNativeTablesColumn(it)!!.id }.mapValues { parseNativeTablesColumn(it.value)!! }
        assertEquals(JsonNull, columns.getValue(3).encodeDraft(""))
        assertEquals(JsonArray(emptyList()), columns.getValue(4).encodeDraft(""))
        assertEquals(JsonPrimitive(""), columns.getValue(2).encodeDraft(""))
        assertEquals("", columns.getValue(3).draftFrom(JsonPrimitive("404")), "An unknown option is not preselected.")
    }

    private fun plan(
        parentResource: String = "tables",
        parent: NativeRecord = NativeRecord("5", mapOf("id" to "5", "isShared" to "false")),
        columns: List<NativeRecord> = columns(),
        evidence: EvidenceSource = EvidenceSource.verifiedAppPackage,
        version: String = "2.4.0",
        appId: String = "tables",
    ): NativeTablesRowWritePlan? {
        val schema = schema(evidence, version, appId)
        val view = ViewSpec("$parentResource.table", "Rows", "rows", NativeComponent.dataTable, "rows.list",
            Confidence.high, compositeDataGrid = CompositeDataGridSpec(parentResource, "columns", "rows",
                "columns.list", "rows.list", "id", "technicalName", "title", "type", "orderWeight", "dataByAlias"))
        val context = NativeDatasetContext(parentResourceId = parentResource, parentRecord = parent,
            relatedRecords = mapOf("columns" to columns))
        return nativeTablesRowWritePlan(schema, view, context)
    }

    private fun schema(
        evidence: EvidenceSource = EvidenceSource.verifiedAppPackage,
        version: String = "2.4.0",
        appId: String = "tables",
    ) = NativeAppSchema("0.1", AppIdentity(appId, "Tables", version), Confidence.high,
        resources = listOf(ResourceSpec("rows", "Rows", Confidence.high,
            listOf(FieldSpec("dataByAlias.item", "Item", FieldKind.string, false, true)))),
        actions = listOf(
            action("api1-create-row-in-table", HttpMethod.POST, "/index.php/apps/tables/api/1/tables/{id}/rows", listOf("id"), evidence, ActionIntent.create),
            action("api1-create-row-in-view", HttpMethod.POST, "/index.php/apps/tables/api/1/views/{viewId}/rows", listOf("viewId"), evidence, ActionIntent.create),
            action("api1-update-row", HttpMethod.PUT, "/index.php/apps/tables/api/1/rows/{rowId}", listOf("rowId"), evidence, ActionIntent.update, listOf("data", "viewId")),
            action("api1-delete-row", HttpMethod.DELETE, "/index.php/apps/tables/api/1/rows/{rowId}", listOf("rowId"), evidence, ActionIntent.delete, emptyList()),
            action("api1-delete-row-by-view", HttpMethod.DELETE, "/index.php/apps/tables/api/1/views/{viewId}/rows/{rowId}", listOf("viewId", "rowId"), evidence, ActionIntent.delete, emptyList()),
        ))

    private fun action(
        id: String, method: HttpMethod, path: String, pathParameters: List<String>, evidence: EvidenceSource,
        intent: ActionIntent, body: List<String> = listOf("data"),
    ) = ActionSpec(id, id, "rows", ApiBinding(method, path, id, pathParameters, pathParameters,
        bodyFieldNames = body, requiredBodyFieldNames = body.filter { it == "data" }), intent,
        if (intent == ActionIntent.delete) ActionRisk.destructive else ActionRisk.mutating, true, Confidence.high,
        evidence = listOf(Evidence(evidence, "synthetic")))

    private fun columns() = listOf(
        column("1", "Item", "text", "line", mandatory = true),
        column("2", "Price", "number", "", extra = mapOf("numberMin" to "0", "numberMax" to "100")),
        column("3", "Status", "selection", "", extra = mapOf("selectionOptions" to OPTIONS)),
        column("4", "Tags", "selection", "multi", extra = mapOf("selectionOptions" to OPTIONS)),
        column("5", "Done", "selection", "check"),
        column("6", "Due", "datetime", "date"),
        column("7", "Link", "text", "link"),
        column("8", "Owner", "usergroup", ""),
        column("9", "Rating", "number", "stars"),
        column("10", "Start", "datetime", "time"),
    )

    private fun column(
        id: String, title: String, type: String, subtype: String, mandatory: Boolean = false,
        tableId: String = "5", extra: Map<String, String> = emptyMap(),
    ) = NativeRecord(id, mapOf("id" to id, "title" to title, "type" to type, "subtype" to subtype,
        "mandatory" to mandatory.toString(), "tableId" to tableId) + extra)

    private fun row(data: String = ROW_DATA) = NativeRecord("9", mapOf("id" to "9", "tableId" to "5", "data" to data))

    private companion object {
        const val OPTIONS = """[{"id":10,"label":"Open"},{"id":11,"label":"Closed"}]"""
        const val ROW_DATA = """[{"columnId":1,"value":"Synthetic item"},{"columnId":2,"value":12},""" +
            """{"columnId":3,"value":"10"},{"columnId":4,"value":[11]},{"columnId":5,"value":"false"},""" +
            """{"columnId":8,"value":[{"id":"someone","type":0}]}]"""
    }
}
