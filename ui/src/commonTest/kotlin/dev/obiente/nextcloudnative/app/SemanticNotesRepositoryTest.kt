package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.runtime.forApp
import dev.obiente.nextcloudnative.nativeui.runtime.notesRoutes
import dev.obiente.nextcloudnative.nativeui.runtime.resolveSemanticNotesContract
import dev.obiente.nextcloudnative.nativeui.runtime.semanticDescriptor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SemanticNotesRepositoryTest {
    private val sent = mutableListOf<NextcloudApiRequest>()

    @Test
    fun `an unknown notes app lists and reads notes through its own contract`() = runBlocking<Unit> {
        val notes = repository("jotter") { request ->
            when (request.method) {
                NextcloudApiMethod.GET -> if (request.relativePath.endsWith("/notes")) {
                    json("""[{"id":4,"title":"Plan","category":"Work","favorite":true,"modified":20,"etag":"e4"}]""")
                } else {
                    json("""{"id":4,"title":"Plan","content":"# Plan","category":"Work","favorite":true}""", etag = "\"h4\"")
                }
                else -> json("{}")
            }
        }

        val listed = notes.listNotes().single()
        val loaded = notes.loadNote(4)

        assertEquals("/apps/jotter/api/v1/notes", sent[0].relativePath)
        assertEquals(mapOf("exclude" to "content"), sent[0].queryParameters)
        assertNull(listed.content)
        assertTrue(listed.favorite)
        assertEquals("# Plan", loaded.content)
        assertEquals("\"h4\"", loaded.etag)
        assertEquals("jotter", notes.appId)
    }

    @Test
    fun `saving sends the last known version as a precondition and only declared fields`() = runBlocking<Unit> {
        val notes = repository("jotter") { json("""{"id":4,"title":"Plan","content":"new","category":"","favorite":false}""") }

        notes.updateNote(4, "new", "", favorite = false, expectedEtag = "abc", title = "Plan")

        val request = sent.single()
        assertEquals(NextcloudApiMethod.PUT, request.method)
        assertEquals("/apps/jotter/api/v1/notes/4", request.relativePath)
        assertEquals("\"abc\"", request.ifMatch)
        val body = Json.parseToJsonElement(requireNotNull(request.body).decodeToString()).jsonObject
        assertEquals(setOf("title", "content", "category", "favorite"), body.keys)
        assertEquals(JsonPrimitive(false), body["favorite"])
    }

    @Test
    fun `a stale save is reported as a conflict, not a success`() = runBlocking<Unit> {
        val notes = repository("jotter") { json("{}", status = 412) }

        val failure = assertFailsWith<IllegalStateException> {
            notes.updateNote(4, "new", "", favorite = false, expectedEtag = "abc")
        }
        assertTrue("changed on the server" in failure.message.orEmpty())
    }

    @Test
    fun `presence is absent only for an authoritative missing answer`() = runBlocking<Unit> {
        var status = 404
        val notes = repository("jotter") { json("{}", status = status) }

        assertIs<NextcloudNotePresence.Absent>(notes.inspectNotePresence(4))
        status = 500
        assertFailsWith<IllegalStateException> { notes.inspectNotePresence(4) }
    }

    @Test
    fun `without an update route every save path is unavailable`() = runBlocking<Unit> {
        val notes = repository("jotter", includeUpdate = false) { json("{}") }

        assertFalse(notes.capabilities.canUpdate)
        assertFalse(notes.capabilities.canFavorite)
        assertFailsWith<IllegalStateException> { notes.updateNote(4, "x", "", false, "abc") }
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `deletion carries the precondition and maps a missing note`() = runBlocking<Unit> {
        val notes = repository("jotter") { json("{}", status = 404) }

        assertFailsWith<IllegalStateException> { notes.deleteNote(4, "\"v1\"") }
        assertEquals(NextcloudApiMethod.DELETE, sent.single().method)
        assertEquals("\"v1\"", sent.single().ifMatch)
    }

    private fun repository(
        appId: String,
        includeUpdate: Boolean = true,
        respond: (NextcloudApiRequest) -> NextcloudApiResponse,
    ): NotesRepository {
        val descriptor = semanticDescriptor(appId, "0.0.1", notesRoutes(includeUpdate = includeUpdate).forApp(appId))
        val contract = requireNotNull(resolveSemanticNotesContract(descriptor, writesAuthorized = true))
        return SemanticNotesRepository(
            execute = { request ->
                sent += request
                respond(request)
            },
            contract = contract,
        )
    }

    private fun json(body: String, status: Int = 200, etag: String? = null) =
        NextcloudApiResponse(status, body.encodeToByteArray(), "application/json", etag)
}
