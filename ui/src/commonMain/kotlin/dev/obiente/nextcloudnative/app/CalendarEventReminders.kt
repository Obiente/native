package dev.obiente.nextcloudnative.app

/**
 * A reminder (RFC 5545 VALARM) on an event.
 *
 * Only reminders set relative to the event start, in whole minutes, can be edited here. Others,
 * such as a reminder at a fixed time or one relative to the end, are kept exactly as stored and
 * shown with [description].
 */
internal data class CalendarEventReminder(
    /** Minutes before the start; negative is after the start. Null when not editable here. */
    val minutesBeforeStart: Int?,
    val action: String,
    val description: String,
)

internal val CalendarEventReminder.editable: Boolean get() = minutesBeforeStart != null

/** The reminders of the component this event instance came from. */
internal fun GroupwareCalendarEvent.reminders(): List<CalendarEventReminder> {
    val lines = rawCalendar.unfoldCalendarLines()
    val range = lines.sourceComponentFor(this) ?: return emptyList()
    return lines.icalendarNestedComponents(range, "VALARM").map { alarm -> lines.parseReminder(alarm) }
}

/** The editable reminder offsets, sorted, for the editor and the save postcondition. */
internal fun GroupwareCalendarEvent.reminderMinutes(): List<Int> =
    reminders().mapNotNull(CalendarEventReminder::minutesBeforeStart).sorted()

/**
 * Sets the editable reminders of [range] to [minutes]. A reminder already at one of those offsets
 * is kept as it is, with its action and description; other editable reminders are removed, and
 * missing offsets get a new on-screen reminder. Reminders that cannot be edited are never touched.
 */
internal fun MutableList<String>.replaceCalendarReminders(
    range: ICalendarComponentRange,
    minutes: List<Int>,
    title: String,
): ICalendarComponentRange {
    require(minutes.size <= MAX_CALENDAR_REMINDERS && minutes.all { it in -MAX_REMINDER_MINUTES..MAX_REMINDER_MINUTES }) {
        "The reminders are invalid."
    }
    val wanted = minutes.groupingBy { it }.eachCount().toMutableMap()
    var end = range.end
    icalendarNestedComponents(range, "VALARM").reversed().forEach { alarm ->
        val offset = parseReminder(alarm).minutesBeforeStart ?: return@forEach
        val remaining = wanted[offset] ?: 0
        if (remaining > 0) {
            wanted[offset] = remaining - 1
        } else {
            val count = alarm.end - alarm.begin + 1
            repeat(count) { removeAt(alarm.begin) }
            end -= count
        }
    }
    val description = title.ifBlank { "Reminder" }.escapeCalendarText()
    wanted.entries.sortedBy { it.key }.forEach { (offset, count) ->
        repeat(count) {
            val block = listOf(
                "BEGIN:VALARM",
                "ACTION:DISPLAY",
                "DESCRIPTION:$description",
                "TRIGGER:${offset.reminderTriggerDuration()}",
                "END:VALARM",
            )
            addAll(end, block)
            end += block.size
        }
    }
    return ICalendarComponentRange(range.begin, end)
}

/** The choices offered when adding a reminder, in minutes before the start. */
internal val CalendarReminderPresets = listOf(0, 5, 10, 15, 30, 60, 120, 1_440, 2_880, 10_080)

internal fun calendarReminderLabel(minutesBeforeStart: Int): String {
    val after = minutesBeforeStart < 0
    val minutes = kotlin.math.abs(minutesBeforeStart)
    if (minutes == 0) return "At the start"
    val amount = when {
        minutes % 10_080 == 0 -> plural(minutes / 10_080, "week")
        minutes % 1_440 == 0 -> plural(minutes / 1_440, "day")
        minutes % 60 == 0 -> plural(minutes / 60, "hour")
        else -> plural(minutes, "minute")
    }
    return if (after) "$amount after the start" else "$amount before"
}

private fun plural(count: Int, unit: String): String = if (count == 1) "1 $unit" else "$count ${unit}s"

private fun List<String>.parseReminder(alarm: ICalendarComponentRange): CalendarEventReminder {
    val properties = icalendarDirectProperties(alarm).map { it.second }
    val action = properties.firstOrNull { it.name.equals("ACTION", true) }?.value?.trim()?.uppercase() ?: "DISPLAY"
    val trigger = properties.firstOrNull { it.name.equals("TRIGGER", true) }
    val relativeToStart = trigger != null &&
        trigger.parameter("VALUE")?.equals("DATE-TIME", true) != true &&
        trigger.parameter("RELATED")?.equals("END", true) != true
    val minutes = if (relativeToStart) trigger?.value?.trim()?.parseReminderOffsetMinutes()?.let { -it } else null
    val description = when {
        minutes != null -> calendarReminderLabel(minutes)
        trigger?.parameter("VALUE")?.equals("DATE-TIME", true) == true ->
            "At ${trigger.value.trim().take(8).displayCalendarDate()}"
        trigger?.parameter("RELATED")?.equals("END", true) == true -> "Relative to the end"
        else -> "Other reminder"
    }
    return CalendarEventReminder(minutes, action, description + if (action == "EMAIL") " (email)" else "")
}

/** An RFC 5545 duration in whole minutes, or null when it is not one. */
internal fun String.parseReminderOffsetMinutes(): Int? {
    val match = Regex("^([+-])?P(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?$")
        .matchEntire(trim().uppercase()) ?: return null
    if (trim().endsWith("T", ignoreCase = true) || trim().length <= 2) return null
    fun group(index: Int): Long = match.groupValues[index].takeIf(String::isNotEmpty)?.toLongOrNull() ?: 0L
    val seconds = group(2) * 604_800 + group(3) * 86_400 + group(4) * 3_600 + group(5) * 60 + group(6)
    if (seconds % 60 != 0L || seconds / 60 > MAX_REMINDER_MINUTES) return null
    val minutes = (seconds / 60).toInt()
    return if (match.groupValues[1] == "-") -minutes else minutes
}

internal fun Int.reminderTriggerDuration(): String {
    // A trigger before the start is a negative duration.
    val sign = if (this > 0) "-" else ""
    val minutes = kotlin.math.abs(this)
    return when {
        minutes == 0 -> "PT0S"
        minutes % 10_080 == 0 -> "${sign}P${minutes / 10_080}W"
        minutes % 1_440 == 0 -> "${sign}P${minutes / 1_440}D"
        minutes % 60 == 0 -> "${sign}PT${minutes / 60}H"
        else -> "${sign}PT${minutes}M"
    }
}

/**
 * The component an event instance was read from: its own override, or the series master for an
 * occurrence generated from the recurrence rule.
 */
internal fun List<String>.sourceComponentFor(event: GroupwareCalendarEvent): ICalendarComponentRange? {
    val fallbackUid = event.href.substringAfterLast('/').substringBeforeLast('.')
    return event.recurrenceId?.let { calendarEventComponent(event.uid, it, fallbackUid) }
        ?: calendarEventComponent(event.uid, null, fallbackUid)
}

internal const val MAX_CALENDAR_REMINDERS = 20
private const val MAX_REMINDER_MINUTES = 60 * 24 * 7 * 52
