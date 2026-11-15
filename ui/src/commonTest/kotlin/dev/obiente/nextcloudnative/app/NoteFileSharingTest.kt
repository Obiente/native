package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class NoteFileSharingTest {
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
}
