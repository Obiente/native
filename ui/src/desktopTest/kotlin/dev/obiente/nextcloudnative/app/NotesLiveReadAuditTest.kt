package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NotesLiveReadAuditTest {
    @Test
    fun `live Notes categories form a content-free native hierarchy`() = runBlocking {
        if (System.getenv("RUN_LIVE_NEXTCLOUD_NOTES_AUDIT") != "1") return@runBlocking
        val services = DesktopNextcloudServices()
        val session = assertNotNull(services.loadSession())

        val notes = services.listNotes(session)
        assertTrue(notes.all { note -> note.content == null })
        assertTrue(notes.all { note ->
            runCatching { normalizeNoteCategory(note.category) }.isSuccess
        })
        val root = buildNativeNotesLocation(notes, "")
        assertTrue(root.notes.size + root.folders.sumOf(NativeNoteFolder::descendantNoteCount) == notes.size)
        println(
            "notes-live-audit outcome=success method=get-only count=${notes.size} " +
                "root-folders=${root.folders.size} content=excluded",
        )
    }
}
