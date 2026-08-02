package dev.obiente.nextcloudnative.app

internal enum class CalendarWorkspaceView {
    Month,
    Week,
    Agenda,
}

internal data class CalendarWorkspacePresentation(
    val visibleEvents: List<GroupwareCalendarEvent>,
    val eventsByDate: Map<String, List<GroupwareCalendarEvent>>,
    val selectedDateEvents: List<GroupwareCalendarEvent>,
    val eventCountByCalendar: Map<String, Int>,
    val weekDates: List<String>,
)

internal fun buildCalendarWorkspacePresentation(
    events: List<GroupwareCalendarEvent>,
    calendars: List<GroupwareCalendar>,
    hiddenCalendarHrefs: Set<String>,
    query: String,
    selectedDate: String,
): CalendarWorkspacePresentation {
    val normalizedQuery = query.trim().lowercase()
    val knownCalendars = calendars.mapTo(hashSetOf(), GroupwareCalendar::href)
    val visible = events.asSequence()
        .filter { event -> event.calendarHref in knownCalendars && event.calendarHref !in hiddenCalendarHrefs }
        .filter { event ->
            normalizedQuery.isEmpty() || listOfNotNull(event.title, event.location, event.description)
                .any { value -> normalizedQuery in value.lowercase() }
        }
        .sortedWith(compareBy(GroupwareCalendarEvent::start, GroupwareCalendarEvent::title))
        .toList()
    val byDate = visible.groupBy { event -> event.start.take(8) }
    return CalendarWorkspacePresentation(
        visibleEvents = visible,
        eventsByDate = byDate,
        selectedDateEvents = byDate[selectedDate].orEmpty(),
        eventCountByCalendar = events.filter { it.calendarHref in knownCalendars }
            .groupingBy(GroupwareCalendarEvent::calendarHref)
            .eachCount(),
        weekDates = calendarWeekDates(selectedDate),
    )
}

internal fun calendarWeekDates(selectedDate: String): List<String> {
    val parsed = selectedDate.parseCompactCalendarDate() ?: return emptyList()
    val monday = parsed.plusDays(-dayOfWeekMondayFirst(parsed.year, parsed.month, parsed.day))
    return (0..6).map { offset -> monday.plusDays(offset).compactValue }
}

internal data class CalendarWorkspaceDate(
    val year: Int,
    val month: Int,
    val day: Int,
) {
    init {
        require(year in 1..9999)
        require(month in 1..12)
        require(day in 1..groupwareCalendarDaysInMonth(year, month))
    }

    val compactValue: String get() = "%04d%02d%02d".format(year, month, day)

    fun plusDays(delta: Int): CalendarWorkspaceDate {
        var result = this
        if (delta >= 0) {
            repeat(delta) { result = result.next() }
        } else {
            repeat(-delta) { result = result.previous() }
        }
        return result
    }

    private fun next(): CalendarWorkspaceDate {
        if (day < groupwareCalendarDaysInMonth(year, month)) return copy(day = day + 1)
        if (month < 12) return CalendarWorkspaceDate(year, month + 1, 1)
        return CalendarWorkspaceDate(year + 1, 1, 1)
    }

    private fun previous(): CalendarWorkspaceDate {
        if (day > 1) return copy(day = day - 1)
        if (month > 1) {
            val previousMonth = month - 1
            return CalendarWorkspaceDate(year, previousMonth, groupwareCalendarDaysInMonth(year, previousMonth))
        }
        return CalendarWorkspaceDate(year - 1, 12, 31)
    }
}

internal fun String.parseCompactCalendarDate(): CalendarWorkspaceDate? {
    if (length != 8 || any { character -> !character.isDigit() }) return null
    val year = take(4).toIntOrNull() ?: return null
    val month = substring(4, 6).toIntOrNull() ?: return null
    val day = takeLast(2).toIntOrNull() ?: return null
    return runCatching { CalendarWorkspaceDate(year, month, day) }.getOrNull()
}

internal fun dayOfWeekMondayFirst(year: Int, month: Int, day: Int): Int {
    var adjustedYear = year
    var adjustedMonth = month
    if (adjustedMonth < 3) {
        adjustedMonth += 12
        adjustedYear -= 1
    }
    val yearOfCentury = adjustedYear % 100
    val century = adjustedYear / 100
    val zeller = (day + (13 * (adjustedMonth + 1)) / 5 + yearOfCentury +
        yearOfCentury / 4 + century / 4 + 5 * century) % 7
    val sundayFirst = (zeller + 6) % 7
    return (sundayFirst + 6) % 7
}
