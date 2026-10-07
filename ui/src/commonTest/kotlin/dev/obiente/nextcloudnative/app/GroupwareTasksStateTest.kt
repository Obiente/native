package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.saveable.SaverScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupwareTasksStateTest {
    @Test
    fun `all failed lists reject successful empty state even with duplicate display names`() {
        val calendars = listOf(selectedCalendar, otherCalendar)
        val loaded = GroupwareTaskCalendarLoadResult(
            emptyList(), calendars.map { it.displayName }, failedCalendarHrefs = calendars.map { it.href }.toSet(),
        )
        assertFailsWith<IllegalStateException> { groupwareTasksRefreshedState(calendars, loaded, null) }
    }

    @Test
    fun `incomplete refresh never labels unknown lists as empty or writable`() {
        val stale = ready(emptySet()).copy(calendars = listOf(selectedCalendar.copy(writable = true)))
        assertFalse(stale.canWrite(selectedCalendar.href))
        assertFalse(stale.countSummary.contains("0 open"))
        assertFalse(stale.emptyMessage.contains("No tasks match"))
        val complete = stale.copy(completedCalendarHrefs = setOf(selectedCalendar.href))
        assertTrue(complete.canWrite(selectedCalendar.href))
        assertEquals("0 open", complete.countSummary)
        assertEquals("No tasks match this view.", complete.emptyMessage)
    }

    @Test
    fun `partial refresh retains failed list tasks read only and preserves selection`() {
        val calendars = listOf(selectedCalendar.copy(writable = true), otherCalendar.copy(writable = true))
        val loaded = GroupwareTaskCalendarLoadResult(
            emptyList(), listOf(selectedCalendar.displayName), completedCalendarHrefs = setOf(otherCalendar.href),
            failedCalendarHrefs = setOf(selectedCalendar.href),
        )
        val updated = groupwareTasksRefreshedState(calendars, loaded, ready(emptySet()).copy(tasks = listOf(task)))
        assertEquals(listOf(task), updated.tasks)
        assertFalse(updated.canWrite(selectedCalendar.href))
        assertTrue(updated.canWrite(otherCalendar.href))
        assertFalse(updated.confirmsSelectionRemoved(task.selection()))
        assertTrue(updated.partialFailureMessage.orEmpty().contains("Previously loaded"))
    }

    @Test
    fun `retained failed list tasks respect the refresh content budget`() {
        val loaded = GroupwareTaskCalendarLoadResult(
            emptyList(), listOf(selectedCalendar.displayName), completedCalendarHrefs = setOf(otherCalendar.href),
            failedCalendarHrefs = setOf(selectedCalendar.href),
        )
        val previous = ready(emptySet()).copy(tasks = listOf(task.copy(rawCalendar = "oversized")))
        val updated = groupwareTasksRefreshedState(
            previous.calendars, loaded, previous, GroupwareTaskRetentionBudget(1),
        )
        assertTrue(updated.tasks.isEmpty())
        assertFalse(updated.complete)
        assertFalse(updated.confirmsSelectionRemoved(task.selection()))
    }

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
