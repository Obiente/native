package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.runBlocking
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class NotesFavoriteActionTest {
    private val session = NextcloudSession("https://fixture.invalid", "fixture", "synthetic")

    @Test
    fun `favorite update carries the current etag and content`() = runBlocking<Unit> {
        val updates = mutableListOf<String?>()
        val fake = FakeNotes(note(favorite = false, etag = "e1")) { stored, favorite, etag ->
            updates += etag
            stored.copy(favorite = favorite, etag = "e2")
        }
        val outcome = setNoteFavorite(fake.services, session, 42, favorite = true)
        assertIs<NoteFavoriteOutcome.Saved>(outcome)
        assertEquals(listOf<String?>("e1"), updates)
        assertEquals(true, outcome.note.favorite)
    }

    @Test
    fun `already matching favorite sends no write`() = runBlocking<Unit> {
        val fake = FakeNotes(note(favorite = true)) { _, _, _ -> error("unexpected write") }
        assertIs<NoteFavoriteOutcome.Saved>(setNoteFavorite(fake.services, session, 42, favorite = true))
    }

    @Test
    fun `conflict reports failure with the latest note`() = runBlocking<Unit> {
        val fake = FakeNotes(note(favorite = false)) { _, _, _ -> throw IllegalStateException("The note changed on the server.") }
        val outcome = setNoteFavorite(fake.services, session, 42, favorite = true)
        assertIs<NoteFavoriteOutcome.Failed>(outcome)
        assertEquals("The note changed on the server.", outcome.message)
    }

    @Test
    fun `lost response is confirmed by reading the note again`() = runBlocking<Unit> {
        val fake = FakeNotes(note(favorite = false)) { stored, favorite, _ ->
            fake().stored = stored.copy(favorite = favorite)
            throw IllegalStateException("connection reset")
        }
        assertIs<NoteFavoriteOutcome.Saved>(setNoteFavorite(fake.services, session, 42, favorite = true))
    }

    @Test
    fun `read only notes are not changed`() = runBlocking<Unit> {
        val fake = FakeNotes(note(favorite = false).copy(readOnly = true)) { _, _, _ -> error("unexpected write") }
        assertIs<NoteFavoriteOutcome.Failed>(setNoteFavorite(fake.services, session, 42, favorite = true))
    }

    private var current: FakeNotes? = null
    private fun fake(): FakeNotes = checkNotNull(current)

    private inner class FakeNotes(
        var stored: NextcloudNote,
        val onUpdate: (NextcloudNote, Boolean, String?) -> NextcloudNote,
    ) {
        init {
            current = this
        }

        val services: NextcloudPlatformServices = run {
            val unavailable = Proxy.newProxyInstance(
                NextcloudPlatformServices::class.java.classLoader,
                arrayOf(NextcloudPlatformServices::class.java),
            ) { _, method, _ -> error("Unexpected ${method.name}") } as NextcloudPlatformServices
            object : NextcloudPlatformServices by unavailable {
                override suspend fun loadNote(session: NextcloudSession, noteId: Long): NextcloudNote = stored

                override suspend fun updateNote(
                    session: NextcloudSession,
                    noteId: Long,
                    content: String,
                    category: String,
                    favorite: Boolean,
                    expectedEtag: String?,
                    title: String?,
                ): NextcloudNote = onUpdate(stored, favorite, expectedEtag).also { stored = it }
            }
        }
    }

    private fun note(favorite: Boolean, etag: String = "e1") =
        NextcloudNote(42, "Plan", 1, "", favorite, false, "body", etag)
}
