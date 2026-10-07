package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.saveable.Saver

internal sealed interface TasksLoadState {
    data object Loading : TasksLoadState
    data class Ready(
        val calendars: List<GroupwareCalendar>,
        val tasks: List<GroupwareTask>,
        val completedCalendarHrefs: Set<String>,
        val partialFailureMessage: String? = null,
    ) : TasksLoadState {
        val complete: Boolean get() = calendars.all { it.href in completedCalendarHrefs }
        fun canWrite(calendarHref: String): Boolean = calendarHref in completedCalendarHrefs &&
            calendars.any { it.href == calendarHref && it.writable }
        val countSummary: String get() = when {
            complete -> "${tasks.count { !it.completed }} open"
            completedCalendarHrefs.isEmpty() -> "Task lists incomplete"
            else -> "${tasks.count { !it.completed }} open in available tasks"
        }
        val emptyMessage: String get() = if (complete) "No tasks match this view."
            else "Task loading is incomplete. Retry to check the remaining lists."

        fun confirmsSelectionRemoved(selection: GroupwareTaskSelection): Boolean =
            tasks.none { it.instanceId == selection.instanceId } &&
                (selection.calendarHref in completedCalendarHrefs || calendars.none { it.href == selection.calendarHref })
    }
    data class Error(val message: String) : TasksLoadState
}

internal fun groupwareTasksRefreshedState(
    calendars: List<GroupwareCalendar>,
    loaded: GroupwareTaskCalendarLoadResult,
    previous: TasksLoadState.Ready?,
    retentionBudget: GroupwareTaskRetentionBudget = GroupwareTaskRetentionBudget(),
): TasksLoadState.Ready {
    check(calendars.isEmpty() || loaded.failedCalendarHrefs.size < calendars.size) {
        "Task lists could not be refreshed. Retry to load your tasks."
    }
    val canRetainPrevious = loaded.tasks.all { retentionBudget.tryRetain(it.rawCalendar.utf8Size().toLong()) }
    val retained = if (canRetainPrevious) previous?.tasks.orEmpty().filter {
        it.calendarHref in loaded.failedCalendarHrefs && retentionBudget.tryRetain(it.rawCalendar.utf8Size().toLong())
    } else emptyList()
    val message = buildList {
        if (loaded.failedCalendarNames.isNotEmpty()) {
            add("Some task lists could not be refreshed: ${loaded.failedCalendarNames.joinToString()}.")
            if (retained.isNotEmpty()) add("Previously loaded tasks from those lists are shown read-only.")
            else add("Successfully loaded lists remain available.")
        }
        if (loaded.concurrentlyDeletedObjectCount > 0) {
            add("${loaded.concurrentlyDeletedObjectCount} task objects changed during refresh.")
        }
        if (loaded.omittedObjectCount > 0) {
            add("${loaded.omittedObjectCount} task objects were not retained because this refresh " +
                "reached the safe in-memory task-data budget.")
        }
    }.joinToString(" ").takeIf(String::isNotEmpty)
    return TasksLoadState.Ready(
        calendars, (loaded.tasks + retained).sortedWith(compareBy<GroupwareTask> { it.completed }
            .thenBy { it.due ?: "99999999" }.thenBy { it.title.lowercase() }),
        loaded.completedCalendarHrefs, message,
    )
}

internal data class GroupwareTaskSelection(val instanceId: String, val calendarHref: String)

internal fun GroupwareTask.selection(): GroupwareTaskSelection = GroupwareTaskSelection(instanceId, calendarHref)

internal val GroupwareTaskSelectionSaver = Saver<GroupwareTaskSelection?, List<String>>(
    save = { it?.let { selection -> listOf(selection.instanceId, selection.calendarHref) } },
    restore = { values ->
        if (values.size == 2 && values[0].length in 1..8_192 && values[1].length in 1..4_096) {
            GroupwareTaskSelection(values[0], values[1])
        } else null
    },
)
