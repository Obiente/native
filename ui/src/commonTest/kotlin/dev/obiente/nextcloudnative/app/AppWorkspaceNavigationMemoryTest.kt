package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppWorkspaceNavigationMemoryTest {
    @Test
    fun `switching apps restores each app's last route`() {
        var memory = AppWorkspaceNavigationMemory<String>()

        val files = memory.switchTo("files", "files:/")
        memory = files.memory.retainCurrent("files:/Projects/Native")
        val talk = memory.switchTo("talk", "talk:rooms")
        memory = talk.memory.retainCurrent("talk:room:design")
        val restoredFiles = memory.switchTo("files", "files:/")

        assertEquals("files:/Projects/Native", restoredFiles.restoredState)
        assertEquals("files", restoredFiles.memory.activeAppId)
        assertEquals("talk:room:design", restoredFiles.memory.lastStateByApp["talk"])
    }

    @Test
    fun `leaving an app keeps its route available without an active workspace`() {
        val opened = AppWorkspaceNavigationMemory<String>()
            .switchTo("deck", "deck:board:launch")
            .memory
            .retainCurrent("deck:card:42")
            .leave()

        assertNull(opened.activeAppId)
        assertEquals("deck:card:42", opened.lastStateByApp["deck"])
        assertEquals("deck:card:42", opened.switchTo("deck", "deck:board:launch").restoredState)
    }

    @Test
    fun `opening an app root bypasses and replaces its remembered nested route`() {
        val memory = AppWorkspaceNavigationMemory<String>()
            .switchTo("notes", "notes:root")
            .memory
            .retainCurrent("notes:42")

        val root = memory.switchTo(
            appId = "notes",
            initialState = "notes:root",
            restoreRememberedState = false,
        )

        assertEquals("notes:root", root.restoredState)
        assertEquals("notes:root", root.memory.lastStateByApp["notes"])
    }

    @Test
    fun `remembered workspaces stay bounded by most recent use`() {
        var memory = AppWorkspaceNavigationMemory<String>()
        repeat(4) { index ->
            memory = memory.switchTo("app-$index", "state-$index", maximumRememberedApps = 3).memory
        }

        assertEquals(setOf("app-1", "app-2", "app-3"), memory.lastStateByApp.keys)
    }
}
