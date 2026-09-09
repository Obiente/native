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
        fun confirmsSelectionRemoved(selection: GroupwareTaskSelection): Boolean =
            tasks.none { it.instanceId == selection.instanceId } &&
                (selection.calendarHref in completedCalendarHrefs || calendars.none { it.href == selection.calendarHref })
    }
    data class Error(val message: String) : TasksLoadState
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
