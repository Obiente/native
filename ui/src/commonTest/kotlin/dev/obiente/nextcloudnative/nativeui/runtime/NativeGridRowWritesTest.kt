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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Grid row writes for an app the client has never seen ("sheetsy" 77.0.0). Every expectation is
 * derived from the synthetic contract and data below, never from an app identity or version.
 */
class NativeGridRowWritesTest {
    @Test
    fun anUnknownAppWithARowShapedContractGetsCreateEditAndDelete() {
        val plan = assertNotNull(plan())
        assertEquals("rows.create", plan.create?.action?.id)
        assertEquals("rows.update", plan.update?.write?.action?.id)
        assertEquals("rows.delete", plan.delete?.write?.action?.id)
        assertEquals("cells", plan.cellField)
        val other = assertNotNull(plan(schema = schema().copy(app = AppIdentity("gridworks", "Gridworks", "0.0.1"))))
        assertEquals(plan.create?.action?.id, other.create?.action?.id, "The app identity changes nothing.")
    }

    @Test
    fun createEncodesEachColumnKindUnderItsColumnIdAndBindsTheParent() {
        val plan = assertNotNull(plan())
        val drafts = mapOf(
            "1" to "Synthetic item", "2" to "12.5", "3" to "11", "4" to "11,10", "5" to "true",
            "6" to "2026-01-02", "7" to "https://files.example.test/a", "9" to "3", "10" to "08:30",
        )
        val request = assertIs<NativeGridRowRequestResult.Ready>(plan.createRequest(drafts)).request
        assertEquals("rows.create", request.action.id)
        assertEquals("5", request.values["sheetId"])
        val data = Json.parseToJsonElement(request.values.getValue("cells")).jsonObject
        assertEquals(JsonPrimitive("Synthetic item"), data["1"])
        assertEquals(JsonPrimitive(12.5), data["2"])
        assertEquals(JsonPrimitive(11L), data["3"], "A choice sends its option identity in the option's own JSON type.")
        assertEquals(JsonArray(listOf(JsonPrimitive(10L), JsonPrimitive(11L))), data["4"])
        assertEquals(JsonPrimitive("true"), data["5"], "Booleans follow the representation stored cells use.")
        assertEquals(JsonPrimitive("2026-01-02"), data["6"])
        assertEquals(JsonPrimitive(3), data["9"])
        assertEquals(JsonPrimitive("08:30"), data["10"])
        assertTrue("7" !in data, "A link column has no declared encoding and is never sent.")
        assertTrue("8" !in data, "A people column is never encoded.")
    }

    @Test
    fun patchUpdateSendsOnlyChangedCellsAndReportsNoChanges() {
        val plan = assertNotNull(plan())
        val row = assertNotNull(plan.row(row()))
        val unchanged = plan.editableColumns.associate { it.key to it.draftFrom(row.cells[it.key]) }
        assertEquals(NativeGridRowRequestResult.NoChanges, plan.updateRequest(row, unchanged))
        val request = assertIs<NativeGridRowRequestResult.Ready>(plan.updateRequest(row, unchanged + ("2" to "40"))).request
        assertEquals("rows.update", request.action.id)
        assertEquals(mapOf("sheetId" to "5", "rowId" to "9"), request.values - "cells")
        assertEquals<Map<String, JsonElement>>(mapOf("2" to JsonPrimitive(40.0)),
            Json.parseToJsonElement(request.values.getValue("cells")).jsonObject)
    }

    @Test
    fun replacementUpdateNeedsADeclaredSameRouteReadAndSendsEveryCell() {
        assertNull(assertNotNull(plan(schema = schema(updateMethod = HttpMethod.PUT))).update,
            "A replacement without a contract read to verify it is not offered.")
        val plan = assertNotNull(plan(schema = schema(updateMethod = HttpMethod.PUT, recovery = "rows.read")))
        val row = assertNotNull(plan.row(row()))
        val request = assertIs<NativeGridRowRequestResult.Ready>(plan.updateRequest(row, mapOf("2" to "40"))).request
        val cells = Json.parseToJsonElement(request.values.getValue("cells")).jsonObject
        assertEquals(plan.editableColumns.map { it.key }.toSet(), cells.keys, "A replacement carries every editable cell.")
        assertEquals(JsonPrimitive("Synthetic item"), cells["1"])
        val linked = assertNotNull(plan.row(row(ROW_DATA.dropLast(1) + """,{"columnId":7,"value":"{\"value\":\"https://x.example.test\"}"}]""")))
        assertIs<NativeGridRowRequestResult.Unavailable>(plan.updateRequest(linked, mapOf("2" to "40")),
            "A replacement would clear the link value it cannot encode.")
    }

    @Test
    fun globalItemRoutesBindTheParentThroughTheContractOrTheRowItself() {
        val schema = schema(itemBase = "/apps/sheetsy/api/rows/{rowId}", updateBody = listOf("cells", "sheetId"))
        val plan = assertNotNull(plan(schema = schema))
        assertTrue(plan.update?.global == true)
        assertEquals("sheetId", plan.update?.parentInput)
        val row = assertNotNull(plan.row(row()))
        val update = assertIs<NativeGridRowRequestResult.Ready>(plan.updateRequest(row, mapOf("1" to "y"))).request
        assertEquals("5", update.values["sheetId"])
        assertEquals("9", update.values["rowId"])
        val delete = assertIs<NativeGridRowRequestResult.Ready>(plan.deleteRequest(row)).request
        assertEquals(mapOf("rowId" to "9"), delete.values, "The row names its sheet, so the global delete is bound.")
        val foreign = assertNotNull(plan.row(row().copy(values = row().values - "sheetId")))
        assertIs<NativeGridRowRequestResult.Unavailable>(plan.deleteRequest(foreign))
    }

    @Test
    fun aBareIdParentPlaceholderIsNamedAfterItsContainerNotTheChildIdentity() {
        val list = "/apps/sheetsy/api/sheets/{id}/rows"
        val plan = assertNotNull(plan(schema = schema(list = list, itemBase = "/apps/sheetsy/api/rows/{rowId}")),
            "Columns name their sheet as sheetId; their own id is not a parent reference.")
        assertEquals("sheetid", plan.route.parentReferenceKey)
        val delete = assertIs<NativeGridRowRequestResult.Ready>(plan.deleteRequest(assertNotNull(plan.row(row())))).request
        assertEquals(mapOf("rowId" to "9"), delete.values)
        assertNull(plan(schema = schema(list = list), columns = columns() + column("20", "Foreign", "text", "line", sheetId = "77")))
    }

    @Test
    fun validationExplainsMandatoryRangeDateAndChoiceProblems() {
        val plan = assertNotNull(plan())
        val errors = assertIs<NativeGridRowRequestResult.Invalid>(
            plan.createRequest(mapOf("1" to "", "2" to "500", "3" to "99", "6" to "2026-02-30", "10" to "25:00")),
        ).fieldErrors
        assertEquals(setOf("1", "2", "3", "6", "10"), errors.keys)
        assertEquals("Item is required.", errors["1"], "A mandatory column blocks submit while it is empty.")
    }

    @Test
    fun noPlanWithoutTrustedProvenanceKnownPermissionsOrScopedColumns() {
        assertNull(plan(schema = schema(evidence = EvidenceSource.networkObservation)))
        assertNull(plan(columns = columns() + column("20", "Foreign", "text", "line", sheetId = "77")))
        assertNull(plan(parent = NativeRecord("5", mapOf("id" to "5", "isShared" to "false"), actionSafeIdentity = false)))
        assertNull(plan(parent = NativeRecord("5", mapOf("id" to "5"))), "A parent that states no permission grants nothing.")
        assertNotNull(plan(parent = NativeRecord("5", mapOf("id" to "5", "owner" to "synthetic-user"))),
            "Owning the parent grants writes.")
    }

    @Test
    fun aContractWithoutACreateOperationOffersNoCreate() {
        val schema = schema().let { it.copy(actions = it.actions.filterNot { action -> action.id == "rows.create" }) }
        val plan = assertNotNull(plan(schema = schema))
        assertNull(plan.create)
        assertIs<NativeGridRowRequestResult.Unavailable>(plan.createRequest(mapOf("1" to "x")))
        assertNotNull(plan.update)
    }

    @Test
    fun sharedParentsGrantOnlyDeclaredPermissions() {
        val readOnly = NativeRecord("5", mapOf("id" to "5", "isShared" to "true",
            "onSharePermissions" to """{"read":true,"create":true,"update":false,"delete":false}"""))
        val plan = assertNotNull(plan(parent = readOnly))
        assertNotNull(plan.create)
        assertNull(plan.update)
        assertNull(plan.delete)
        assertNull(plan(parent = NativeRecord("5", mapOf("id" to "5", "isShared" to "true", "onSharePermissions" to "nope"))))
    }

    @Test
    fun malformedRowCellsAreNotEditable() {
        val plan = assertNotNull(plan())
        assertNull(plan.row(row("""[{"columnId":1,"value":"a"},{"columnId":1,"value":"b"}]""")))
        assertNull(plan.row(row("""[{"value":"a"}]""")))
        assertNull(plan.row(row("not json")))
        assertNull(plan.row(row().copy(actionSafeIdentity = false)))
    }

    @Test
    fun booleanAndDateTimeColumnsNeedAStoredExampleBeforeTheyAreEncoded() {
        val plan = assertNotNull(plan(rows = emptyList()))
        assertFalse(plan.columns.single { it.key == "5" }.editable)
        val dated = column("11", "Starts", "datetime", "")
        val withSample = assertNotNull(plan(columns = columns() + dated,
            rows = listOf(row(ROW_DATA.dropLast(1) + """,{"columnId":11,"value":"2026-01-02T08:30:00"}]"""))))
        val column = withSample.columns.single { it.key == "11" }
        assertTrue(column.editable)
        assertEquals(JsonPrimitive("2026-03-04T09:15"), column.encodeDraft("2026-03-04 09:15"))
    }

    @Test
    fun clearingOptionalValuesSendsTypedEmptyValues() {
        val columns = assertNotNull(plan()).columns.associateBy { it.key }
        assertEquals(JsonNull, columns.getValue("3").encodeDraft(""))
        assertEquals(JsonArray(emptyList()), columns.getValue("4").encodeDraft(""))
        assertEquals(JsonNull, columns.getValue("2").encodeDraft(""))
        assertEquals("", columns.getValue("3").draftFrom(JsonPrimitive("404")), "An unknown option is not preselected.")
    }

    @Test
    fun genericInlineCellEditingIsDisabledForEveryColumnDefinedGrid() {
        val schema = schema()
        val rows = schema.resource("rows")!!
        val projection = NativeTableProjection(rows, listOf(row()), composite = true)
        assertNull(nativeInlineCellEditPlan(schema, rows, projection, row(), rows.fields.first()))
    }

    @Test
    fun anUnconfirmedCreateOffersRefreshAndVerifyInsteadOfRetry(): Unit = runBlocking {
        val plan = assertNotNull(plan())
        val editing = NativeGridRowEditing()
        editing.open(plan, NativeGridRowEditorTarget.Create)
        editing.update("1", "Synthetic item")
        var sent = 0
        var refreshes = 0
        val ambiguous = NativeActionExecutor {
            sent++
            NativeActionExecutionResult.Failure("Timed out.", NativeActionFailureOutcome.Unknown)
        }
        editing.submit(plan, ambiguous) { refreshes++ }
        val status = assertIs<NativeEditorWriteStatus.Unconfirmed>(editing.session.status)
        assertTrue(status.awaitingRefresh)
        assertTrue("check" in status.message, "The person is asked to check the refreshed list.")
        assertEquals(1, refreshes, "The authoritative list is re-read to verify the outcome.")
        assertFalse(editing.session.canSubmit)
        editing.submit(plan, ambiguous) { refreshes++ }
        assertEquals(1, sent, "A blind second create never reaches the server.")
        assertNotNull(editing.target, "The draft stays open for checking.")
    }

    private fun plan(
        schema: NativeAppSchema = schema(),
        parent: NativeRecord = NativeRecord("5", mapOf("id" to "5", "isShared" to "false")),
        columns: List<NativeRecord> = columns(),
        rows: List<NativeRecord> = listOf(row()),
    ): NativeGridRowWritePlan? {
        val view = ViewSpec("sheets.table", "Rows", "rows", NativeComponent.dataTable, "rows.list",
            Confidence.high, compositeDataGrid = CompositeDataGridSpec("sheets", "columns", "rows",
                "columns.list", "rows.list", "id", null, "title", "type", null, "cells"))
        val context = NativeDatasetContext(parentResourceId = "sheets", parentRecord = parent,
            currentUserId = "synthetic-user", relatedRecords = mapOf("columns" to columns))
        return nativeGridRowWritePlan(schema, view, context, rows)
    }

    private fun schema(
        evidence: EvidenceSource = EvidenceSource.verifiedAppPackage,
        updateMethod: HttpMethod = HttpMethod.PATCH,
        recovery: String? = null,
        list: String = LIST,
        itemBase: String = "$list/{rowId}",
        updateBody: List<String> = listOf("cells"),
    ) = NativeAppSchema("0.1", AppIdentity("sheetsy", "Sheetsy", "77.0.0"), Confidence.high,
        resources = listOf(ResourceSpec("rows", "Rows", Confidence.high,
            listOf(FieldSpec("cells.item", "Item", FieldKind.string, false, true)))),
        actions = listOf(
            action("rows.list", HttpMethod.GET, list, evidence, ActionIntent.list, emptyList()),
            action("rows.read", HttpMethod.GET, itemBase, evidence, ActionIntent.read, emptyList()),
            action("rows.create", HttpMethod.POST, list, evidence, ActionIntent.create),
            action("rows.update", updateMethod, itemBase, evidence, ActionIntent.update, updateBody)
                .copy(resultRecoveryActionId = recovery),
            action("rows.delete", HttpMethod.DELETE, itemBase, evidence, ActionIntent.delete, emptyList()),
        ))

    private fun action(
        id: String, method: HttpMethod, path: String, evidence: EvidenceSource, intent: ActionIntent,
        body: List<String> = listOf("cells"),
    ): ActionSpec {
        val pathParameters = Regex("\\{([^}]+)}").findAll(path).map { it.groupValues[1] }.toList()
        val properties = body.joinToString(",") { name ->
            if (name == "cells") """"cells":{"type":"object","additionalProperties":{}}""" else """"$name":{"type":"integer"}"""
        }
        return ActionSpec(id, id, "rows", ApiBinding(method, path, id, pathParameters, pathParameters,
            bodyFieldNames = body, requiredBodyFieldNames = body.filter { it == "cells" },
            bodySchema = if (body.isEmpty()) null else Json.parseToJsonElement("""{"type":"object","properties":{$properties}}""")),
            intent, when (intent) {
                ActionIntent.list, ActionIntent.read -> ActionRisk.readOnly
                ActionIntent.delete -> ActionRisk.destructive
                else -> ActionRisk.mutating
            }, true, Confidence.high, evidence = listOf(Evidence(evidence, "synthetic")))
    }

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
        sheetId: String = "5", extra: Map<String, String> = emptyMap(),
    ) = NativeRecord(id, mapOf("id" to id, "title" to title, "type" to type, "subtype" to subtype,
        "mandatory" to mandatory.toString(), "sheetId" to sheetId) + extra)

    private fun row(data: String = ROW_DATA) = NativeRecord("9", mapOf("id" to "9", "sheetId" to "5", "cells" to data))

    private companion object {
        const val LIST = "/apps/sheetsy/api/sheets/{sheetId}/rows"
        const val OPTIONS = """[{"id":10,"label":"Open"},{"id":11,"label":"Closed"}]"""
        const val ROW_DATA = """[{"columnId":1,"value":"Synthetic item"},{"columnId":2,"value":12},""" +
            """{"columnId":3,"value":"10"},{"columnId":4,"value":[11]},{"columnId":5,"value":"false"}]"""
    }
}
