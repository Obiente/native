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
    fun `a revalidated list sends its cached validator and reuses the cache on 304`() = runBlocking<Unit> {
        var status = 304
        val notes = repository("jotter") {
            json(if (status == 304) "" else """[{"id":4,"title":"Plan"}]""", status = status, etag = "\"list-2\"")
        }

        assertIs<NextcloudConditionalRead.NotModified>(notes.listNotesConditionally("\"list-1\""))
        assertEquals("\"list-1\"", sent.last().ifNoneMatch)
        status = 200
        val modified = assertIs<NextcloudConditionalRead.Modified<List<NextcloudNote>>>(notes.listNotesConditionally(null))
        assertNull(sent.last().ifNoneMatch)
        assertEquals("\"list-2\"", modified.responseEtag)
        assertEquals(listOf(4L), modified.value.map(NextcloudNote::id))
    }

    @Test
    fun `a 304 without a sent validator is not treated as unchanged`() = runBlocking<Unit> {
        val notes = repository("jotter") { json("", status = 304) }

        assertFailsWith<IllegalStateException> { notes.listNotesConditionally(null) }
        assertFailsWith<IllegalStateException> { notes.loadNoteConditionally(4, " ") }
    }

    @Test
    fun `a revalidated note body reuses the cache on 304 and returns the new version otherwise`() = runBlocking<Unit> {
        var status = 304
        val notes = repository("jotter") {
            json(if (status == 304) "" else """{"id":4,"title":"Plan","content":"v2"}""", status = status, etag = "\"n2\"")
        }

        assertIs<NextcloudConditionalRead.NotModified>(notes.loadNoteConditionally(4, "\"n1\""))
        assertEquals("\"n1\"", sent.last().ifNoneMatch)
        assertEquals("/apps/jotter/api/v1/notes/4", sent.last().relativePath)
        status = 200
        val modified = assertIs<NextcloudConditionalRead.Modified<NextcloudNote>>(notes.loadNoteConditionally(4, "\"n1\""))
        assertEquals("v2", modified.value.content)
        assertEquals("\"n2\"", modified.responseEtag)
    }

    @Test
    fun `response fields match by the same normalization as contract evidence`() = runBlocking<Unit> {
        val notes = repository("jotter") {
            json("""[{"id":4,"Title":"Plan","last_modified":1700000000,"is_read_only":true,"Favorite":1}]""")
        }

        val note = notes.listNotes().single()

        assertEquals("Plan", note.title)
        assertEquals(1_700_000_000L, note.modified)
        assertTrue(note.favorite)
        assertTrue(note.readOnly)
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
