package dev.obiente.nextcloudnative.app

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScreenRestorationTest {
    @Test
    fun `pending navigation waits for mutation recovery inside a discovered app`() {
        val notesApp = Screen.AppInfo(NextcloudAppEntry("jotter", "Jotter", null))
        assertTrue(notesApp.requiresPendingNavigationGuard(groupwareMutationInProgress = true))
        assertFalse(notesApp.requiresPendingNavigationGuard(groupwareMutationInProgress = false))
        assertTrue(Screen.Calendar.requiresPendingNavigationGuard(groupwareMutationInProgress = false))
        assertTrue(Screen.Tasks.requiresPendingNavigationGuard(groupwareMutationInProgress = false))
        assertFalse(Screen.Root.requiresPendingNavigationGuard(groupwareMutationInProgress = false))
    }

    @Test
    fun `notes folder drafts and mutations guard pending navigation`() {
        assertTrue(
            notesListRequiresPendingNavigationGuard(
                mutationInProgress = false,
                renameDraftOpen = true,
            ),
        )
        assertTrue(
            notesListRequiresPendingNavigationGuard(
                mutationInProgress = true,
                renameDraftOpen = false,
            ),
        )
        assertFalse(
            notesListRequiresPendingNavigationGuard(
                mutationInProgress = false,
                renameDraftOpen = false,
            ),
        )
    }

    @Test
    fun `link commit blocks editing without becoming a durable mutation`() {
        assertTrue(
            mutationOrLinkCommitBlocksInteraction(
                mutationInProgress = false,
                navigationCommitInProgress = true,
            ),
        )
        assertTrue(
            mutationOrLinkCommitBlocksInteraction(
                mutationInProgress = true,
                navigationCommitInProgress = false,
            ),
        )
        assertFalse(
            mutationOrLinkCommitBlocksInteraction(
                mutationInProgress = false,
                navigationCommitInProgress = false,
            ),
        )
    }

    @Test
    fun `top-level app workspaces keep navigation while focused screens stay immersive`() {
        assertTrue(Screen.Calendar.usesPersistentAppNavigation())
        assertTrue(Screen.Tasks.usesPersistentAppNavigation())
        assertTrue(Screen.Files("/").usesPersistentAppNavigation())
        assertTrue(Screen.AppInfo(NextcloudAppEntry("tables", "Tables", null)).usesPersistentAppNavigation())
        assertFalse(Screen.Chat(TalkRoom("room", "Room", null, 0)).usesPersistentAppNavigation())
        assertFalse(
            Screen.TextEditor(
                NextcloudFile(
                    path = "/note.md",
                    name = "note.md",
                    isDirectory = false,
                    mimeType = "text/markdown",
                    size = 0,
                    lastModified = null,
                    fileId = 1,
                    hasPreview = false,
                ),
                "/",
            ).usesPersistentAppNavigation(),
        )
    }

    @Test
    fun `chat restoration keeps only bounded conversation identity`() {
        val saved = Screen.Chat(
            TalkRoom(
                token = "conversation-token",
                displayName = "Design room",
                lastMessage = "Private message body must not enter saved state",
                unreadMessages = 42,
            ),
        ).toSavedScreen()
        val encoded = Json.encodeToString(saved)
        val restored = assertIs<Screen.Chat>(saved.toScreen()).room

        assertFalse(encoded.contains("Private message body"))
        assertFalse(encoded.contains("42"))
        assertEquals("conversation-token", restored.token)
        assertEquals("Design room", restored.displayName)
        assertNull(restored.lastMessage)
        assertEquals(0, restored.unreadMessages)
    }

    @Test
    fun `board and notes screens saved before contract selection reopen through discovery`() {
        for ((kind, appId) in listOf("deck" to "deck", "notes" to "notes", "note-editor" to "notes")) {
            val restored = assertIs<Screen.AppInfo>(SavedScreen(kind, noteId = 91L).toScreen())
            assertEquals(appId, restored.app.id)
            assertEquals(DynamicAppNavigationState(), restored.navigation)
        }
        val encoded = Json.encodeToString(Screen.AppInfo(NextcloudAppEntry("jotter", "Jotter", null)).toSavedScreen())
        assertFalse(encoded.contains("noteId\":9"))
    }
}
