package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

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
    fun `large input is rejected before it can replace an accepted task draft`() {
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
        assertNull(acceptGroupwareTaskEditorChange(state))
        val previous = state.copy(description = "Original draft")
        val accepted = acceptGroupwareTaskEditorChange(state) ?: previous
        assertEquals(previous, accepted)
        assertEquals(previous, decodeGroupwareTaskEditorStateFromSavedState(
            assertNotNull(encodeGroupwareTaskEditorStateForSavedState(accepted)),
        ))
    }

    @Test
    fun `draft budget includes every field and accepts the exact limit`() {
        val state = GroupwareTaskEditorState("", "", "", false, null, null)
        val overhead = assertNotNull(encodeGroupwareTaskEditorStateForSavedState(state)).sumOf(String::length)
        val atLimit = state.copy(description = "x".repeat(32 * 1_024 - overhead))
        assertEquals(atLimit, acceptGroupwareTaskEditorChange(atLimit))
        assertEquals(atLimit, decodeGroupwareTaskEditorStateFromSavedState(
            assertNotNull(encodeGroupwareTaskEditorStateForSavedState(atLimit)),
        ))
        listOf(
            atLimit.copy(title = "x"), atLimit.copy(dueDate = "x"),
            atLimit.copy(calendarHref = "x"), atLimit.copy(editStartEtag = "x"),
        ).forEach { assertNull(acceptGroupwareTaskEditorChange(it)) }
        assertNull(decodeGroupwareTaskEditorStateFromSavedState(listOf("", "", "x".repeat(40_000), "false", "", "")))
    }

    @Test
    fun `task search query is bounded before entering saved state`() {
        val oversized = "task".repeat(MAX_GROUPWARE_TASK_QUERY_LENGTH)

        val bounded = boundedGroupwareTaskQuery(oversized)

        assertEquals(MAX_GROUPWARE_TASK_QUERY_LENGTH, bounded.length)
        assertEquals(oversized.take(MAX_GROUPWARE_TASK_QUERY_LENGTH), bounded)
    }

    @Test
    fun `restored task draft never falls back to another calendar`() {
        val remaining = GroupwareCalendar("/remote.php/dav/calendars/person/remaining/", "Remaining")

        assertNull(
            selectedGroupwareTaskCalendar(
                calendars = listOf(remaining),
                selectedHref = "/remote.php/dav/calendars/person/deleted/",
            ),
        )
        assertEquals(remaining, selectedGroupwareTaskCalendar(listOf(remaining), remaining.href))
    }
}
