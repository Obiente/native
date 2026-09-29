package dev.obiente.nextcloudnative.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import dev.obiente.nextcloudnative.nativeui.model.*
import dev.obiente.nextcloudnative.nativeui.runtime.*
import kotlin.test.*

class NativeTableRowCardSceneTest {
    @Test
    fun narrowAndWideCardsExposeUserColumnsAndPreserveSelectionIdentity() {
        val values = linkedMapOf("owner" to "Synthetic owner", "createdAt" to "2026-09-01",
            "item" to "Camera kit", "location" to "Studio", "status" to "Ready", "quantity" to "3", "checked" to "No")
        val labels = mapOf("item" to "Item", "location" to "Location", "status" to "Status", "quantity" to "Quantity", "checked" to "Checked")
        val resource = ResourceSpec("rows", "Rows", Confidence.verified, values.keys.map {
            FieldSpec(it, labels[it] ?: it, FieldKind.string, false, true) })
        val row = NativeRecord("original-row", values)
        val projection = NativeTableProjection(resource, listOf(row), composite = true, projectedFieldIds = labels.keys)
        val schema = NativeAppSchema("1", AppIdentity("tables", "Tables", "1"), Confidence.verified)
        for (width in listOf(390, 1200)) {
            var selected: NativeRecord? = null
            nativeSceneTest(width, 844, content = {
                GenericEditableTableRecordList(schema, resource, projection, listOf(row), { selected = it },
                    NativeActionExecutor { error("Presentation must not execute a mutation") }, null, null, false, null)
            }) {
                assertEquals(1, nodes().count { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "Camera kit" } == true })
                for (label in listOf("Location: Studio", "Status: Ready", "Quantity: 3", "1 more field")) {
                    val bounds = assertNotNull(node(label), label).boundsInRoot
                    assertTrue(bounds.left >= 0 && bounds.right <= width && bounds.top >= 0 && bounds.bottom <= 844)
                }
                assertFalse(has("Synthetic owner"))
                assertFalse(has("2026-09-01"))
                click("Camera kit")
                assertEquals(row.id, selected?.id)
                assertEquals(row.values, selected?.values)
                capture("tables-row-summary-$width")
            }
        }
    }
}
