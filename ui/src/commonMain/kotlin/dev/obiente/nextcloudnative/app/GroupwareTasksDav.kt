package dev.obiente.nextcloudnative.app

import kotlin.time.Clock

data class GroupwareTask(
    val href: String,
    val etag: String?,
    val calendarHref: String,
    val uid: String,
    val recurrenceId: String? = null,
    val title: String,
    val due: String? = null,
    val dueAllDay: Boolean = false,
    val completed: Boolean = false,
    val completedAt: String? = null,
    val description: String? = null,
    val priority: Int? = null,
    val rawCalendar: String,
) {
    /** Stable across refreshes when one DAV object contains a recurring master and exceptions. */
    val instanceId: String
        get() = "$href#${uid.length}:$uid#${recurrenceId?.let { "exception:$it" } ?: "master"}"
}

fun parseGroupwareTasks(calendarHref: String, response: NextcloudApiResponse): List<GroupwareTask> {
    require(response.status in 200..299) { "Task loading failed (HTTP ${response.status})." }
    return response.body.decodeToString().xmlElements("response").flatMap { block ->
        val href = block.xmlText("href")?.decodeXmlEntities()?.trim()?.requireSafeDavHref()
            ?: return@flatMap emptyList()
        val calendar = block.xmlText("calendar-data")?.decodeXmlEntities() ?: return@flatMap emptyList()
        parseGroupwareTasksFromContent(
            calendarHref, href, block.xmlText("getetag")?.decodeXmlEntities()?.trim(), calendar,
        )
    }.requireUniqueTaskIdentities()
}

fun parseGroupwareTask(
    calendarHref: String,
    href: String,
    etag: String?,
    content: String,
): GroupwareTask? = parseGroupwareTasksFromContent(calendarHref, href, etag, content).firstOrNull()

internal fun parseGroupwareTasksFromContent(
    calendarHref: String,
    href: String,
    etag: String?,
    content: String,
): List<GroupwareTask> = content.unfoldCalendarLines().calendarComponentLines("VTODO").mapNotNull { lines ->
    fun property(name: String): CalendarProperty? = lines.calendarProperty(name)
    val identity = lines.groupwareTaskIdentity() ?: return@mapNotNull null
    val due = property("DUE")
    val status = property("STATUS")?.value?.trim()
    val percentComplete = property("PERCENT-COMPLETE")?.value?.trim()?.toIntOrNull()
    GroupwareTask(
        href = href.requireSafeDavHref(),
        etag = etag,
        calendarHref = calendarHref.requireSafeDavHref(),
        uid = identity.uid,
        recurrenceId = identity.recurrenceId,
        title = property("SUMMARY")?.value?.decodeCalendarText()?.ifBlank { "Untitled task" }
            ?: "Untitled task",
        due = due?.value?.trim()?.takeIf(String::isNotBlank),
        dueAllDay = due?.declaration?.contains("VALUE=DATE", ignoreCase = true) == true ||
            due?.value?.let { value -> value.length == 8 && value.all(Char::isDigit) } == true,
        completed = status.equals("COMPLETED", ignoreCase = true) || percentComplete == 100,
        completedAt = property("COMPLETED")?.value?.trim()?.takeIf(String::isNotBlank),
        description = property("DESCRIPTION")?.value?.decodeCalendarText()?.takeIf(String::isNotEmpty),
        priority = property("PRIORITY")?.value?.trim()?.toIntOrNull()?.takeIf { it in 0..9 },
        rawCalendar = content,
    )
}.requireUniqueTaskIdentities()

private fun List<GroupwareTask>.requireUniqueTaskIdentities(): List<GroupwareTask> = also { tasks ->
    require(tasks.map(GroupwareTask::instanceId).toSet().size == tasks.size) {
        "The calendar response contains duplicate task identities."
    }
}

internal fun isSafeGroupwareTaskUid(value: String): Boolean =
    value.isNotBlank() && value.length <= 1_024 && value.none(Char::isISOControl)

private data class GroupwareTaskComponentIdentity(val uid: String, val recurrenceId: String?)

private fun List<String>.groupwareTaskIdentity(): GroupwareTaskComponentIdentity? {
    val uid = calendarProperty("UID")?.value?.takeIf(::isSafeGroupwareTaskUid)?.trim() ?: return null
    val recurrenceId = calendarProperty("RECURRENCE-ID")?.let { recurrence ->
        parseGroupwareTaskRecurrenceId(recurrence.value) ?: return null
    }
    return GroupwareTaskComponentIdentity(uid, recurrenceId)
}

private fun parseGroupwareTaskRecurrenceId(value: String): String? {
    // RFC 5545 recurrence values are DATE or DATE-TIME, not arbitrary saved-state text.
    if (value.length != 8 && value.length != 15 && value.length != 16) return null
    val date = value.take(8)
    if (!date.all { it in '0'..'9' } || !isValidGroupwareTaskDueDate(date)) return null
    if (value.length == 8) return value
    if (!value[8].equals('T', ignoreCase = true)) return null
    if (value.length == 16 && !value.last().equals('Z', ignoreCase = true)) return null
    val time = value.substring(9, 15)
    if (!time.all { it in '0'..'9' }) return null
    if (time.take(2).toInt() > 23 || time.substring(2, 4).toInt() > 59 || time.takeLast(2).toInt() > 60) {
        return null
    }
    return value
}

fun createGroupwareTaskContent(
    uid: String,
    title: String,
    dueDate: String?,
    completed: Boolean,
    description: String? = null,
    dtstamp: String = currentGroupwareTaskCompletionTimestamp(),
): String {
    require(isSafeGroupwareTaskUid(uid)) { "The task id is invalid." }
    require(title.isNotBlank()) { "A task title is required." }
    val due = dueDate?.takeIf(String::isNotBlank)?.also(::requireValidGroupwareTaskDueDate)
    return buildList {
        add("BEGIN:VCALENDAR")
        add("VERSION:2.0")
        add("PRODID:-//Obiente//Nextcloud Native//EN")
        add("BEGIN:VTODO")
        add("UID:${uid.escapeCalendarText()}")
        add("DTSTAMP:${dtstamp.also(::requireValidGroupwareTaskCompletionTimestamp)}")
        add("SUMMARY:${title.escapeCalendarText()}")
        due?.let { add("DUE;VALUE=DATE:$it") }
        add("STATUS:${if (completed) "COMPLETED" else "NEEDS-ACTION"}")
        add("PERCENT-COMPLETE:${if (completed) 100 else 0}")
        description?.takeIf(String::isNotEmpty)?.let { add("DESCRIPTION:${it.escapeCalendarText()}") }
        add("END:VTODO")
        add("END:VCALENDAR")
    }.joinToString("\r\n", postfix = "\r\n")
}

fun updateGroupwareTaskContent(
    task: GroupwareTask,
    title: String,
    dueDate: String?,
    completed: Boolean,
    description: String?,
    completionTimestamp: String = currentGroupwareTaskCompletionTimestamp(),
): String {
    require(title.isNotBlank()) { "A task title is required." }
    val due = dueDate?.takeIf(String::isNotBlank)?.also(::requireValidGroupwareTaskDueDate)
    val original = task.rawCalendar.unfoldCalendarLines().toMutableList()
    val selectedIdentity = GroupwareTaskComponentIdentity(task.uid, task.recurrenceId)
    val taskRange = original.calendarComponentRanges("VTODO").singleOrNull { range ->
        val component = original.subList(range.first + 1, range.last)
        component.groupwareTaskIdentity() == selectedIdentity
    }
    requireNotNull(taskRange) { "The selected task component is missing or ambiguous." }
    val taskStart = taskRange.first
    var taskEnd = taskRange.last
    val completionChanged = completed != task.completed
    val replacements = linkedMapOf<String, String?>(
        "SUMMARY" to "SUMMARY:${title.escapeCalendarText()}",
        "DESCRIPTION" to description?.takeIf(String::isNotEmpty)?.let {
            "DESCRIPTION:${it.escapeCalendarText()}"
        },
    )
    if (completionChanged) {
        replacements["STATUS"] = "STATUS:${if (completed) "COMPLETED" else "NEEDS-ACTION"}"
        replacements["PERCENT-COMPLETE"] = "PERCENT-COMPLETE:${if (completed) 100 else 0}"
        replacements["COMPLETED"] = if (completed) {
            "COMPLETED:${completionTimestamp.also(::requireValidGroupwareTaskCompletionTimestamp)}"
        } else {
            null
        }
    }
    val existingDueIndex = original.directCalendarPropertyIndex(taskStart, taskEnd, "DUE")
    replacements["DUE"] = due?.let { date ->
        existingDueIndex
            ?.let(original::get)
            ?.preserveGroupwareTaskDueTime(task, date)
            ?: "DUE;VALUE=DATE:$date"
    }
    replacements.forEach { (name, replacement) ->
        val index = original.directCalendarPropertyIndex(taskStart, taskEnd, name)
        when {
            index != null && replacement != null -> original[index] = replacement
            index != null -> {
                original.removeAt(index)
                taskEnd -= 1
            }
            replacement != null -> {
                original.add(taskEnd, replacement)
                taskEnd += 1
            }
        }
    }
    return original.joinToString("\r\n", postfix = "\r\n")
}

internal fun expectedGroupwareTaskDueAfterDateEdit(task: GroupwareTask?, dueDate: String?): String? {
    if (dueDate == null || task == null || task.dueAllDay) return dueDate
    val existingDue = task.due ?: return dueDate
    return if (existingDue.hasGroupwareTaskDatePrefix()) {
        dueDate + existingDue.drop(8)
    } else {
        dueDate
    }
}

internal fun isGroupwareTaskObjectDeleteSafe(task: GroupwareTask): Boolean {
    val components = mutableListOf<String>()
    var calendars = 0
    var todos = 0
    task.rawCalendar.unfoldCalendarLines().forEach { line ->
        val declaration = line.substringBefore(':').uppercase()
        val component = line.substringAfter(':', "").uppercase()
        when (declaration) {
            "BEGIN" -> {
                if (component.isBlank()) return false
                when {
                    components.isEmpty() -> if (component != "VCALENDAR" || ++calendars != 1) return false
                    components.size == 1 -> when (component) {
                        "VTODO" -> if (++todos != 1) return false
                        "VTIMEZONE" -> Unit
                        else -> return false
                    }
                    else -> when (components.last()) {
                        "VTODO" -> if (component != "VALARM") return false
                        "VTIMEZONE" -> if (component !in setOf("STANDARD", "DAYLIGHT")) return false
                        else -> return false
                    }
                }
                components += component
            }
            "END" -> {
                if (components.lastOrNull() != component) return false
                components.removeAt(components.lastIndex)
            }
            else -> if (components.isEmpty()) return false
        }
    }
    return components.isEmpty() && calendars == 1 && todos == 1
}

private fun String.preserveGroupwareTaskDueTime(task: GroupwareTask, dueDate: String): String? {
    if (task.dueAllDay) return null
    val separator = indexOf(':')
    if (separator <= 0) return null
    val declaration = substring(0, separator)
    if (!declaration.substringBefore(';').equals("DUE", ignoreCase = true)) return null
    val value = substring(separator + 1)
    if (!value.hasGroupwareTaskDatePrefix()) return null
    return "$declaration:$dueDate${value.drop(8)}"
}

private fun String.hasGroupwareTaskDatePrefix(): Boolean =
    length > 8 && take(8).all(Char::isDigit) && this[8] == 'T'

internal fun isValidGroupwareTaskDueDate(value: String): Boolean {
    if (value.length != 8 || !value.all(Char::isDigit)) return false
    val year = value.take(4).toIntOrNull() ?: return false
    val month = value.substring(4, 6).toIntOrNull() ?: return false
    val day = value.takeLast(2).toIntOrNull() ?: return false
    if (year !in 1..9999 || month !in 1..12) return false
    val days = when (month) {
        2 -> if (year % 400 == 0 || year % 4 == 0 && year % 100 != 0) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }
    return day in 1..days
}

private fun requireValidGroupwareTaskDueDate(value: String) {
    require(isValidGroupwareTaskDueDate(value)) { "The task due date is invalid." }
}

private fun requireValidGroupwareTaskCompletionTimestamp(value: String) {
    require(
        value.length == 16 && value[8] == 'T' && value.last() == 'Z' &&
            value.take(8).all(Char::isDigit) && value.substring(9, 15).all(Char::isDigit),
    ) { "The task completion timestamp is invalid." }
}

private fun currentGroupwareTaskCompletionTimestamp(): String {
    val instant = Clock.System.now().toString()
    return buildString(16) {
        append(instant.substring(0, 4))
        append(instant.substring(5, 7))
        append(instant.substring(8, 10))
        append('T')
        append(instant.substring(11, 13))
        append(instant.substring(14, 16))
        append(instant.substring(17, 19))
        append('Z')
    }
}

internal fun List<String>.calendarComponentLines(componentName: String): List<List<String>> {
    val result = mutableListOf<List<String>>()
    var start = -1
    forEachIndexed { index, line ->
        when {
            line.equals("BEGIN:$componentName", ignoreCase = true) -> start = index + 1
            line.equals("END:$componentName", ignoreCase = true) && start >= 0 -> {
                result += subList(start, index)
                start = -1
            }
        }
    }
    return result
}
