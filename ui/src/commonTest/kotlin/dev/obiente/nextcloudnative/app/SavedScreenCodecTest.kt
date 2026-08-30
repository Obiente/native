package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SavedScreenCodecTest {
    @Test
    fun tasksWorkspaceRestoresAsItsNativeRoute() {
        assertEquals(Screen.Tasks, Screen.Tasks.toSavedScreen().toScreen())
    }

    @Test
    fun filePathSerializationIsBounded() {
        val saved = Screen.Files("/" + "x".repeat(3_000)).toSavedScreen()

        assertEquals(2_048, saved.path?.length)
        assertEquals(Screen.Files(saved.path.orEmpty()), saved.toScreen())
    }

    @Test
    fun chatRestorationKeepsSafeRouteIdentity() {
        val saved = Screen.Chat(
            TalkRoom(
                token = "room-token",
                displayName = "Project room",
                lastMessage = null,
                unreadMessages = 9,
            ),
        ).toSavedScreen()

        val restored = assertIs<Screen.Chat>(saved.toScreen())
        assertEquals("room-token", restored.room.token)
        assertEquals("Project room", restored.room.displayName)
        assertEquals(0, restored.room.unreadMessages)
    }

    @Test
    fun unsafeAppIdentityRestoresRoot() {
        val restored = SavedScreen(
            kind = "app-info",
            appId = "unsafe\u0000id",
            appName = "Unsafe app",
        ).toScreen()

        assertEquals(Screen.Root, restored)
    }

    @Test
    fun unknownRouteKindRestoresRoot() {
        assertEquals(Screen.Root, SavedScreen(kind = "future-route").toScreen())
    }
}
