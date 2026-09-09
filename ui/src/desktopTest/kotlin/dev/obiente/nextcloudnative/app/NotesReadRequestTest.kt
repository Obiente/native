package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotesReadRequestTest {
    @Test
    fun listRequestExplicitlyExcludesBodiesAndSupportsConditionalRefresh() {
        assertTrue(NOTES_LIST_RELATIVE_PATH.endsWith("?exclude=content"))
        assertFalse("include=content" in NOTES_LIST_RELATIVE_PATH)
        assertEquals(mapOf("If-None-Match" to "\"list-v1\""), notesConditionalHeaders("\"list-v1\""))
        assertTrue(notesConditionalHeaders(" ").isEmpty())
    }

    @Test
    fun detailRequestUsesOnlyValidatedNumericIdentity() {
        assertEquals("/index.php/apps/notes/api/v1/notes/42", notesDetailRelativePath(42))
        assertFailsWith<IllegalArgumentException> { notesDetailRelativePath(-1) }
        assertEquals("\"http-etag\"", resolvedNoteEtag("\"http-etag\"", "document-etag"))
        assertEquals("document-etag", resolvedNoteEtag(null, "document-etag"))
    }

    @Test
    fun mutationHeadersSerializeBareNotesApiEtagsAsHttpValidators() {
        assertEquals(mapOf("If-Match" to "\"bare-etag\""), notesMutationHeaders("bare-etag"))
        assertEquals(mapOf("If-Match" to "\"quoted-etag\""), notesMutationHeaders("\"quoted-etag\""))
        assertEquals(mapOf("If-Match" to "W/\"weak-etag\""), notesMutationHeaders("W/\"weak-etag\""))
        assertEquals(mapOf("If-Match" to "*"), notesMutationHeaders("*"))
        assertTrue(notesMutationHeaders(" ").isEmpty())
        assertFailsWith<IllegalArgumentException> { notesMutationHeaders("bad\nvalue") }
        assertFailsWith<IllegalArgumentException> { notesMutationHeaders("W/bare") }
    }
}
