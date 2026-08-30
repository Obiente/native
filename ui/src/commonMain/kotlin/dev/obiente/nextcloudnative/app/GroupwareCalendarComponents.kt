package dev.obiente.nextcloudnative.app

internal fun List<String>.calendarComponentRanges(componentName: String): List<IntRange> {
    val result = mutableListOf<IntRange>()
    var start = -1
    forEachIndexed { index, line ->
        when {
            line.equals("BEGIN:$componentName", ignoreCase = true) -> start = index
            line.equals("END:$componentName", ignoreCase = true) && start >= 0 -> {
                result += start..index
                start = -1
            }
        }
    }
    return result
}

internal fun List<String>.calendarPropertyValue(name: String): String? =
    calendarProperty(name)?.value?.trim()?.takeIf(String::isNotBlank)

internal fun List<String>.calendarProperty(name: String): CalendarProperty? {
    var nestedDepth = 0
    for (line in this) {
        when {
            line.startsWith("BEGIN:", ignoreCase = true) -> nestedDepth += 1
            line.startsWith("END:", ignoreCase = true) -> nestedDepth = maxOf(0, nestedDepth - 1)
            nestedDepth == 0 -> {
                val separator = line.indexOf(':')
                if (
                    separator > 0 &&
                    line.substring(0, separator).substringBefore(';').equals(name, ignoreCase = true)
                ) {
                    return CalendarProperty(line.substring(0, separator), line.substring(separator + 1))
                }
            }
        }
    }
    return null
}

internal fun List<String>.directCalendarPropertyIndex(
    componentStart: Int,
    componentEnd: Int,
    name: String,
): Int? {
    var nestedDepth = 0
    for (index in componentStart + 1 until componentEnd) {
        val line = this[index]
        when {
            line.startsWith("BEGIN:", ignoreCase = true) -> nestedDepth += 1
            line.startsWith("END:", ignoreCase = true) -> nestedDepth = maxOf(0, nestedDepth - 1)
            nestedDepth == 0 &&
                line.substringBefore(':').substringBefore(';').equals(name, ignoreCase = true) -> return index
        }
    }
    return null
}
