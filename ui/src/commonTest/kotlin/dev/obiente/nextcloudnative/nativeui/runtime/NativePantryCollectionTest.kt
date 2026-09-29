package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.*
import kotlin.test.*

class NativePantryCollectionTest {
    private val fields = listOf(FieldSpec("name", "Name", FieldKind.string, false, false),
        FieldSpec("quantity", "Quantity", FieldKind.string, false, false),
        FieldSpec("done", "Done", FieldKind.boolean, false, false))
    private val resource = ResourceSpec("items", "Items", Confidence.high, fields,
        listOf(Evidence(EvidenceSource.verifiedAppPackage, "Synthetic reviewed contract")))
    private val schema = NativeAppSchema("test", AppIdentity("pantry", "Pantry", "0.34.0"),
        Confidence.high, resources = listOf(resource))

    @Test fun onlyReviewedShapesUsePantryCards() {
        assertEquals(NativePantryCollectionKind.Item, nativePantryCollectionKind(schema, resource))
        assertEquals(NativePantryCollectionKind.Household, nativePantryCollectionKind(schema, resource.copy(id = "houses")))
        assertEquals(NativePantryCollectionKind.List, nativePantryCollectionKind(schema, resource.copy(id = "lists")))
        assertNull(nativePantryCollectionKind(schema.copy(app = schema.app.copy(version = "0.35.0")), resource))
        assertNull(nativePantryCollectionKind(schema.copy(app = schema.app.copy(id = "other")), resource))
        listOf(resource.copy(evidence = emptyList()), resource.copy(confidence = Confidence.low),
            resource.copy(id = "notes"), resource.copy(fields = fields.filterNot { it.id == "done" }),
            resource.copy(fields = fields.map { if (it.id == "done") it.copy(kind = FieldKind.string) else it })
        ).forEach { assertNull(nativePantryCollectionKind(schema, it)) }
    }

    @Test fun completionAndQuantityDoNotInferMissingStatusOrChangeAuthoritativeRecords() {
        val record = NativeRecord("item-7", mapOf("name" to "Synthetic lentils", "quantity" to "2 cans",
            "description" to "For the shared meal", "done" to "false", "listId" to "list-3", "deleteOnDone" to "true"))
        assertEquals(NativePantryRecordPresentation("Synthetic lentils", "For the shared meal", "2 cans", "To do"),
            nativePantryRecordPresentation(NativePantryCollectionKind.Item, record))
        assertEquals("Completed", nativePantryRecordPresentation(NativePantryCollectionKind.Item,
            record.copy(values = record.values + ("done" to "true"))).completion)
        listOf(null, "", "1", "maybe").forEach { status ->
            assertEquals("Status unavailable", nativePantryRecordPresentation(NativePantryCollectionKind.Item,
                record.copy(values = record.values + ("done" to status))).completion)
        }
        assertNull(nativePantryRecordPresentation(NativePantryCollectionKind.Household, record).completion)
        assertEquals("list-3", record.values["listId"])
        assertEquals("false", record.values["done"])
        assertTrue(record.actionSafeIdentity)
    }
}
