package dev.obiente.nextcloudnative.app

/**
 * Where events sit on one day of a time grid. All-day and day-spanning events go to the all-day
 * row; timed events are placed by their wall-clock minutes on that date, and overlapping events
 * share the width side by side. Pure, so phone and desktop grids lay out the same way.
 */
internal data class CalendarDayLayout(
    val allDay: List<GroupwareCalendarEvent>,
    val timed: List<CalendarTimedPlacement>,
)

internal data class CalendarTimedPlacement(
    val event: GroupwareCalendarEvent,
    /** Minutes after midnight on this date, clamped to the day. */
    val startMinute: Int,
    val endMinute: Int,
    /** Lane within its overlap group, from 0, and the number of lanes the group needs. */
    val lane: Int,
    val lanes: Int,
)

internal fun calendarDayLayout(date: String, events: List<GroupwareCalendarEvent>): CalendarDayLayout {
    val allDay = mutableListOf<GroupwareCalendarEvent>()
    val timed = mutableListOf<Triple<GroupwareCalendarEvent, Int, Int>>()
    for (event in events) {
        val startDate = event.start.take(8)
        val endDate = event.end?.take(8)?.takeIf { it.length == 8 } ?: startDate
        if (event.allDay || (startDate < date && endDate > date)) {
            allDay += event
            continue
        }
        val start = if (startDate == date) event.start.calendarMinuteOfDay() ?: 0 else 0
        val end = when {
            event.end == null -> start + DefaultEventMinutes
            endDate == date -> event.end.calendarMinuteOfDay() ?: (start + DefaultEventMinutes)
            endDate > date -> MinutesPerDay
            else -> start + DefaultEventMinutes
        }.coerceAtMost(MinutesPerDay)
        // An event ending at midnight of its own start date or earlier still needs a visible block.
        timed += Triple(event, start, if (end <= start) (start + MinimumEventMinutes).coerceAtMost(MinutesPerDay) else end)
    }
    return CalendarDayLayout(allDay, assignCalendarLanes(timed))
}

private fun assignCalendarLanes(events: List<Triple<GroupwareCalendarEvent, Int, Int>>): List<CalendarTimedPlacement> {
    val sorted = events.sortedWith(compareBy<Triple<GroupwareCalendarEvent, Int, Int>> { it.second }
        .thenByDescending { it.third - it.second }.thenBy { it.first.title })
    val result = mutableListOf<CalendarTimedPlacement>()
    var group = mutableListOf<Pair<Triple<GroupwareCalendarEvent, Int, Int>, Int>>()
    var laneEnds = mutableListOf<Int>()
    var groupEnd = -1
    fun closeGroup() {
        val lanes = laneEnds.size.coerceAtLeast(1)
        group.forEach { (event, lane) -> result += CalendarTimedPlacement(event.first, event.second, event.third, lane, lanes) }
        group = mutableListOf()
        laneEnds = mutableListOf()
    }
    for (event in sorted) {
        // Short events still take a minimum visual height, so they overlap by that height.
        val visualEnd = maxOf(event.third, event.second + MinimumEventMinutes)
        if (event.second >= groupEnd) closeGroup()
        val lane = laneEnds.indexOfFirst { it <= event.second }.takeIf { it >= 0 } ?: laneEnds.size.also { laneEnds += 0 }
        laneEnds[lane] = visualEnd
        group += event to lane
        groupEnd = maxOf(groupEnd, visualEnd)
    }
    closeGroup()
    return result
}

/** Minutes after midnight from a compact `YYYYMMDDTHHMMSS` value, or null for a date only. */
internal fun String.calendarMinuteOfDay(): Int? {
    if (length < 13 || getOrNull(8) != 'T') return null
    val hour = substring(9, 11).toIntOrNull()?.takeIf { it in 0..23 } ?: return null
    val minute = substring(11, 13).toIntOrNull()?.takeIf { it in 0..59 } ?: return null
    return hour * 60 + minute
}

/** A compact start value for a new event at [minute] on [date], rounded down to a half hour. */
internal fun calendarSlotStart(date: String, minute: Int): String {
    val rounded = (minute.coerceIn(0, MinutesPerDay - 1) / 30) * 30
    return "${date}T%02d%02d00".format(rounded / 60, rounded % 60)
}

/** The device's local date and minute after midnight, for today's column and its time line. */
internal fun calendarLocalNow(): Pair<String, Int>? =
    deckInstantToLocalDateTime(kotlin.time.Clock.System.now().toString())?.let { local ->
        val hour = local.time.take(2).toIntOrNull() ?: return@let null
        val minute = local.time.drop(3).take(2).toIntOrNull() ?: return@let null
        local.date.isoDateToCompact() to hour * 60 + minute
    }

/** `HH:MM` for minutes after midnight, as event fields show times. */
internal fun calendarClockTime(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)

internal const val MinutesPerDay = 24 * 60
private const val DefaultEventMinutes = 60
internal const val MinimumEventMinutes = 20
