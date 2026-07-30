package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.ActionEffect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GenericNativeRendererActionsTest {
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
