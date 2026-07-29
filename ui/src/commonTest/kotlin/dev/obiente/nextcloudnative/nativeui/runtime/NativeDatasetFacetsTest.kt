package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.AppIdentity
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.NativeComponent
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import dev.obiente.nextcloudnative.nativeui.model.ViewSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class NativeDatasetFacetsTest {
    private val resource = ResourceSpec(
        id = "rows",
        name = "Inventory items",
        confidence = Confidence.verified,
        fields = listOf(
            FieldSpec("name", "Item", FieldKind.string, true, true),
            FieldSpec("category", "Category", FieldKind.string, false, true),
            FieldSpec("status", "Status", FieldKind.enumeration, false, true),
            FieldSpec("serial", "Serial number", FieldKind.string, false, true),
        ),
    )
    private val records = listOf(
        record("1", "Field recorder", "Audio", "Available", "A-001"),
        record("2", "Tripod", "Camera", "On loan", "C-002"),
        record("3", "USB-C hub", "Computer", "Available", "D-003"),
        record("4", "Lighting kit", "Camera", "Reserved", "C-004"),
        record("5", "Studio monitor", "Audio", "Available", "A-005"),
    )

    @Test
    fun `facets use semantic categorical fields and reject unique strings`() {
        val facets = inferNativeDatasetFacets(resource, records)

        assertEquals(listOf("status", "category"), facets.map { it.field.id })
        assertEquals(
            listOf("Available" to 3, "On loan" to 1, "Reserved" to 1),
            facets.first().options.map { it.label to it.count },
        )
        assertTrue(facets.none { it.field.id in setOf("name", "serial") })
    }

    @Test
    fun `facet groups combine with and while values within one group use or`() {
        val filtered = filterNativeDatasetRecords(
            records,
            mapOf(
                "status" to setOf("Available", "Reserved"),
                "category" to setOf("Audio"),
            ),
        )

        assertEquals(listOf("1", "5"), filtered.map(NativeRecord::id))
    }

    @Test
    fun `browsing searches the title and visible field values without changing server order`() {
        assertEquals(
            listOf("1", "5"),
            browseNativeDatasetRecords(
                resource = resource,
                records = records,
                searchQuery = " audio ",
            ).map(NativeRecord::id),
        )
        assertEquals(
            listOf("2"),
            browseNativeDatasetRecords(
                resource = resource,
                records = records,
                searchQuery = "TRIPOD",
            ).map(NativeRecord::id),
        )
        assertEquals(
            records.map(NativeRecord::id),
            browseNativeDatasetRecords(resource, records).map(NativeRecord::id),
        )
    }

    @Test
    fun `browsing sorts titles deterministically in both alphabetical directions`() {
        assertEquals(
            listOf("1", "4", "5", "2", "3"),
            browseNativeDatasetRecords(
                resource = resource,
                records = records,
                sortMode = NativeDatasetSortMode.NameAscending,
            ).map(NativeRecord::id),
        )
        assertEquals(
            listOf("3", "2", "5", "4", "1"),
            browseNativeDatasetRecords(
                resource = resource,
                records = records,
                sortMode = NativeDatasetSortMode.NameDescending,
            ).map(NativeRecord::id),
        )
    }

    @Test
    fun `browse state key separates schema view resource and parent scope without exposing parent ids`() {
        val schema = NativeAppSchema(
            schemaVersion = "1",
            app = AppIdentity("inventory", "Inventory", "1.0.0"),
            confidence = Confidence.verified,
            resources = listOf(resource),
        )
        val view = ViewSpec(
            id = "inventory-table",
            title = "Inventory",
            resourceId = resource.id,
            component = NativeComponent.dataTable,
            sourceActionId = "read-inventory",
            confidence = Confidence.verified,
        )
        val firstScope = NativeDatasetContext(
            parentResourceId = "collections",
            parentRecord = NativeRecord("private-parent-a", emptyMap()),
        )
        val sameScope = NativeDatasetContext(
            parentResourceId = "collections",
            parentRecord = NativeRecord("private-parent-a", emptyMap()),
        )
        val otherParent = NativeDatasetContext(
            parentResourceId = "collections",
            parentRecord = NativeRecord("private-parent-b", emptyMap()),
        )
        val otherSchema = schema.copy(app = schema.app.copy(version = "1.0.1"))
        val otherView = view.copy(id = "inventory-overview")
        val otherResource = resource.copy(id = "archived-rows")

        val key = nativeDatasetBrowseStateKey(schema, view, resource, firstScope)

        assertEquals(key, nativeDatasetBrowseStateKey(schema, view, resource, sameScope))
        assertNotEquals(key, nativeDatasetBrowseStateKey(schema, view, resource, otherParent))
        assertNotEquals(key, nativeDatasetBrowseStateKey(otherSchema, view, resource, firstScope))
        assertNotEquals(key, nativeDatasetBrowseStateKey(schema, otherView, resource, firstScope))
        assertNotEquals(key, nativeDatasetBrowseStateKey(schema, view, otherResource, firstScope))
        assertTrue(key.startsWith("dataset-browse:"))
        assertTrue("private-parent-a" !in key)
    }

    private fun record(
        id: String,
        name: String,
        category: String,
        status: String,
        serial: String,
    ) = NativeRecord(
        id = id,
        values = mapOf(
            "name" to name,
            "category" to category,
            "status" to status,
            "serial" to serial,
        ),
    )
}
