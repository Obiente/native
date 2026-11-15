package dev.obiente.nextcloudnative.app

/** Whether a change to a repeating event applies to one occurrence or to the whole series. */
internal enum class CalendarChangeScope { Occurrence, Series }

/** True when this instance belongs to a repeating series, so a change needs a scope choice. */
internal val GroupwareCalendarEvent.isPartOfSeries: Boolean
    get() = recurrenceRule != null || recurrenceId != null

/**
 * The RECURRENCE-ID value that names this instance in its series: its own for an occurrence or
 * exception, or the stored series start for the first occurrence. Null for a single event.
 */
internal fun GroupwareCalendarEvent.occurrenceId(): String? {
    recurrenceId?.let { return it }
    if (recurrenceRule == null) return null
    val lines = rawCalendar.unfoldCalendarLines()
    val master = lines.calendarEventComponent(uid, null, fallbackUid()) ?: return null
    return lines.icalendarDirectProperties(master).firstOrNull { it.second.name.equals("DTSTART", true) }?.second?.value?.trim()
}

/** The series master as it is shown in the calendar, for editing the whole series from any occurrence. */
internal fun GroupwareCalendarEvent.seriesMaster(): GroupwareCalendarEvent? =
    parseGroupwareCalendarEventsFromContent(calendarHref, href, etag, rawCalendar)
        .firstOrNull { it.uid == uid && it.recurrenceId == null }
        ?.inDeviceTime()

/** This occurrence as the editor should see it: its own times, without the series rule. */
internal fun GroupwareCalendarEvent.asSingleOccurrence(): GroupwareCalendarEvent? =
    occurrenceId()?.let { copy(recurrenceRule = null, recurrenceId = it) }

/** The event the editor opens for [scope]: the series master, this occurrence alone, or the event itself. */
internal fun GroupwareCalendarEvent.editableFor(scope: CalendarChangeScope): GroupwareCalendarEvent? = when {
    !isPartOfSeries -> this
    scope == CalendarChangeScope.Series -> seriesMaster()
    else -> asSingleOccurrence()
}

/**
 * Removes one occurrence from its series: the series gets an EXDATE for it, written like its
 * DTSTART, and any exception stored for that occurrence is removed.
 */
internal fun excludeCalendarOccurrenceContent(event: GroupwareCalendarEvent): String {
    val occurrence = requireNotNull(event.occurrenceId()) { "This event does not repeat." }
    val lines = event.rawCalendar.unfoldCalendarLines().toMutableList()
    lines.calendarEventComponent(event.uid, occurrence, event.fallbackUid())?.let { exception ->
        repeat(exception.end - exception.begin + 1) { lines.removeAt(exception.begin) }
    }
    val master = requireNotNull(lines.calendarEventComponent(event.uid, null, event.fallbackUid())) {
        "The repeating event could not be found."
    }
    val start = requireNotNull(lines.icalendarDirectProperties(master).firstOrNull { it.second.name.equals("DTSTART", true) }) {
        "The repeating event has no start."
    }.second
    require(occurrence.isCalendarOccurrenceValue()) { "The occurrence is invalid." }
    lines.add(master.end, ICalendarContentLine("EXDATE", start.parameters, occurrence).format())
    return lines.joinICalendarLines()
}

/**
 * Applies an editor draft. A series edit changes the stored component; an occurrence edit
 * changes that occurrence's exception, creating one from the series when it has none yet.
 */
internal fun applyCalendarEventDraftContent(
    event: GroupwareCalendarEvent,
    draft: EventDraft,
    scope: CalendarChangeScope,
    identity: CalendarUserIdentity?,
): String {
    val target = if (scope == CalendarChangeScope.Occurrence && event.isPartOfSeries) {
        val occurrence = requireNotNull(event.occurrenceId()) { "The occurrence could not be found." }
        val content = event.rawCalendar.ensureCalendarException(event, occurrence)
        event.copy(rawCalendar = content, recurrenceId = occurrence)
    } else {
        event
    }
    val updated = updateGroupwareCalendarEventContent(
        event = target,
        title = draft.title,
        start = draft.startValue(),
        end = draft.endValue(),
        allDay = draft.allDay,
        location = draft.location,
        description = draft.description,
        recurrenceRule = if (scope == CalendarChangeScope.Occurrence) null else draft.recurrenceRule,
        timeZone = draft.zone,
    )
    return applyCalendarEventExtras(updated, target.uid, target.recurrenceId, target.fallbackUid(), draft, identity)
}

/** Writes the reminders and guests of a draft into one component of [content]. */
internal fun applyCalendarEventExtras(
    content: String,
    uid: String,
    recurrenceId: String?,
    fallbackUid: String?,
    draft: EventDraft,
    identity: CalendarUserIdentity?,
): String {
    val lines = content.unfoldCalendarLines().toMutableList()
    var range = requireNotNull(lines.calendarEventComponent(uid, recurrenceId, fallbackUid)) {
        "The selected calendar event component could not be found."
    }
    val wasMeeting = lines.hasCalendarAttendees(range)
    draft.reminderMinutes?.let { range = lines.replaceCalendarReminders(range, it, draft.title) }
    draft.attendees?.let { addresses ->
        range = lines.replaceCalendarAttendees(range, addresses, requireNotNull(identity) { "Your calendar identity is unknown." })
    }
    // Any change the organizer saves to a meeting is a new version for the people invited to it.
    // An invited person's own copy never gets ahead of the organizer's.
    val organizer = lines.icalendarDirectProperties(range).firstOrNull { it.second.name.equals("ORGANIZER", true) }?.second
    val ownMeeting = organizer == null || identity?.owns(organizer.calendarAddress()) == true
    if (ownMeeting && (wasMeeting || lines.hasCalendarAttendees(range))) lines.bumpCalendarSequence(range)
    return lines.joinICalendarLines()
}

/** Raises SEQUENCE so invited people's calendars take the new version of a meeting. */
internal fun MutableList<String>.bumpCalendarSequence(range: ICalendarComponentRange): ICalendarComponentRange {
    val existing = icalendarDirectProperties(range).firstOrNull { it.second.name.equals("SEQUENCE", true) }
    val next = (existing?.second?.value?.trim()?.toIntOrNull()?.coerceAtLeast(0) ?: 0) + 1
    return if (existing != null) {
        this[existing.first] = "SEQUENCE:$next"
        range
    } else {
        add(range.end, "SEQUENCE:$next")
        ICalendarComponentRange(range.begin, range.end + 1)
    }
}

private fun List<String>.hasCalendarAttendees(range: ICalendarComponentRange): Boolean =
    icalendarDirectProperties(range).any { it.second.name.equals("ATTENDEE", true) }

/** Adds an exception for [occurrence] copied from the series master unless one exists. */
private fun String.ensureCalendarException(event: GroupwareCalendarEvent, occurrence: String): String {
    require(occurrence.isCalendarOccurrenceValue()) { "The occurrence is invalid." }
    val lines = unfoldCalendarLines().toMutableList()
    if (lines.calendarEventComponent(event.uid, occurrence, event.fallbackUid()) != null) return this
    val master = requireNotNull(lines.calendarEventComponent(event.uid, null, event.fallbackUid())) {
        "The repeating event could not be found."
    }
    val properties = lines.icalendarDirectProperties(master)
    val start = requireNotNull(properties.firstOrNull { it.second.name.equals("DTSTART", true) }) {
        "The repeating event has no start."
    }.second
    val seriesOnly = properties.filter { (_, line) -> line.name.uppercase() in SERIES_ONLY_PROPERTIES }.map { it.first }.toSet()
    val copy = (master.begin..master.end).filterNot { it in seriesOnly }.map { lines[it] }.toMutableList()
    // RECURRENCE-ID is written like the series DTSTART, so it names exactly this occurrence.
    copy.add(1, ICalendarContentLine("RECURRENCE-ID", start.parameters, occurrence).format())
    lines.addAll(master.end + 1, copy)
    return lines.joinICalendarLines()
}

private fun GroupwareCalendarEvent.fallbackUid(): String = href.substringAfterLast('/').substringBeforeLast('.')

private fun String.isCalendarOccurrenceValue(): Boolean =
    length in 8..20 && take(8).all(Char::isDigit) && none { it == ':' || it == ';' || it == ',' || it.isISOControl() }

private val SERIES_ONLY_PROPERTIES = setOf("RRULE", "RDATE", "EXDATE", "EXRULE", "RECURRENCE-ID")
