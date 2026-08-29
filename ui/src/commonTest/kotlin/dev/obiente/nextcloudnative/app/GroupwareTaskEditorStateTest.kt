package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GroupwareTaskEditorStateTest {
    @Test
    fun `bounded editor saved state preserves the edit-start ETag atomically`() {
        val state = GroupwareTaskEditorState(
            title = "Before",
            dueDate = "2026-08-30",
            description = "Keep the original conditional-write identity",
            completed = false,
            calendarHref = "/remote.php/dav/calendars/person/tasks/",
            editStartEtag = "\"edit-start\"",
        )

        val encoded = requireNotNull(encodeGroupwareTaskEditorStateForSavedState(state))

        assertEquals(state, decodeGroupwareTaskEditorStateFromSavedState(encoded))
    }

    @Test
    fun `large descriptions remain valid in memory but are not copied into saved state`() {
        val state = GroupwareTaskEditorState(
            title = "Large task",
            dueDate = "",
            description = "x".repeat(40 * 1_024),
            completed = false,
            calendarHref = null,
            editStartEtag = null,
        )

        assertEquals(40 * 1_024, state.description.length)
        assertNull(encodeGroupwareTaskEditorStateForSavedState(state))
    }
}
