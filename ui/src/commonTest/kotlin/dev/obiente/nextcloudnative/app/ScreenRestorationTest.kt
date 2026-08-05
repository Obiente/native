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
