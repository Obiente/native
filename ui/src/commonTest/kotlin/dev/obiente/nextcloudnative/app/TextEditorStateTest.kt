package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TextEditorStateTest {
    @Test
    fun `discarded ambiguous save is not recreated by later draft persistence`() = runBlocking {
        val store = MemoryStore(TextEditorDraft("original", "unconfirmed save", "v1", true))
        val editor = TextEditorState(store, { error("offline") }, { _, _ -> error("unsafe retry") })
        editor.open()
        assertTrue(editor.discard())
        editor.persistEdits()
        assertNull(store.value)
        assertNull(editor.etag)
        editor.edit("new edits")
        assertFalse(editor.canSave)
    }

    @Test
    fun `failed discard keeps edits and explains why the editor must remain open`(): Unit = runBlocking {
        val stored = TextEditorDraft("original", "unsaved edits", "v1")
        val store = object : TextEditorDraftStore {
            override suspend fun load() = stored
            override suspend fun save(draft: TextEditorDraft) = Unit
            override suspend fun remove() { error("storage unavailable") }
        }
        val editor = TextEditorState(store, { error("unused") }, { _, _ -> error("unused") })
        editor.open()
        assertFalse(editor.discard())
        assertEquals("unsaved edits", editor.draft)
        assertEquals("original", editor.originalText)
        assertTrue(editor.dirty)
        assertNotNull(editor.saveError)
    }

    @Test
    fun `successful discard removes persisted edits before allowing navigation`() = runBlocking {
        val store = MemoryStore(TextEditorDraft("original", "unsaved edits", "v1"))
        val editor = TextEditorState(store, { error("unused") }, { _, _ -> error("unused") })
        editor.open()
        assertTrue(editor.discard())
        assertEquals("original", editor.draft)
        assertFalse(editor.dirty)
        assertNull(store.value)
        assertNull(editor.saveError)
    }

    @Test
    fun `undoing all edits removes the stale recovery draft`() = runBlocking {
        val store = MemoryStore()
        val editor = TextEditorState(store, { content("old", "v1") }, { _, _ -> error("unused") })
        editor.open()
        editor.edit("draft")
        editor.persistEdits()
        assertEquals("draft", store.value?.text)
        editor.edit("old")
        editor.persistEdits()
        assertNull(store.value)
    }

    @Test
    fun `successful save without revision never reuses the old etag`() = runBlocking {
        val store = MemoryStore()
        var remote = content("old", "v1")
        val editor = TextEditorState(store, { remote }, { _, revision ->
            assertEquals("v1", revision)
            SavedTextFile(null, false)
        })
        editor.open()
        editor.edit("new")
        editor.save()
        assertNull(editor.etag)
        editor.edit("newer")
        assertFalse(editor.canSave)
        remote = content("new", "v2")
        editor.verifyRevision()
        assertEquals("newer", editor.draft)
        assertEquals("v2", editor.etag)
        assertTrue(editor.canSave)
    }

    @Test
    fun `draft and original revision survive owner recreation`() = runBlocking {
        val store = MemoryStore()
        val first = TextEditorState(store, { content("old", "v1") }, { _, _ -> error("unused") })
        first.open()
        first.edit("private draft")
        first.persist()
        val recreated = TextEditorState(store, { error("restored draft needs no download") }, { _, _ -> error("unused") })
        recreated.open()
        assertEquals("old", recreated.originalText)
        assertEquals("private draft", recreated.draft)
        assertEquals("v1", recreated.etag)
        assertTrue(recreated.canSave)
    }

    @Test
    fun `cancelled save persists unknown delivery and recovery verifies content`() = runBlocking {
        val store = MemoryStore()
        val first = TextEditorState(store, { content("old", "v1") }, { _, _ ->
            assertTrue(requireNotNull(store.value).verificationRequired)
            throw CancellationException("interrupted")
        })
        first.open()
        first.edit("new")
        assertFailsWith<CancellationException> { first.save() }
        assertFalse(first.canSave)
        var reads = 0
        val recreated = TextEditorState(store, { reads++; content("new", "v2") }, { _, _ -> error("must not retry") })
        recreated.open()
        assertEquals(1, reads)
        assertFalse(recreated.dirty)
        assertEquals("v2", recreated.etag)
        assertNull(store.value)
    }

    @Test
    fun `failed ambiguous-save verification leaves revision unavailable for retry`() = runBlocking {
        val store = MemoryStore(TextEditorDraft("old", "draft", "v1", true))
        var offline = true
        val editor = TextEditorState(store, {
            if (offline) error("offline") else content("old", "v2")
        }, { _, _ -> error("unsafe write") })
        editor.open()
        assertEquals("draft", editor.draft)
        assertNull(editor.etag)
        assertFalse(editor.canSave)
        assertTrue(requireNotNull(store.value).verificationRequired)
        offline = false
        editor.verifyRevision()
        assertEquals("v2", editor.etag)
        assertTrue(editor.canSave)
    }

    @Test
    fun `ambiguous save with changed remote retains draft and blocks overwrite`() = runBlocking {
        val store = MemoryStore(TextEditorDraft("old", "draft", "v1", true))
        val editor = TextEditorState(store, { content("other change", "v3") }, { _, _ -> error("unsafe write") })
        editor.open()
        assertEquals("draft", editor.draft)
        assertNull(editor.etag)
        assertFalse(editor.canSave)
        assertTrue(editor.dirty)
    }

    @Test
    fun `confirmed conflict preserves draft and prevents a blind second save`() = runBlocking {
        val store = MemoryStore()
        var writes = 0
        val editor = TextEditorState(store, { content("old", "v1") }, { _, _ ->
            writes++
            throw TextFileDavSaveConflictException()
        })
        editor.open()
        editor.edit("local edits")
        editor.save()
        assertEquals("local edits", editor.draft)
        assertEquals("old", editor.originalText)
        assertNull(editor.etag)
        assertFalse(editor.canSave)
        assertEquals(
            "The server file changed. Your edits are kept; verify the server version before saving again.",
            editor.saveError,
        )
        assertEquals("local edits", store.value?.text)
        assertTrue(requireNotNull(store.value).verificationRequired)
        editor.save()
        assertEquals(1, writes)
        val restored = TextEditorState(store, { content("remote edits", "v2") }, { _, _ -> error("unsafe write") })
        restored.open()
        assertEquals("local edits", restored.draft)
        assertFalse(restored.canSave)
        assertNull(restored.etag)
    }

    @Test
    fun `unclassified save failure still requires delivery verification`() = runBlocking {
        val editor = TextEditorState(MemoryStore(), { content("old", "v1") }, { _, _ ->
            error("unknown result")
        })
        editor.open()
        editor.edit("local edits")
        editor.save()
        assertEquals(
            "The save could not be confirmed. Verify the server version before saving again.",
            editor.saveError,
        )
        assertEquals("local edits", editor.draft)
        assertFalse(editor.canSave)
    }

    @Test
    fun `failed recovery checkpoint prevents network write`() = runBlocking {
        val store = object : TextEditorDraftStore {
            override suspend fun load(): TextEditorDraft? = null
            override suspend fun save(draft: TextEditorDraft) { error("disk full") }
            override suspend fun remove() = Unit
        }
        var writes = 0
        val editor = TextEditorState(store, { content("old", "v1") }, { _, _ -> writes++; SavedTextFile("v2", false) })
        editor.open()
        editor.edit("new")
        editor.save()
        assertEquals(0, writes)
        assertEquals("new", editor.draft)
        // Nothing reached the server, so this is a local storage failure, not an ambiguous save.
        assertEquals("v1", editor.etag)
        assertTrue(editor.canSave)
        assertEquals("Could not keep a recovery copy, so nothing was saved. Your edits are kept.", editor.saveError)
    }

    @Test
    fun `recovery copy failure after a confirmed upload keeps the new revision`() = runBlocking {
        var saves = 0
        val store = object : TextEditorDraftStore {
            override suspend fun load(): TextEditorDraft? = null
            // The pre-upload checkpoint succeeds; the post-upload cleanup fails.
            override suspend fun save(draft: TextEditorDraft) { if (++saves > 1) error("disk full") }
            override suspend fun remove() { error("disk full") }
        }
        val editor = TextEditorState(store, { content("old", "v1") }, { _, _ -> SavedTextFile("v2", false) })
        editor.open()
        editor.edit("new")
        editor.save()
        assertEquals("v2", editor.etag)
        assertEquals("new", editor.originalText)
        assertFalse(editor.dirty)
        assertEquals("Saved, but the local recovery copy could not be updated.", editor.saveError)
        editor.edit("newer")
        assertTrue(editor.canSave)
    }

    @Test
    fun `listing revision is used when the download has no ETag`() = runBlocking {
        val revisions = mutableListOf<String>()
        val editor = TextEditorState(
            MemoryStore(),
            { content("old", null) },
            { _, revision -> revisions += revision; SavedTextFile("v2", false) },
            listingEtag = "v1",
        )
        editor.open()
        assertEquals("v1", editor.etag)
        editor.edit("new")
        assertTrue(editor.canSave)
        editor.save()
        assertEquals(listOf("v1"), revisions)
    }

    private fun content(text: String, etag: String?) = NextcloudFileContent(text.encodeToByteArray(), "text/plain", etag)

    private class MemoryStore(var value: TextEditorDraft? = null) : TextEditorDraftStore {
        override suspend fun load(): TextEditorDraft? = value
        override suspend fun save(draft: TextEditorDraft) { value = draft }
        override suspend fun remove() { value = null }
    }
}
