package dev.obiente.nextcloudnative.app

/**
 * One unfolded RFC 5545 / RFC 6350 content line: `NAME;PARAM=value;PARAM="quoted":value`.
 *
 * Parameter values may be quoted and may then contain `;`, `:` and `,`, so a line cannot be split
 * at its first colon. Names and parameter names compare case-insensitively.
 */
internal data class ICalendarContentLine(
    val name: String,
    val parameters: List<Pair<String, String>>,
    val value: String,
) {
    fun parameter(name: String): String? =
        parameters.firstOrNull { (key) -> key.equals(name, ignoreCase = true) }?.second

    fun withParameter(name: String, value: String?): ICalendarContentLine {
        val index = parameters.indexOfFirst { (key) -> key.equals(name, ignoreCase = true) }
        val updated = parameters.toMutableList()
        when {
            value == null && index >= 0 -> updated.removeAt(index)
            value == null -> Unit
            index >= 0 -> updated[index] = parameters[index].first to value
            else -> updated += name.uppercase() to value
        }
        return copy(parameters = updated)
    }

    fun format(): String = buildString {
        append(name)
        parameters.forEach { (key, parameterValue) ->
            append(';').append(key).append('=')
            if (parameterValue.any { it == ':' || it == ';' || it == ',' }) {
                append('"').append(parameterValue).append('"')
            } else {
                append(parameterValue)
            }
        }
        append(':').append(value)
    }
}

internal fun parseICalendarContentLine(line: String): ICalendarContentLine? {
    var index = 0
    while (index < line.length && line[index] != ';' && line[index] != ':') index++
    if (index == 0 || index >= line.length) return null
    val name = line.substring(0, index)
    if (!name.all { it.isLetterOrDigit() || it == '-' || it == '.' }) return null
    val parameters = mutableListOf<Pair<String, String>>()
    while (index < line.length && line[index] == ';') {
        index++
        val keyStart = index
        while (index < line.length && line[index] != '=' && line[index] != ':' && line[index] != ';') index++
        if (index >= line.length || line[index] != '=') return null
        val key = line.substring(keyStart, index)
        if (key.isEmpty()) return null
        index++
        val value = StringBuilder()
        while (index < line.length && line[index] != ';' && line[index] != ':') {
            if (line[index] == '"') {
                val close = line.indexOf('"', index + 1)
                if (close < 0) return null
                value.append(line, index + 1, close)
                index = close + 1
            } else {
                value.append(line[index])
                index++
            }
        }
        parameters += key to value.toString()
    }
    if (index >= line.length || line[index] != ':') return null
    return ICalendarContentLine(name, parameters, line.substring(index + 1))
}

/** A parameter value that can be written back safely: no quotes, no control characters. */
internal fun String.icalendarParameterValue(): String =
    filterNot { it == '"' || it.isISOControl() }.trim().take(MAX_ICALENDAR_PARAMETER_LENGTH)

/** The lines of one component, from its BEGIN line to its END line, including nested components. */
internal data class ICalendarComponentRange(val begin: Int, val end: Int)

/** Every VEVENT (or other component) range in [this] that sits directly in VCALENDAR. */
internal fun List<String>.icalendarComponents(componentName: String): List<ICalendarComponentRange> =
    calendarComponentRanges(componentName).map { ICalendarComponentRange(it.first, it.last) }

/** The direct properties of a component, skipping nested components such as VALARM. */
internal fun List<String>.icalendarDirectProperties(range: ICalendarComponentRange): List<Pair<Int, ICalendarContentLine>> {
    val result = mutableListOf<Pair<Int, ICalendarContentLine>>()
    var depth = 0
    for (index in range.begin + 1 until range.end) {
        val line = this[index]
        when {
            line.startsWith("BEGIN:", ignoreCase = true) -> depth++
            line.startsWith("END:", ignoreCase = true) -> depth = maxOf(0, depth - 1)
            depth == 0 -> parseICalendarContentLine(line)?.let { result += index to it }
        }
    }
    return result
}

/** Nested components named [componentName] directly inside [range], such as the VALARMs of a VEVENT. */
internal fun List<String>.icalendarNestedComponents(
    range: ICalendarComponentRange,
    componentName: String,
): List<ICalendarComponentRange> {
    val result = mutableListOf<ICalendarComponentRange>()
    var depth = 0
    var start = -1
    for (index in range.begin + 1 until range.end) {
        val line = this[index]
        when {
            line.startsWith("BEGIN:", ignoreCase = true) -> {
                if (depth == 0 && line.substring(6).equals(componentName, ignoreCase = true)) start = index
                depth++
            }
            line.startsWith("END:", ignoreCase = true) -> {
                depth = maxOf(0, depth - 1)
                if (depth == 0 && start >= 0) {
                    result += ICalendarComponentRange(start, index)
                    start = -1
                }
            }
        }
    }
    return result
}

/**
 * The VEVENT in [lines] with [uid] and [recurrenceId] (null for the series master), or null.
 * A missing UID falls back to [fallbackUid], matching how events are parsed.
 */
internal fun List<String>.calendarEventComponent(
    uid: String,
    recurrenceId: String?,
    fallbackUid: String? = null,
): ICalendarComponentRange? = icalendarComponents("VEVENT").firstOrNull { range ->
    val properties = icalendarDirectProperties(range).map { it.second }
    val componentUid = properties.firstOrNull { it.name.equals("UID", true) }?.value?.trim() ?: fallbackUid
    val componentRecurrence = properties.firstOrNull { it.name.equals("RECURRENCE-ID", true) }?.value?.trim()
    componentUid == uid && componentRecurrence == recurrenceId
}

internal fun List<String>.joinICalendarLines(): String = joinToString("\r\n", postfix = "\r\n")

private const val MAX_ICALENDAR_PARAMETER_LENGTH = 256
