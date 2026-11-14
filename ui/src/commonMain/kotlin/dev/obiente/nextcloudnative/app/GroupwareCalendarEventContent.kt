package dev.obiente.nextcloudnative.app

fun createGroupwareCalendarEventContent(
    uid: String,
    title: String,
    start: String,
    end: String?,
    allDay: Boolean,
    location: String? = null,
    description: String? = null,
    recurrenceRule: String? = null,
    timeZone: String? = null,
): String {
    require(uid.isNotBlank() && uid.none(Char::isISOControl)) { "The event id is invalid." }
    require(title.isNotBlank()) { "An event title is required." }
    require(start.isCalendarDateValue(allDay)) { "The event start is invalid." }
    require(end == null || end.isCalendarDateValue(allDay)) { "The event end is invalid." }
    recurrenceRule?.let { requireValidCalendarRecurrenceRule(it) }
    val dateParameter = calendarTimeParameter(allDay, timeZone)
    return buildList {
        add("BEGIN:VCALENDAR")
        add("VERSION:2.0")
        add("PRODID:-//Obiente//Nextcloud Native//EN")
        add("BEGIN:VEVENT")
        add("UID:${uid.escapeCalendarText()}")
        add("DTSTART$dateParameter:$start")
        end?.let { add("DTEND$dateParameter:$it") }
        add("SUMMARY:${title.escapeCalendarText()}")
        location?.takeIf(String::isNotBlank)?.let { add("LOCATION:${it.escapeCalendarText()}") }
        description?.takeIf(String::isNotBlank)?.let { add("DESCRIPTION:${it.escapeCalendarText()}") }
        recurrenceRule?.trim()?.takeIf(String::isNotBlank)?.let { add("RRULE:$it") }
        add("END:VEVENT")
        add("END:VCALENDAR")
    }.joinToString("\r\n", postfix = "\r\n")
}

/**
 * Event times are shown and edited in the device's time zone. A stored time ending in `Z` is
 * UTC and one with a `TZID` belongs to that zone; both are converted once, after recurrences are
 * expanded, so every view, detail, and editor agrees. A floating time has no zone and stays as
 * written. All-day events are dates and never move.
 */
fun GroupwareCalendarEvent.inDeviceTime(deviceZone: String? = null): GroupwareCalendarEvent {
    if (allDay) return this
    val localStart = start.calendarDeviceWallTime(startZone, deviceZone)
    val localEnd = end?.calendarDeviceWallTime(endZone ?: startZone, deviceZone)
    return if (localStart == start && localEnd == end) this else copy(start = localStart, end = localEnd)
}

private fun String.calendarDeviceWallTime(zone: String?, deviceZone: String?): String {
    if (length < 15 || getOrNull(8) != 'T') return this
    val utc = endsWith('Z')
    if (!utc && zone == null) return this
    return calendarConvertWallTime(take(15), if (utc) CALENDAR_UTC_ZONE else zone, deviceZone) ?: this
}

/**
 * Turns an editor draft in device time into the values that are stored. An event that has a
 * time zone keeps it, so a repeating event keeps its wall time across daylight-saving changes;
 * anything else is stored in UTC, as before. The draft then records what was written, which is
 * what the save's postcondition compares against.
 */
internal fun EventDraft.inDavFrame(eventZone: String?, deviceZone: String? = null): EventDraft {
    if (allDay) return copy(zone = null, endDate = null)
    val zone = eventZone?.takeIf { calendarTimeParameter(false, it).isNotEmpty() }
    val start = calendarConvertWallTime(wallTime(startTime), deviceZone, zone ?: CALENDAR_UTC_ZONE)
    val end = calendarConvertWallTime(wallTime(endTime), deviceZone, zone ?: CALENDAR_UTC_ZONE)
    if (start == null || end == null) {
        return if (zone != null) inDavFrame(null, deviceZone) else copy(zone = null, endDate = null)
    }
    val startDate = start.take(8).compactDateToIso()
    val endDate = end.take(8).compactDateToIso()
    return copy(
        date = startDate,
        startTime = start.compactTime(),
        endDate = endDate.takeIf { it != startDate },
        endTime = end.compactTime(),
        zone = zone,
    )
}

private fun EventDraft.wallTime(time: String): String = "${date.isoDateToCompact()}T${time.timeToCompact()}00"

/** The parameters for a DTSTART or DTEND line: a date, a zone, or nothing for UTC and floating. */
internal fun calendarTimeParameter(allDay: Boolean, zone: String?): String = when {
    allDay -> ";VALUE=DATE"
    zone != null && zone.isNotBlank() && zone.length <= 128 &&
        zone.none { it == ':' || it == ';' || it == '"' || it == ',' || it.isISOControl() } -> ";TZID=$zone"
    else -> ""
}

/** The zone named by a property declaration such as `DTSTART;TZID=Europe/Paris`, or null. */
internal fun String.calendarTimeZoneId(): String? = split(';').drop(1).firstNotNullOfOrNull { parameter ->
    parameter.takeIf { it.startsWith("TZID=", ignoreCase = true) }?.substring(5)?.trim()?.removeSurrounding("\"")
}?.takeIf(String::isNotBlank)

/**
 * Converts a compact `YYYYMMDDTHHMMSS` wall time between zones: a zone id, [CALENDAR_UTC_ZONE],
 * or null for the device zone. Returns null when either zone is unknown.
 */
internal expect fun calendarConvertWallTime(compact: String, fromZone: String?, toZone: String?): String?

internal const val CALENDAR_UTC_ZONE = "UTC"
