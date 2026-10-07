package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.runtime.NativeRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DynamicNavigationRestorationLabelTest {
    @Test
    fun restoredParentUsesVerifiedResourceLabelWithoutPersistingItsPrivateTitle() {
        val state = DynamicAppNavigationState(
            selectedViewId = "items.list",
            selectedRecord = NativeRecord("2", mapOf("name" to "Private synthetic household list")),
            selectedRecordResourceId = "lists",
            pathParameterValues = mapOf("listId" to "2"),
        )
        val saved = state.toSavedDynamicAppNavigationState()
        assertFalse(saved.toString().contains("Private synthetic household list"))

        val restored = requireNotNull(saved.toDynamicAppNavigationState().selectedRecord)
        assertEquals("Lists", restored.dynamicContextLabel("Lists"))
        assertEquals("2", restored.id)
        assertFalse(restored.actionSafeIdentity)
        assertTrue(restored.values.isEmpty())
        assertTrue(restored.displayValues.isEmpty())
    }

    @Test
    fun ancestorSnapshotsUseSafeLabelsUntilAnAuthoritativeNameIsAvailable() {
        val original = DynamicNavigationSnapshot(
            viewId = "tables.list",
            resourceId = "tables",
            record = NativeRecord("2", mapOf("title" to "Synthetic equipment")),
            recordResourceId = "tables",
            pathParameterValues = emptyMap(),
        )
        val restored = requireNotNull(
            restoreDynamicNavigationHistory(saveDynamicNavigationHistory(listOf(original))).single().record,
        )
        assertEquals("Tables", restored.dynamicContextLabel("Tables"))
        assertEquals("Synthetic equipment", original.record?.dynamicContextLabel("Tables"))
        assertFalse(restored.actionSafeIdentity)
    }

    @Test
    fun absentOrIdentifierOnlyResourceLabelsNeverExposeTheRestoredId() {
        val restored = NativeRecord("2", emptyMap(), actionSafeIdentity = false)
        for (fallback in listOf(null, "", " ", "2", "123")) {
            assertEquals("Selected item", restored.dynamicContextLabel(fallback))
        }
        val observed = restored.copy(displayValues = mapOf("name" to "Synthetic item"))
        assertEquals("Synthetic item", observed.dynamicContextLabel("Rows"))
        assertFalse(observed.actionSafeIdentity)
    }
}
