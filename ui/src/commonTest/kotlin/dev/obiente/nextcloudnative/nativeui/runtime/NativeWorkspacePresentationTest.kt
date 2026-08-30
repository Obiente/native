package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import dev.obiente.nextcloudnative.nativeui.model.ViewSpec
import dev.obiente.nextcloudnative.nativeui.model.NativeComponent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeWorkspacePresentationTest {
    private fun field(id: String, kind: FieldKind) = FieldSpec(id, id, kind, required = false, readOnly = true)
    private fun resource(vararg fields: FieldSpec) = ResourceSpec(
        id = "inventory", name = "Inventory", confidence = Confidence.verified, fields = fields.toList(),
    )

    @Test
    fun compactRecordsRetainQuantitiesZeroAndFalseWithoutInternalIds() {
        val resource = resource(
            field("title", FieldKind.string), field("quantity", FieldKind.integer),
            field("reorderLevel", FieldKind.integer), field("active", FieldKind.boolean),
            field("tableId", FieldKind.integer), field("order", FieldKind.integer),
        )
        val record = NativeRecord("row", mapOf(
            "title" to "Drills", "quantity" to "0", "reorderLevel" to "3", "active" to "false",
            "tableId" to "42", "order" to "999",
        ))
        assertEquals(listOf("quantity", "reorderLevel", "active"), nativeRecordFacts(resource, record).map { it.fieldId })
        assertEquals(listOf("0", "3", "No"), nativeRecordFacts(resource, record).map { it.value })
        assertEquals(listOf("title", "quantity", "reorderLevel", "active"), nativeTableFields(resource, listOf(record)).map { it.id })
        assertTrue(nativeRecordFacts(resource, record, maximumFacts = 0).isEmpty())
    }

    @Test
    fun identifierFilteringDoesNotHidePaidOrValidFields() {
        val resource = resource(field("title", FieldKind.string), field("paid", FieldKind.currency), field("valid", FieldKind.boolean))
        val record = NativeRecord("one", mapOf("title" to "Invoice", "paid" to "25", "valid" to "true"))
        assertEquals(listOf("title", "paid", "valid"), nativeTableFields(resource, listOf(record)).map { it.id })
        assertEquals(listOf("paid", "valid"), nativeRecordFacts(resource, record).map { it.fieldId })
    }

    @Test
    fun tableAndCollectionSearchUseTheSameMultiTermPolicy() {
        val resource = resource(field("title", FieldKind.string), field("quantity", FieldKind.integer), field("etag", FieldKind.string))
        val record = NativeRecord("row", mapOf("title" to "Cordless drills", "quantity" to "6", "etag" to "private-token"))
        assertTrue(nativeRecordMatchesCollectionQuery(resource, record, "drills 6"))
        assertEquals(listOf(record), browseNativeDatasetRecords(resource, listOf(record), searchQuery = "drills 6"))
        assertTrue(browseNativeDatasetRecords(resource, listOf(record), searchQuery = "private-token").isEmpty())
    }

    @Test
    fun categoryCollectionsKeepHierarchyInsteadOfPromotingCountsToCharts() {
        val resource = ResourceSpec(
            id = "categories", name = "Categories", confidence = Confidence.verified,
            fields = listOf(field("name", FieldKind.string), field("budget", FieldKind.decimal)),
        )
        val view = ViewSpec("categories", "Categories", resource.id, NativeComponent.collectionList, "list", Confidence.verified)
        val records = listOf(NativeRecord("food", mapOf("name" to "Food", "budget" to "500")))
        assertEquals(GenericNativeSurface.List, view.genericSurface(resource, records))
    }

    @Test
    fun permissionsSummarizeOnlyExplicitKnownBooleansWithoutInventingRoles() {
        val resource = resource(
            field("permissionRead", FieldKind.boolean), field("permissionUpdate", FieldKind.boolean),
            field("permissionDelete", FieldKind.boolean), field("permissionManage", FieldKind.boolean),
        )
        val summary = nativePermissionSummary(resource, NativeRecord("share", mapOf(
            "permissionRead" to "true", "permissionUpdate" to "true", "permissionDelete" to "false",
            "permissionManage" to "unknown",
        )))!!
        assertEquals(listOf("Read", "Edit"), summary.allowed)
        assertEquals(listOf("Delete"), summary.denied)
        assertFalse("permissionManage" in summary.fieldIds)
        assertNull(nativePermissionSummary(resource, NativeRecord("share", emptyMap())))
    }

    @Test
    fun negativeRemainingIsExplicitlyOverBudget() {
        assertTrue(nativeBudgetRemainingLabel(-20.0, "EUR").endsWith("over budget"))
        assertFalse(nativeBudgetRemainingLabel(-20.0, "EUR").contains("-20"))
        assertTrue(nativeBudgetRemainingLabel(0.0, "EUR").endsWith("left"))
    }

    @Test
    fun boardColumnsUseAvailableDesktopSpaceAndHintPhoneOverflow() {
        assertEquals(326f, nativeBoardLaneWidth(390f, 3))
        assertTrue(nativeBoardLaneWidth(1280f, 3) > 380f)
        assertEquals(284f, nativeBoardLaneWidth(1280f, 20))
        assertTrue(nativeBoardLaneWidth(1280f, 0).isFinite())
    }
}
