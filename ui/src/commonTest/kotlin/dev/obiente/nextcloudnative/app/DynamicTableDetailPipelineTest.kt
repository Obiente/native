package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.*
import dev.obiente.nextcloudnative.nativeui.runtime.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.*

/** The Tables 2.3.1 shape includes dataByAlias on both view containers and row records. */
class DynamicTableDetailPipelineTest {
    private val proof = listOf(Provenance(ProvenanceKind.verifiedAppPackage, "synthetic", "contract"))
    private fun field(id: String, kind: FieldKind = FieldKind.string) =
        DynamicField(id, id, kind, false, true, true, false, confidence = Confidence.high)
    private fun resource(id: String, vararg fields: DynamicField) =
        DynamicResource(id, id, true, fields.toList(), confidence = Confidence.high, provenance = proof)
    private fun read(id: String, target: String, parent: String) = DynamicAction(id, id, target,
        ActionIntent.list, ActionRisk.readOnly, false,
        DynamicHttpBinding(HttpMethod.GET, "/api/$parent/{id}/$target", listOf(
            HttpParameter("id", true, JsonPrimitive("integer"), ParameterSource.resourceField))),
        confidence = Confidence.high, provenance = proof)
    private val columns = read("table.columns", "columns", "tables")
    private val rows = read("table.rows", "rows", "tables")
    private val views = read("table.views", "views", "tables")
    private val viewRows = read("view.rows", "rows", "views")
    private val viewColumns = read("view.columns", "columns", "views")
    private val detail = read("row.detail", "rows", "tables").copy(intent = ActionIntent.read,
        binding = DynamicHttpBinding(HttpMethod.GET, "/api/rows/{rowId}", listOf(
            HttpParameter("rowId", true, JsonPrimitive("integer"), ParameterSource.resourceField))),
        responseFieldIds = listOf("columnId", "value"))
    private fun link(parent: String, action: DynamicAction) = DynamicLink("$parent.${action.id}", action.label,
        parent, "id", DynamicLinkTarget.Action(action.id), Confidence.high, proof)
    private val descriptor = DynamicAppDescriptor(DYNAMIC_APP_DESCRIPTOR_VERSION,
        AppIdentity("synthetic", "Synthetic tables", "1"), EndpointPolicy("https://fixture.invalid", listOf("/api")),
        resources = listOf(resource("tables", field("id"), field("title")),
            resource("views", field("id"), field("tableId"), field("title"), field("dataByAlias", FieldKind.objectValue)),
            resource("columns", field("id"), field("tableId"), field("viewId"), field("title"),
                field("technicalName"), field("type"), field("orderWeight")),
            resource("rows", field("id"), field("tableId"), field("viewId"), field("columnId"),
                field("value", FieldKind.objectValue), field("data", FieldKind.objectValue),
                field("dataByAlias", FieldKind.objectValue))),
        layouts = listOf(DynamicLayout("rows.detail", "Row", "rows", LayoutKind.detail,
            sourceActionId = detail.id, confidence = Confidence.high, provenance = proof)),
        links = listOf(link("tables", columns), link("tables", rows), link("tables", views),
            link("views", viewRows), link("views", viewColumns)),
        actions = listOf(columns, rows, views, viewRows, viewColumns, detail))

    @Test
    fun mappedNestedViewContainerLoadsRelatedColumnsAndRendersSparseRowAsNamedCells() = runBlocking {
        val schema = descriptor.toNativeAppSchema()
        assertEquals(setOf("tables", "views"), schema.views.mapNotNull { it.compositeDataGrid?.parentResourceId }.toSet())
        val row = parseDynamicRecords(detail, response("""{"id":9,"tableId":2,"createdBy":"synthetic",
            "data":[{"columnId":1,"value":"Synthetic sample"}],"dataByAlias":{"item":"Synthetic sample"}}"""),
            detail.responseFieldIds.toSet()).single()
        assertTrue(row.values.isEmpty())
        assertFalse(row.actionSafeIdentity)
        val view = schema.views.single { it.id == "rows.detail" }
        val plan = assertNotNull(dynamicTableColumnReadPlan(descriptor, schema, view, row))
        assertEquals(columns.id, plan.actionId)
        assertEquals(mapOf("id" to "2"), plan.values)
        val column = parseDynamicRecords(columns, response("""[{"id":1,"tableId":2,
            "title":"Item","technicalName":"item","type":"text","orderWeight":0}]"""),
            setOf("id", "tableId", "title", "technicalName", "type", "orderWeight"))
        val outcome = loadDynamicTableDetailColumns(DynamicRecordLoadOutcome(listOf(row), null), plan) {
            DynamicRecordLoadOutcome(column, null)
        }
        val fields = assertNotNull(nativeTableRecordDetail(schema, assertNotNull(schema.resource("rows")),
            outcome.records.single(), NativeDatasetContext(relatedRecords = outcome.relatedRecords)))
        assertEquals("Synthetic sample", fields.title)
        assertEquals(listOf("Item"), fields.fields.map { it.formatted.label })
        assertEquals(listOf("Synthetic sample"), fields.fields.map { it.formatted.displayValue })
        assertFalse(outcome.records.single().actionSafeIdentity)
        assertTrue(outcome.records.single().values.isEmpty())
    }

    @Test
    fun unrelatedCellMapCollectionsRemainAmbiguousWithoutDeclaredNestedRelationship() {
        val ambiguous = descriptor.copy(links = descriptor.links.filterNot { it.resourceId == "views" })
        assertTrue(ambiguous.toNativeAppSchema().views.none { it.compositeDataGrid?.parentResourceId == "tables" })
    }

    private fun response(body: String) = NextcloudApiResponse(200, body.encodeToByteArray(), "application/json", null)
}