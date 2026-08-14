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
    fun `pending navigation waits for mutation recovery on top-level notes`() {
        assertTrue(Screen.Notes.requiresPendingNavigationGuard(groupwareMutationInProgress = true))
        assertFalse(Screen.Notes.requiresPendingNavigationGuard(groupwareMutationInProgress = false))
        assertTrue(Screen.Calendar.requiresPendingNavigationGuard(groupwareMutationInProgress = false))
        assertFalse(Screen.Root.requiresPendingNavigationGuard(groupwareMutationInProgress = false))
    }

    @Test
    fun `notes drafts guard pending navigation before submission`() {
        assertTrue(
            notesListRequiresPendingNavigationGuard(
                mutationInProgress = false,
                createDraftOpen = true,
                renameDraftOpen = false,
            ),
        )
        assertTrue(
            notesListRequiresPendingNavigationGuard(
                mutationInProgress = false,
                createDraftOpen = false,
                renameDraftOpen = true,
            ),
        )
        assertFalse(
            notesListRequiresPendingNavigationGuard(
                mutationInProgress = false,
                createDraftOpen = false,
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
    fun `note restoration keeps its route without serializing the note payload`() {
        val saved = Screen.NoteEditor(
            NextcloudNote(
                id = 91L,
                title = "Private planning title",
                modified = 1_725_000_000L,
                category = "Confidential",
                favorite = true,
                readOnly = false,
                content = "Private note body must not enter saved state",
                etag = "secret-etag",
                internalPath = "Notes/Private.md",
                isShared = true,
            ),
        ).toSavedScreen()
        val encoded = Json.encodeToString(saved)
        val restored = assertIs<Screen.NoteEditor>(saved.toScreen()).note

        assertFalse(encoded.contains("Private planning title"))
        assertFalse(encoded.contains("Private note body"))
        assertFalse(encoded.contains("secret-etag"))
        assertFalse(encoded.contains("Confidential"))
        assertEquals(91L, restored.id)
        assertNull(restored.content)
        assertNull(restored.etag)
        assertTrue(restored.readOnly)
    }
}
