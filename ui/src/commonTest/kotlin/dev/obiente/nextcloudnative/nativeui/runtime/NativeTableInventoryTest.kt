package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.*
import kotlin.test.*

class NativeTableInventoryTest {
    private val resource = ResourceSpec("tables", "Tables", Confidence.verified,
        listOf("title", "rowsCount", "columnsCount", "owner", "archived", "favorite", "id")
            .map { FieldSpec(it, it, FieldKind.string, false, true) })
    private val schema = NativeAppSchema("1", AppIdentity("tables", "Tables", "1"), Confidence.verified,
        resources = listOf(resource))

    @Test
    fun inventoryUsesDeclaredCountsAndKeepsSecondaryMetadataOutOfSummary() {
        val record = NativeRecord("table-1", mapOf("title" to "Inventory", "rowsCount" to "12",
            "columnsCount" to "1", "owner" to "Synthetic owner", "archived" to "false", "favorite" to "true"))
        val presentation = assertNotNull(nativeTableInventoryPresentation(schema, resource, record))
        assertEquals("12 rows | 1 column", presentation.counts)
        assertEquals(listOf("Favorite"), presentation.states)
        assertTrue(presentation.details.any { it.fieldId == "owner" })
        assertFalse(presentation.details.any { it.fieldId == "id" || it.fieldId == "rowsCount" })
        assertEquals("false", record.values["archived"])
    }

    @Test
    fun absentMalformedOrAmbiguousCountsAreNotInvented() {
        val record = NativeRecord("1", mapOf("title" to "Inventory", "rowsCount" to "-1", "columnsCount" to "many"))
        assertNull(nativeTableInventoryPresentation(schema, resource, record)!!.counts)
        assertNull(nativeTableInventoryPresentation(schema, resource.copy(fields = resource.fields.filterNot { it.id == "rowsCount" }),
            record.copy(values = record.values + ("rowsCount" to "9")))!!.counts)
        assertNull(nativeTableInventoryPresentation(schema.copy(app = schema.app.copy(id = "other")), resource, record))
        assertNull(nativeTableInventoryPresentation(schema, resource.copy(id = "rows"), record))
    }

    @Test
    fun loadedSummaryDoesNotClaimAnUnfetchedTotal() {
        assertEquals("1 record loaded | 1 column", nativeTableLoadedSummary(1, 1, 1))
        assertEquals("2 of 20 loaded records | 4 columns", nativeTableLoadedSummary(2, 20, 4))
        assertEquals("0 records loaded", nativeTableLoadedSummary(0, 0, null))
    }

    @Test
    fun rowTitleIsShownOnceOnlyWhenExactlyOneFieldSuppliesIt() {
        val title = NativeDetailFieldPresentation("name", NativeFormattedField("Item", "Synthetic item"))
        val status = NativeDetailFieldPresentation("status", NativeFormattedField("Status", "Ready"))
        val detail = NativeTableRecordDetailPresentation("Synthetic item", listOf(title, status))
        assertEquals(title, detail.titleField)
        assertEquals(listOf(status), detail.detailFields)
        val ambiguous = detail.copy(fields = listOf(title, title.copy(fieldId = "other")))
        assertNull(ambiguous.titleField)
        assertEquals(2, ambiguous.detailFields.size)
    }
}
