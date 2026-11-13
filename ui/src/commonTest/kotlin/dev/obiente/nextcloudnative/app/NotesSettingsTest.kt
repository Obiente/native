package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotesSettingsTest {
    private val v14 = NotesApiCapabilities("6.1.0", listOf("0.2", "1.4"))
    private val v12 = NotesApiCapabilities("4.1.0", listOf("1.2"))

    @Test
    fun `capabilities gate settings and custom suffixes by api version`() {
        val parsed = parseNotesApiCapabilities(
            notesResponse("""{"ocs":{"data":{"capabilities":{"notes":{"api_version":["0.2","1.3"],"version":"4.5.0"}}}}}"""),
        )
        assertEquals("4.5.0", parsed?.appVersion)
        assertTrue(parsed!!.supportsSettings && parsed.supportsCustomSuffix)
        assertFalse(NotesApiCapabilities(null, listOf("1.1")).supportsSettings)
        assertFalse(v12.supportsCustomSuffix)
        assertNull(parseNotesApiCapabilities(notesResponse("""{"ocs":{"data":{"capabilities":{}}}}""")))
        assertFailsWith<IllegalStateException> { parseNotesApiCapabilities(notesResponse("[]")) }
    }

    @Test
    fun `settings update sends only changed documented attributes`() {
        val request = updateNotesSettingsRequest(v14, NotesSettingsChange(notesPath = " /Work/Notes/ "))
        assertEquals(NextcloudApiMethod.PUT, request.method)
        assertEquals("/index.php/apps/notes/api/v1/settings", request.relativePath)
        assertEquals("""{"notesPath":"Work/Notes"}""", request.body?.decodeToString())
        val suffix = updateNotesSettingsRequest(v14, NotesSettingsChange(fileSuffix = ".Markdown"))
        assertEquals("""{"fileSuffix":".markdown"}""", suffix.body?.decodeToString())
    }

    @Test
    fun `invalid paths, suffixes, and old api versions are rejected locally`() {
        assertFailsWith<IllegalArgumentException> { updateNotesSettingsRequest(v14, NotesSettingsChange(notesPath = "a/../b")) }
        assertFailsWith<IllegalArgumentException> { updateNotesSettingsRequest(v14, NotesSettingsChange(notesPath = "  ")) }
        assertFailsWith<IllegalArgumentException> { updateNotesSettingsRequest(v12, NotesSettingsChange(fileSuffix = ".org")) }
        assertFailsWith<IllegalArgumentException> { updateNotesSettingsRequest(v14, NotesSettingsChange(fileSuffix = "md")) }
        assertFailsWith<IllegalArgumentException> { updateNotesSettingsRequest(v14, NotesSettingsChange(fileSuffix = ".a/b")) }
        assertFailsWith<IllegalArgumentException> {
            updateNotesSettingsRequest(NotesApiCapabilities(null, listOf("1.1")), NotesSettingsChange(fileSuffix = ".md"))
        }
        assertFailsWith<IllegalArgumentException> { NotesSettingsChange() }
    }

    @Test
    fun `settings responses are validated`() {
        assertEquals(
            NotesSettings("Notes", ".md"),
            parseNotesSettings(notesResponse("""{"notesPath":"Notes","fileSuffix":".md","noteMode":"rich"}""")),
        )
        assertFailsWith<IllegalArgumentException> { parseNotesSettings(notesResponse("{}", 400)) }
        assertFailsWith<IllegalStateException> { parseNotesSettings(notesResponse("""{"fileSuffix":".md"}""")) }
        assertFailsWith<IllegalStateException> { parseNotesSettings(notesResponse("oops")) }
        assertEquals(
            "Notes stored the folder as \"Work Notes\".",
            describeNotesSettingsAdjustment(NotesSettingsChange(notesPath = "Work/../Notes"), NotesSettings("Work Notes", ".md")),
        )
    }

    @Test
    fun `controller confirms a save with a fresh read and reports a folder change`() = runBlocking<Unit> {
        var stored = """{"notesPath":"Notes","fileSuffix":".md"}"""
        var folderChanges = 0
        val controller = NotesSettingsController(
            execute = { request ->
                when {
                    request.relativePath.endsWith("/capabilities") ->
                        notesResponse("""{"ocs":{"data":{"capabilities":{"notes":{"api_version":["1.4"]}}}}}""")
                    request.method == NextcloudApiMethod.PUT -> {
                        stored = """{"notesPath":"Archive","fileSuffix":".md"}"""
                        notesResponse(stored)
                    }
                    else -> notesResponse(stored)
                }
            },
            onNotesFolderChanged = { folderChanges += 1 },
        )
        controller.load()
        controller.save(NotesSettingsChange(notesPath = "Archive"))
        assertEquals("Archive", controller.state.settings?.notesPath)
        assertEquals("Notes settings saved", controller.state.notice)
        assertEquals(1, folderChanges)
    }

    @Test
    fun `controller blocks saving when the outcome cannot be confirmed`() = runBlocking<Unit> {
        var reads = 0
        val controller = NotesSettingsController(
            execute = { request ->
                when {
                    request.relativePath.endsWith("/capabilities") ->
                        notesResponse("""{"ocs":{"data":{"capabilities":{"notes":{"api_version":["1.2"]}}}}}""")
                    request.method == NextcloudApiMethod.PUT -> throw IllegalStateException("reset")
                    else -> {
                        reads += 1
                        if (reads > 1) throw IllegalStateException("offline")
                        notesResponse("""{"notesPath":"Notes","fileSuffix":".md"}""")
                    }
                }
            },
            onNotesFolderChanged = {},
        )
        controller.load()
        controller.save(NotesSettingsChange(fileSuffix = ".txt"))
        assertTrue(controller.state.outcomeUnknown)
        assertFalse(controller.state.canSave)
    }

    @Test
    fun `unsupported notes versions show no editable settings`() = runBlocking<Unit> {
        val controller = NotesSettingsController(
            execute = { notesResponse("""{"ocs":{"data":{"capabilities":{"notes":{"api_version":["1.1"]}}}}}""") },
            onNotesFolderChanged = {},
        )
        controller.load()
        assertNull(controller.state.settings)
        assertFalse(controller.state.canSave)
        assertEquals(false, controller.state.capabilities?.supportsSettings)
    }

    @Test
    fun `note files are shared only when id and path agree`() {
        val note = note(internalPath = "/Notes/Plan.md")
        val file = NextcloudFile("Notes/Plan.md", "Plan.md", false, "text/markdown", 10, null, 42, false)
        assertIs<NoteFileResolution.Verified>(verifyNoteFile(note, file))
        assertIs<NoteFileResolution.Unavailable>(verifyNoteFile(note, file.copy(fileId = 43)))
        assertIs<NoteFileResolution.Unavailable>(verifyNoteFile(note, file.copy(path = "Other/Plan.md")))
        assertIs<NoteFileResolution.Unavailable>(verifyNoteFile(note, file.copy(isDirectory = true)))
        assertIs<NoteFileResolution.Unavailable>(verifyNoteFile(note.copy(internalPath = null), file))
        assertIs<NoteFileResolution.Unavailable>(verifyNoteFile(note, null))
    }

    @Test
    fun `favorites filter spans folders and combines with search`() {
        val notes = listOf(
            note(id = 1, title = "Groceries", category = "Home", favorite = true, modified = 5),
            note(id = 2, title = "Plan", category = "", favorite = false),
            note(id = 3, title = "Ideas", category = "Work", favorite = true, modified = 9),
        )
        val root = notes.filter { it.category.isEmpty() }
        assertEquals(listOf(2L), filterNotesForList(notes, root, "", NotesListFilter.All).map(NextcloudNote::id))
        assertEquals(listOf(3L, 1L), filterNotesForList(notes, root, "", NotesListFilter.Favorites).map(NextcloudNote::id))
        assertEquals(listOf(1L), filterNotesForList(notes, root, "groc", NotesListFilter.Favorites).map(NextcloudNote::id))
        assertEquals(listOf(3L), filterNotesForList(notes, root, "ideas", NotesListFilter.All).map(NextcloudNote::id))
    }

    private fun note(
        id: Long = 42,
        title: String = "Plan",
        category: String = "",
        favorite: Boolean = false,
        modified: Long = 1,
        internalPath: String? = null,
    ) = NextcloudNote(id, title, modified, category, favorite, false, "body", "e1", internalPath)

    private fun notesResponse(json: String, status: Int = 200) =
        NextcloudApiResponse(status, json.encodeToByteArray(), "application/json", etag = null)
}
