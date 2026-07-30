package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.ActionEffect
import dev.obiente.nextcloudnative.nativeui.model.DYNAMIC_INTEGER_ARRAY_FORMAT
import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GenericNativeRendererActionsTest {
    @Test
    fun `unknown command outcomes require reconciliation while explicit rejections may retry`() {
        assertTrue(NativeActionFailureOutcome.Unknown.requiresCommandReconciliation())
        assertFalse(NativeActionFailureOutcome.Rejected.requiresCommandReconciliation())
    }

    @Test
    fun `authoritative refresh wins over completion override even when records are unchanged`() {
        val originalRecords = listOf(NativeRecord("task-1", mapOf("completed" to "false")))
        val unchangedRefreshedRecords = listOf(NativeRecord("task-1", mapOf("completed" to "false")))
        assertEquals(originalRecords, unchangedRefreshedRecords)
        assertFalse(originalRecords === unchangedRefreshedRecords)

        val originalKey = NativeAuthoritativeRecordsKey(originalRecords)
        val sameSnapshotKey = NativeAuthoritativeRecordsKey(originalRecords)
        val refreshedKey = NativeAuthoritativeRecordsKey(unchangedRefreshedRecords)
        val override = NativeCompletionOverride(completed = true, sourceRecordsKey = originalKey)

        assertEquals(originalKey, sameSnapshotKey)
        assertTrue(
            effectiveNativeCompletion(
                override = override,
                authoritativeRecordsKey = sameSnapshotKey,
                authoritativeCompleted = false,
            ),
        )
        assertFalse(
            effectiveNativeCompletion(
                override = override,
                authoritativeRecordsKey = refreshedKey,
                authoritativeCompleted = false,
            ),
        )

        val overrides = mutableMapOf("task-1" to override)
        overrides.reconcileNativeCompletionOverrides(refreshedKey)
        assertTrue(overrides.isEmpty())
    }

    @Test
    fun `only optional scalar relations expose an explicit clear choice`() {
        val optionalScalar = FieldSpec(
            id = "collectionId",
            label = "Collection",
            kind = FieldKind.integer,
            required = false,
            readOnly = false,
        )

        assertEquals(
            NativeRelationOption(value = "", label = "None", supportingText = "Clear selection"),
            nativeScalarRelationClearChoice(optionalScalar),
        )
        assertNull(nativeScalarRelationClearChoice(optionalScalar.copy(required = true)))
        assertNull(
            nativeScalarRelationClearChoice(
                optionalScalar.copy(format = DYNAMIC_INTEGER_ARRAY_FORMAT),
            ),
        )
    }

    @Test
    fun `reversible record commands use concise non destructive labels`() {
        val expectations = mapOf(
            ActionEffect.archive to "Archive",
            ActionEffect.unarchive to "Unarchive",
            ActionEffect.restore to "Restore",
            ActionEffect.copy to "Copy",
        )

        expectations.forEach { (effect, label) ->
            val ui = nativeRecordCommandUi(effect, "Example record")

            assertEquals(label, ui.label)
            assertFalse(ui.destructive)
            assertNull(ui.confirmationTitle)
            assertNull(ui.confirmationMessage)
        }
    }

    @Test
    fun `destructive record commands describe the exact effect and item`() {
        val permanentDelete = nativeRecordCommandUi(
            ActionEffect.permanentDelete,
            "Example record",
        )
        val clear = nativeRecordCommandUi(ActionEffect.clear, "Example record")
        val leave = nativeRecordCommandUi(ActionEffect.leave, "Example record")

        assertEquals("Delete permanently", permanentDelete.label)
        assertEquals("Delete Example record permanently?", permanentDelete.confirmationTitle)
        assertTrue(requireNotNull(permanentDelete.confirmationMessage).contains("cannot be undone"))

        assertEquals("Clear", clear.label)
        assertEquals("Clear Example record?", clear.confirmationTitle)
        assertTrue(requireNotNull(clear.confirmationMessage).contains("cannot be undone"))

        assertEquals("Leave", leave.label)
        assertEquals("Leave Example record?", leave.confirmationTitle)
        assertTrue(requireNotNull(leave.confirmationMessage).contains("lose access"))

        assertTrue(permanentDelete.destructive)
        assertTrue(clear.destructive)
        assertTrue(leave.destructive)
    }
}
