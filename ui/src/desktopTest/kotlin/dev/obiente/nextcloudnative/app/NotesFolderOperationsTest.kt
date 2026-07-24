package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NotesFolderOperationsTest {
    @Test
    fun `late read only note aborts rename before any mutation`() = runBlocking {
        val summaries = listOf(
            note(1, "First", "Work"),
            note(2, "Locked", "Work/Nested", readOnly = true),
        )
        val loaded = mutableListOf<Long>()
        val mutated = mutableListOf<Long>()

        val failure = assertFailsWith<IllegalStateException> {
            executeNoteFolderRename(
                summaries = summaries,
                oldCategory = "Work",
                newCategory = "Archive",
                loadNote = { id ->
                    loaded += id
                    summaries.first { note -> note.id == id }
                },
                updateNote = { mutation -> mutated += mutation.note.id },
                reloadSummaries = { error("A preflight failure must not need reconciliation.") },
            )
        }

        assertEquals(listOf(1L, 2L), loaded)
        assertTrue(mutated.isEmpty())
        assertTrue(failure.message.orEmpty().contains("read only"))
    }

    @Test
    fun `rename destination cannot merge with an existing category subtree`() = runBlocking {
        val source = note(1, "Source", "Work/Incoming")
        val existing = note(2, "Existing", "Work/Archive/2025")
        val mutated = mutableListOf<Long>()

        val failure = assertFailsWith<IllegalArgumentException> {
            executeNoteFolderRename(
                summaries = listOf(source, existing),
                oldCategory = "Work/Incoming",
                newCategory = "Work/Archive",
                loadNote = { source },
                updateNote = { mutation -> mutated += mutation.note.id },
                reloadSummaries = { error("Mutation never began.") },
            )
        }

        assertTrue(failure.message.orEmpty().contains("already exists"))
        assertTrue(mutated.isEmpty())
    }

    @Test
    fun `all detail loads finish before first delete mutation`() = runBlocking {
        val summaries = listOf(
            note(1, "First", "Work"),
            note(2, "Missing", "Work/Nested"),
        )
        val mutated = mutableListOf<Long>()

        assertFailsWith<IllegalStateException> {
            executeNoteFolderDelete(
                summaries = summaries,
                category = "Work",
                loadNote = { id ->
                    if (id == 2L) error("The note no longer exists.")
                    summaries.first { note -> note.id == id }
                },
                deleteNote = { mutation -> mutated += mutation.note.id },
                reloadSummaries = { error("Mutation never began.") },
            )
        }

        assertTrue(mutated.isEmpty())
    }

    @Test
    fun `transport failure reconciles summaries and reports partial completion`() = runBlocking {
        val summaries = listOf(
            note(1, "First", "Work"),
            note(2, "Second", "Work"),
        )
        val reconciled = listOf(note(2, "Second", "Work"))
        var reloads = 0

        val failure = assertFailsWith<PartialNoteFolderMutationException> {
            executeNoteFolderDelete(
                summaries = summaries,
                category = "Work",
                loadNote = { id -> summaries.first { note -> note.id == id } },
                deleteNote = { mutation ->
                    if (mutation.note.id == 2L) error("Server disconnected.")
                },
                reloadSummaries = {
                    reloads += 1
                    reconciled
                },
            )
        }

        assertIs<PartialNoteFolderMutationException>(failure)
        assertEquals(1, failure.completedCount)
        assertEquals(2, failure.totalCount)
        assertEquals(reconciled, failure.refreshedSummaries)
        assertEquals(1, reloads)
        assertTrue(failure.message.orEmpty().contains("1 of 2 notes"))
    }

    private fun note(
        id: Long,
        title: String,
        category: String,
        readOnly: Boolean = false,
    ) = NextcloudNote(
        id = id,
        title = title,
        modified = id,
        category = category,
        favorite = false,
        readOnly = readOnly,
        content = "# $title",
        etag = "\"etag-$id\"",
        internalPath = "$category/$title.md",
    )
}
