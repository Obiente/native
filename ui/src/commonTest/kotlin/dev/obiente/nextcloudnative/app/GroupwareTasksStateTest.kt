package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.saveable.SaverScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupwareTasksStateTest {
    private val selectedCalendar = GroupwareCalendar("/remote.php/dav/calendars/person/selected/", "Tasks")
    private val otherCalendar = GroupwareCalendar("/remote.php/dav/calendars/person/other/", "Tasks")
    private val task = GroupwareTask(
        "${selectedCalendar.href}one.ics", "v1", selectedCalendar.href, "one", title = "Task", rawCalendar = "",
    )

    @Test
    fun `own completed calendar clears a missing selection despite unrelated refresh failures`() {
        val ready = ready(setOf(selectedCalendar.href))
        assertTrue(ready.confirmsSelectionRemoved(task.selection()))
    }

    @Test
    fun `own failed or truncated calendar preserves selection`() {
        assertFalse(ready(setOf(otherCalendar.href)).confirmsSelectionRemoved(task.selection()))
        assertFalse(ready(emptySet()).confirmsSelectionRemoved(task.selection()))
    }

    @Test
    fun `existing selection survives successful refresh and disappeared calendar clears it`() {
        assertFalse(ready(setOf(selectedCalendar.href)).copy(tasks = listOf(task)).confirmsSelectionRemoved(task.selection()))
        assertTrue(ready(emptySet()).copy(calendars = listOf(otherCalendar)).confirmsSelectionRemoved(task.selection()))
    }

    @Test
    fun `restored selection retains calendar identity for partial refresh cleanup`() {
        val saved = with(GroupwareTaskSelectionSaver) {
            SaverScope { true }.save(task.selection())
        }
        val restored = GroupwareTaskSelectionSaver.restore(requireNotNull(saved))
        assertEquals(task.selection(), restored)
        assertTrue(ready(setOf(selectedCalendar.href)).confirmsSelectionRemoved(requireNotNull(restored)))
        assertNull(GroupwareTaskSelectionSaver.restore(listOf("x".repeat(8_193), selectedCalendar.href)))
        assertNull(GroupwareTaskSelectionSaver.restore(listOf(task.instanceId)))
    }

    private fun ready(completed: Set<String>) = TasksLoadState.Ready(
        listOf(selectedCalendar, otherCalendar), emptyList(), completed,
        partialFailureMessage = "One task list could not be refreshed.",
    )
}
