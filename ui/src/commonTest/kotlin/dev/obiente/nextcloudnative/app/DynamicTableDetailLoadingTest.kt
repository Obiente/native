package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.*
import dev.obiente.nextcloudnative.nativeui.runtime.NativeRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.*

class DynamicTableDetailLoadingTest {
    private val app = AppIdentity("synthetic", "Synthetic tables", "1")
    private val composite = CompositeDataGridSpec("tables", "columns", "rows", "columns.read", "rows.read",
        "id", null, "title", "type", "orderWeight", "data")
    private val detail = ViewSpec("row-detail", "Row", "rows", NativeComponent.detail, "row.read", Confidence.high)
    private val schema = NativeAppSchema("0.1", app, Confidence.high,
        views = listOf(detail, ViewSpec("grid", "Table", "rows", NativeComponent.dataTable,
            "rows.read", Confidence.high, compositeDataGrid = composite)),
        relationships = listOf(ResourceRelationshipSpec("tables", "rows", "id", "tableId", Confidence.high),
            ResourceRelationshipSpec("tables", "columns", "id", "tableId", Confidence.high)))
    private val columnAction = DynamicAction("columns.read", "Columns", "columns", ActionIntent.list,
        ActionRisk.readOnly, false, DynamicHttpBinding(HttpMethod.GET, "/apps/example/api/tables/{tableId}/columns",
            pathParameters = listOf(HttpParameter("tableId", true, JsonPrimitive("string"), ParameterSource.resourceField))),
        confidence = Confidence.high, provenance = listOf(Provenance(ProvenanceKind.advertisedOpenApi, "synthetic", "contract")))
    private val descriptor = DynamicAppDescriptor("1.0", app,
        EndpointPolicy("https://fixture.invalid", listOf("/apps/example/api")), actions = listOf(columnAction))
    private val row = NativeRecord("7", mapOf("id" to "7", "tableId" to "2", "data" to "[]"))

    @Test
    fun bindsDeclaredParentIdentityInsteadOfSelectedRowIdentity() {
        val plan = assertNotNull(dynamicTableColumnReadPlan(descriptor, schema, detail, row))
        assertEquals(mapOf("tableId" to "2"), plan.values)
        assertEquals("columns", plan.resourceId)
        assertNull(dynamicTableColumnReadPlan(descriptor, schema, detail, row.copy(values = mapOf("id" to "7"))))
        assertNull(dynamicTableColumnReadPlan(descriptor, schema.copy(relationships = emptyList()), detail, row))
        assertNull(dynamicTableColumnReadPlan(descriptor, schema.copy(relationships = schema.relationships.map {
            if (it.childResourceId == "columns") it.copy(parentFieldId = "otherId") else it
        }), detail, row))
    }

    @Test
    fun missingProvenanceUnsafeRoutesAndUnresolvedParametersCannotLoadColumns() {
        listOf(columnAction.copy(provenance = emptyList()), columnAction.copy(confidence = Confidence.low),
            columnAction.copy(binding = columnAction.binding.copy(method = HttpMethod.POST)),
            columnAction.copy(binding = columnAction.binding.copy(path = "/unapproved/{tableId}")),
            columnAction.copy(binding = columnAction.binding.copy(pathParameters = listOf(
                HttpParameter("id", true, JsonPrimitive("string"), ParameterSource.resourceField)))))
            .forEach { action ->
                assertNull(dynamicTableColumnReadPlan(descriptor.copy(actions = listOf(action)), schema, detail, row))
            }
        assertNull(dynamicTableColumnReadPlan(descriptor, schema, detail, row.copy(actionBindingProvenanceValid = false)))
    }

    @Test
    fun verifiedColumnsJoinAndForeignPartialOrFailedColumnsPreserveDetailWithoutReusingStaleDefinitions() = runBlocking {
        val plan = assertNotNull(dynamicTableColumnReadPlan(descriptor, schema, detail, row))
        val primary = DynamicRecordLoadOutcome(listOf(row))
        val column = NativeRecord("3", mapOf("id" to "3", "tableId" to "2", "title" to "Item"))
        val success = loadDynamicTableDetailColumns(primary, plan) { DynamicRecordLoadOutcome(listOf(column)) }
        assertEquals(listOf(column), success.relatedRecords["columns"])
        listOf(DynamicRecordLoadOutcome(listOf(column.copy(values = column.values + ("tableId" to "9")))),
            DynamicRecordLoadOutcome(listOf(column), "partial")).forEach { response ->
            val result = loadDynamicTableDetailColumns(primary, plan) { response }
            assertEquals(listOf(row), result.records)
            assertEquals(emptyList(), result.relatedRecords["columns"])
            assertNotNull(result.relatedFailureMessage)
            assertNull(result.partialFailureMessage)
        }
        val failed = loadDynamicTableDetailColumns(primary, plan) { error("synthetic failure") }
        assertEquals(listOf(row), failed.records)
        assertEquals(emptyList(), failed.relatedRecords["columns"])
        assertFailsWith<CancellationException> {
            loadDynamicTableDetailColumns(primary, plan) { throw CancellationException("synthetic cancellation") }
        }
        val absent = loadDynamicTableDetailColumns(primary, null) { error("No column request expected") }
        assertEquals(listOf(row), absent.records)
    }
    @Test
    fun sparseRowUsesVerifiedParentLinkToBindGenericIdWithoutConfusingItWithRowId() {
        val action = columnAction.copy(binding = columnAction.binding.copy(
            path = "/apps/example/api/tables/{id}/columns", pathParameters = listOf(
                HttpParameter("id", true, JsonPrimitive("integer"), ParameterSource.resourceField))))
        val link = DynamicLink("table.columns", "Columns", "tables", "id", DynamicLinkTarget.Action(action.id),
            Confidence.high, columnAction.provenance)
        val linked = descriptor.copy(actions = listOf(action), links = listOf(link))
        val rowRead = action.copy(id = "row.read", resourceId = "rows", intent = ActionIntent.read,
            responseFieldIds = listOf("columnId", "value"))
        val observed = parseDynamicRecords(rowRead, NextcloudApiResponse(200,
            """{"id":9,"tableId":2,"data":[{"columnId":1,"value":"Synthetic sample"}]}""".encodeToByteArray(),
            "application/json", null), declaredFieldIds = rowRead.responseFieldIds.toSet()).single()
        assertEquals("9", observed.id)
        assertFalse(observed.actionSafeIdentity)
        val plan = dynamicTableColumnReadPlan(linked, schema, detail, observed)!!
        assertEquals(mapOf("id" to "2"), plan.values)
        assertEquals("2", plan.parentValue)
        assertNull(dynamicTableColumnReadPlan(linked.copy(links = emptyList()), schema, detail, observed))
        assertNull(dynamicTableColumnReadPlan(linked.copy(links = listOf(link.copy(provenance = emptyList()))),
            schema, detail, observed))
        assertEquals(emptyMap(), observed.values)
    }
}
