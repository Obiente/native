package dev.obiente.nextcloudnative.app

import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

internal actual fun calendarConvertWallTime(compact: String, fromZone: String?, toZone: String?): String? {
    val from = calendarZoneOrNull(fromZone) ?: return null
    val to = calendarZoneOrNull(toZone) ?: return null
    return try {
        // A time that falls in a daylight-saving gap moves forward, as calendar apps do.
        LocalDateTime.parse(compact, CALENDAR_WALL_TIME).atZone(from).withZoneSameInstant(to)
            .toLocalDateTime().format(CALENDAR_WALL_TIME)
    } catch (_: DateTimeParseException) {
        null
    } catch (_: DateTimeException) {
        null
    }
}

/** Null means the device zone; an unknown or malformed zone id gives null so callers keep the value. */
private fun calendarZoneOrNull(id: String?): ZoneId? = when (id) {
    null -> ZoneId.systemDefault()
    CALENDAR_UTC_ZONE -> ZoneOffset.UTC
    else -> try {
        ZoneId.of(id)
    } catch (_: DateTimeException) {
        null
    }
}

private val CALENDAR_WALL_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss")
