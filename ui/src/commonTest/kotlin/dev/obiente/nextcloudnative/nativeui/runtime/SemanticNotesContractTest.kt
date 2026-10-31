package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SemanticNotesContractTest {
    @Test
    fun `notes surface follows the contract shape under any app id and version`() {
        val familiar = assertNotNull(resolve("notes", "4.11.0", notesRoutes()))
        val unknown = assertNotNull(resolve("jotter", "0.0.1", notesRoutes()))

        assertEquals(familiar.operations.keys, unknown.operations.keys)
        assertEquals(NoteOperation.entries.toSet(), unknown.operations.keys)
        assertTrue(unknown.canWrite(NoteField.Favorite))
        assertTrue(unknown.canWrite(NoteField.Category))
        assertEquals("exclude" to "content", unknown.binding(NoteOperation.ListNotes).excludeContentQuery)
        assertEquals("id", unknown.binding(NoteOperation.UpdateNote).identityParameter)
    }

    @Test
    fun `settings document writes only the declared settings`() {
        val settings = assertNotNull(resolve("jotter", "0.0.1", notesRoutes())?.settings)
        val write = assertIs<SemanticSettingsWrite.Document>(settings.write)

        assertEquals(setOf("notesPath", "fileSuffix"), write.properties.keys)
    }

    @Test
    fun `an unworded collection is a notes surface only with a full note shape`() {
        val shaped = notesRoutes(collection = "entries")
        assertNotNull(resolve("jotter", "0.0.1", shaped))

        val titledOnly = listOf(
            get("/apps/APP/api/entries"),
            get("/apps/APP/api/entries/{id}"),
            write(HttpMethod.POST, "/apps/APP/api/entries", mapOf("title" to "string")),
        )
        assertNull(resolve("listy", "1.0.0", titledOnly))
    }

    @Test
    fun `without an update route notes stay readable and the editor cannot save`() {
        val contract = assertNotNull(resolve("jotter", "0.0.1", notesRoutes(includeUpdate = false)))

        assertFalse(contract.supports(NoteOperation.UpdateNote))
        assertFalse(contract.canWrite(NoteField.Favorite))
        assertTrue(contract.supports(NoteOperation.ReadNote))
    }

    @Test
    fun `an unverified version offers no note writes`() {
        val descriptor = semanticDescriptor("jotter", "0.0.1", notesRoutes().forApp("jotter"))
        val contract = assertNotNull(resolveSemanticNotesContract(descriptor, writesAuthorized = false))

        assertEquals(setOf(NoteOperation.ListNotes, NoteOperation.ReadNote), contract.operations.keys)
        assertNull(contract.settings?.write)
    }

    @Test
    fun `a board contract is not mistaken for notes`() {
        val descriptor = semanticDescriptor("kanban_x", "42.0.0", boardRoutes().forApp("kanban_x"))

        assertNull(resolveSemanticNotesContract(descriptor, writesAuthorized = true))
    }

    private fun resolve(appId: String, version: String, routes: List<FixtureRoute>): SemanticNotesContract? =
        resolveSemanticNotesContract(semanticDescriptor(appId, version, routes.forApp(appId)), writesAuthorized = true)
}
