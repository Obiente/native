package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.*
import kotlin.test.*

class NativeTableRowCardTest {
    @Test
    fun projectedColumnsExcludeEnvelopeMetadataWithoutChangingRecordIdentity() {
        val fields = listOf("owner", "createdAt", "item", "location", "status", "quantity")
            .map { FieldSpec(it, it, FieldKind.string, false, true) }
        val row = NativeRecord("original-row", mapOf("owner" to "Synthetic owner", "createdAt" to "2026-09-01",
            "item" to "Camera kit", "location" to "Studio", "status" to "Ready", "quantity" to "3"))
        val projection = NativeTableProjection(ResourceSpec("rows", "Rows", Confidence.verified, fields), listOf(row),
            composite = true, projectedFieldIds = setOf("item", "location", "status", "quantity"))
        val schema = NativeAppSchema("1", AppIdentity("tables", "Tables", "1"), Confidence.verified)
        val displayResource = assertNotNull(nativeTableRowCardResource(schema, projection))
        val detail = nativeTableRowCardPresentation(displayResource, row)
        assertEquals("Camera kit", detail.title)
        assertEquals(listOf("location", "status", "quantity"), detail.detailFields.map { it.fieldId })
        assertEquals("original-row", row.id)
        assertEquals("Synthetic owner", row.values["owner"])
        assertNull(nativeTableRowCardResource(schema.copy(app = schema.app.copy(id = "other")), projection))
        assertNull(nativeTableRowCardResource(schema, projection.copy(composite = false)))
        assertNull(nativeTableRowCardResource(schema, projection.copy(projectedFieldIds = emptySet())))
    }
}
