package dev.obiente.nextcloudnative.app.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopShellShortcutsTest {
    @Test
    fun appSelectionUsesTheSameCanonicalIdsAsWorkspacePins() {
        assertTrue(desktopAppIdsMatch("talk", "spreed"))
        assertTrue(desktopAppIdsMatch("spreed", "Talk"))
        assertTrue(desktopAppIdsMatch("Calendar", "calendar"))
        assertFalse(desktopAppIdsMatch("talk", null))
        assertFalse(desktopAppIdsMatch("talk", "calendar"))
    }

    @Test
    fun `number shortcuts map to persistent desktop destinations`() {
        assertEquals(
            NextcloudDestination.Home,
            destinationForNextcloudDesktopShortcut(NextcloudDesktopShortcutKey.One, true),
        )
        assertEquals(
            NextcloudDestination.FolderSync,
            destinationForNextcloudDesktopShortcut(NextcloudDesktopShortcutKey.Two, true),
        )
        assertEquals(
            NextcloudDestination.Activity,
            destinationForNextcloudDesktopShortcut(NextcloudDesktopShortcutKey.Three, true),
        )
        assertEquals(
            NextcloudDestination.Apps,
            destinationForNextcloudDesktopShortcut(NextcloudDesktopShortcutKey.Four, true),
        )
    }

    @Test
    fun `platform settings convention is supported`() {
        assertEquals(
            NextcloudDestination.Settings,
            destinationForNextcloudDesktopShortcut(NextcloudDesktopShortcutKey.Comma, true),
        )
    }

    @Test
    fun `plain typing never triggers global navigation`() {
        NextcloudDesktopShortcutKey.entries.forEach { key ->
            assertNull(destinationForNextcloudDesktopShortcut(key, false))
        }
    }
}
