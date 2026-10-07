package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NativeTableRecordDetailTest {
    private fun field(id: String, kind: FieldKind = FieldKind.string) = FieldSpec(id, id, kind, false, true)
    private val rows = ResourceSpec("rows", "Rows", Confidence.verified,
        listOf(field("id"), field("tableId"), field("data", FieldKind.objectValue)))
    private val columns = ResourceSpec("columns", "Columns", Confidence.verified,
        listOf("id", "tableId", "title", "type", "orderWeight").map { field(it) })
    private val composite = CompositeDataGridSpec("tables", "columns", "rows", "columns.read", "rows.read",
        "id", null, "title", "type", "orderWeight", "data")
    private val schema = NativeAppSchema("0.1", AppIdentity("synthetic", "Inventory", "1"), Confidence.verified,
        resources = listOf(rows, columns), views = listOf(ViewSpec("grid", "Table", "rows", NativeComponent.dataTable,
            "rows.read", Confidence.verified, compositeDataGrid = composite)),
        relationships = listOf(
            ResourceRelationshipSpec("tables", "rows", "id", "tableId", Confidence.verified),
            ResourceRelationshipSpec("tables", "columns", "id", "tableId", Confidence.verified)))
    private val row = NativeRecord("1", mapOf("id" to "1", "tableId" to "1", "data" to
        """[{"columnId":1,"value":"Synthetic sample"}]"""))
    private val column = NativeRecord("1", mapOf("id" to "1", "tableId" to "1", "title" to "Item", "type" to "text"))

    @Test
    fun joinedRowDetailShowsColumnTitleAndCellValueWithoutTransportMetadata() {
        val fields = nativeTableRecordDetail(schema, rows, row, NativeDatasetContext(relatedRecords = mapOf("columns" to listOf(column))))!!
        assertEquals("Synthetic sample", fields.title)
        assertEquals(listOf("Item"), fields.fields.map { it.formatted.label })
        assertEquals(listOf("Synthetic sample"), fields.fields.map { it.formatted.displayValue })
        assertEquals("1", row.values["id"])
    }

    @Test
    fun missingAmbiguousOrOtherParentColumnsCannotRelabelTheRow() {
        assertNull(nativeTableRecordDetail(schema, rows, row, NativeDatasetContext()))
        for (values in listOf(listOf(column.copy(values = column.values + ("tableId" to "2"))), listOf(column, column))) {
            assertNull(nativeTableRecordDetail(schema, rows, row, NativeDatasetContext(relatedRecords = mapOf("columns" to values))))
        }
        assertNull(nativeTableRecordDetail(schema.copy(relationships = emptyList()), rows, row,
            NativeDatasetContext(relatedRecords = mapOf("columns" to listOf(column)))))
    }
    @Test
    fun identityNormalizationAndRelationshipEvidenceFailClosed() {
        val context = NativeDatasetContext(relatedRecords = mapOf("columns" to listOf(column)))
        for (relationships in listOf(
            schema.relationships.drop(1),
            schema.relationships.map { it.copy(confidence = Confidence.low) },
            schema.relationships.map { if (it.childResourceId == "columns") it.copy(parentFieldId = "uuid") else it },
        )) assertNull(nativeTableRecordDetail(schema.copy(relationships = relationships), rows, row,
            context.copy(parentResourceId = "tables", parentRecord = NativeRecord("1", mapOf("id" to "1")))))
        for (identity in listOf(" 1 ", " ")) {
            val duplicate = column.copy(values = column.values + ("id" to identity))
            assertNull(nativeTableRecordDetail(schema, rows, row,
                context.copy(relatedRecords = mapOf("columns" to listOf(column, duplicate)))))
        }
    }

    @Test
    fun sparseDetailEnvelopeUsesObservedParentAndCellsWithoutPromotingMutationFields() {
        val observed = row.copy(values = emptyMap(), displayValues = mapOf("tableId" to "1"),
            actionSafeIdentity = false, structuredValues = mapOf("data" to NativeStructuredValue.ListValue(listOf(
                NativeStructuredValue.ObjectValue(listOf(
                    NativeStructuredEntry("columnId", "Column", NativeStructuredValue.Scalar("1", NativeStructuredScalarKind.number)),
                    NativeStructuredEntry("value", "Value", NativeStructuredValue.Scalar("Synthetic sample", NativeStructuredScalarKind.string)),
                ))))))
        val competing = schema.copy(views = schema.views + schema.views.single().copy(id = "view-grid",
            compositeDataGrid = composite.copy(parentResourceId = "views")), relationships = schema.relationships + listOf(
                ResourceRelationshipSpec("views", "rows", "id", "viewId", Confidence.high),
                ResourceRelationshipSpec("views", "columns", "id", "viewId", Confidence.high)))
        val fields = nativeTableRecordDetail(competing, rows, observed,
            NativeDatasetContext(relatedRecords = mapOf("columns" to listOf(column))))!!
        assertEquals("Synthetic sample", fields.title)
        assertEquals(listOf("Item"), fields.fields.map { it.formatted.label })
        assertEquals(listOf("Synthetic sample"), fields.fields.map { it.formatted.displayValue })
        assertEquals(emptyMap(), observed.values)
        assertNull(nativeTableRecordScope(competing, "rows", observed.copy(displayValues =
            observed.displayValues + ("viewId" to "8"))))
        assertNull(nativeTableRecordScope(competing, "rows", observed.copy(values = mapOf("tableId" to "2"))))
    }
    @Test
    fun truncatedObservedCellsNeverBecomeApparentlyCompleteDetails() {
        val scalar = NativeStructuredValue.Scalar("Synthetic sample", NativeStructuredScalarKind.string)
        val entry = NativeStructuredEntry("1", "Item", scalar)
        val truncated = NativeStructuredValue.ObjectValue(listOf(entry), omittedEntries = 1)
        val examples = listOf<NativeStructuredValue>(truncated,
            NativeStructuredValue.ListValue(listOf(truncated)),
            NativeStructuredValue.ObjectValue(listOf(entry.copy(value = truncated))),
            NativeStructuredValue.ListValue(emptyList(), omittedItems = 1))
        examples.forEach { cells ->
            val observed = row.copy(values = mapOf("tableId" to "1"), structuredValues = mapOf("data" to cells))
            assertNull(nativeTableRecordDetail(schema, rows, observed,
                NativeDatasetContext(relatedRecords = mapOf("columns" to listOf(column)))))
        }
    }
}
