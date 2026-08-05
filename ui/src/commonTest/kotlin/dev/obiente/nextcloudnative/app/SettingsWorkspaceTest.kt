package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsWorkspaceTest {
    @Test
    fun `minimum desktop content width prioritizes actionable settings detail`() {
        val compact = resolveSettingsWorkspaceLayout(670)

        assertEquals(206, compact.categoryWidthDp)
        assertFalse(compact.showSummaryPane)
    }

    @Test
    fun `wide settings workspace restores account summary`() {
        val wide = resolveSettingsWorkspaceLayout(1_200)

        assertEquals(246, wide.categoryWidthDp)
        assertTrue(wide.showSummaryPane)
    }
}
