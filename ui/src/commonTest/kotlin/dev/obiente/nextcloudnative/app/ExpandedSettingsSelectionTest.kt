package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExpandedSettingsSelectionTest {
    @Test
    fun expandedWorkspaceMaterializesOnlyItsVisibleDefault() {
        val visible = listOf(SettingsWorkspaceSection.Account, SettingsWorkspaceSection.Support)

        assertEquals(SettingsWorkspaceSection.Account, expandedSettingsSection(null, visible))
        assertEquals(
            SettingsWorkspaceSection.Support,
            expandedSettingsSection(SettingsWorkspaceSection.Support, visible),
        )
        assertNull(expandedSettingsSection(null, emptyList()))
    }
}
