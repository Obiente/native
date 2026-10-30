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

    @Test
    fun tooltipHintsNameTheShortcutThatOpensEachDestination() {
        assertEquals("Ctrl+1", nextcloudDesktopShortcutHint(NextcloudDestination.Home))
        assertEquals("Ctrl+2", nextcloudDesktopShortcutHint(NextcloudDestination.FolderSync))
        assertEquals("Ctrl+3", nextcloudDesktopShortcutHint(NextcloudDestination.Activity))
        assertEquals("Ctrl+4", nextcloudDesktopShortcutHint(NextcloudDestination.Apps))
        assertEquals("Ctrl+,", nextcloudDesktopShortcutHint(NextcloudDestination.Settings))
        for (destination in NextcloudDestination.entries) {
            val hint = nextcloudDesktopShortcutHint(destination) ?: continue
            val key = NextcloudDesktopShortcutKey.entries.single {
                destinationForNextcloudDesktopShortcut(it, primaryModifierPressed = true) == destination
            }
            assertTrue(hint.startsWith("Ctrl+"), "Hint for $key must name the accepted modifier")
        }
    }
}
