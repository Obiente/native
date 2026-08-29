package dev.obiente.nextcloudnative.app

data class GroupwareTask(
    val href: String,
    val etag: String?,
    val calendarHref: String,
    val uid: String,
    val title: String,
    val due: String? = null,
    val dueAllDay: Boolean = false,
    val completed: Boolean = false,
    val description: String? = null,
    val priority: Int? = null,
    val rawCalendar: String,
)

fun parseGroupwareTasks(calendarHref: String, response: NextcloudApiResponse): List<GroupwareTask> {
    require(response.status in 200..299) { "Task loading failed (HTTP ${response.status})." }
    return response.body.decodeToString().xmlElements("response").flatMap { block ->
        val href = block.xmlText("href")?.decodeXmlEntities()?.trim()?.requireSafeDavHref()
            ?: return@flatMap emptyList()
        val calendar = block.xmlText("calendar-data")?.decodeXmlEntities() ?: return@flatMap emptyList()
        parseGroupwareTasksFromContent(
            calendarHref, href, block.xmlText("getetag")?.decodeXmlEntities()?.trim(), calendar,
        )
    }
}

fun parseGroupwareTask(
    calendarHref: String,
    href: String,
    etag: String?,
    content: String,
): GroupwareTask? = parseGroupwareTasksFromContent(calendarHref, href, etag, content).firstOrNull()

private fun parseGroupwareTasksFromContent(
    calendarHref: String,
    href: String,
    etag: String?,
    content: String,
): List<GroupwareTask> = content.unfoldCalendarLines().calendarComponentLines("VTODO").mapNotNull { lines ->
    fun property(name: String): CalendarProperty? = lines.firstNotNullOfOrNull { line ->
        val separator = line.indexOf(':')
        if (separator <= 0) return@firstNotNullOfOrNull null
        val declaration = line.substring(0, separator)
        if (!declaration.substringBefore(';').equals(name, ignoreCase = true)) {
            return@firstNotNullOfOrNull null
        }
        CalendarProperty(declaration, line.substring(separator + 1))
    }
    val uid = property("UID")?.value?.trim()?.takeIf(String::isNotBlank)
        ?: href.substringAfterLast('/').substringBeforeLast('.')
    val due = property("DUE")
    val status = property("STATUS")?.value?.trim()
    val percentComplete = property("PERCENT-COMPLETE")?.value?.trim()?.toIntOrNull()
    GroupwareTask(
        href = href.requireSafeDavHref(),
        etag = etag,
        calendarHref = calendarHref.requireSafeDavHref(),
        uid = uid,
        title = property("SUMMARY")?.value?.decodeCalendarText()?.ifBlank { "Untitled task" }
            ?: "Untitled task",
        due = due?.value?.trim()?.takeIf(String::isNotBlank),
        dueAllDay = due?.declaration?.contains("VALUE=DATE", ignoreCase = true) == true ||
            due?.value?.let { value -> value.length == 8 && value.all(Char::isDigit) } == true,
        completed = status.equals("COMPLETED", ignoreCase = true) || percentComplete == 100,
        description = property("DESCRIPTION")?.value?.decodeCalendarText()?.takeIf(String::isNotBlank),
        priority = property("PRIORITY")?.value?.trim()?.toIntOrNull()?.takeIf { it in 0..9 },
        rawCalendar = content,
    )
}

fun createGroupwareTaskContent(
    uid: String,
    title: String,
    dueDate: String?,
    completed: Boolean,
    description: String? = null,
): String {
    require(uid.isNotBlank() && uid.none(Char::isISOControl)) { "The task id is invalid." }
    require(title.isNotBlank()) { "A task title is required." }
    val due = dueDate?.takeIf(String::isNotBlank)?.also(::requireValidGroupwareTaskDueDate)
    return buildList {
        add("BEGIN:VCALENDAR")
        add("VERSION:2.0")
        add("PRODID:-//Obiente//Nextcloud Native//EN")
        add("BEGIN:VTODO")
        add("UID:${uid.escapeCalendarText()}")
        add("SUMMARY:${title.escapeCalendarText()}")
        due?.let { add("DUE;VALUE=DATE:$it") }
        add("STATUS:${if (completed) "COMPLETED" else "NEEDS-ACTION"}")
        add("PERCENT-COMPLETE:${if (completed) 100 else 0}")
        description?.takeIf(String::isNotBlank)?.let { add("DESCRIPTION:${it.escapeCalendarText()}") }
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
): String {
    require(title.isNotBlank()) { "A task title is required." }
    val due = dueDate?.takeIf(String::isNotBlank)?.also(::requireValidGroupwareTaskDueDate)
    val original = task.rawCalendar.unfoldCalendarLines().toMutableList()
    val taskRange = original.calendarComponentRanges("VTODO").firstOrNull { range ->
        original.subList(range.first + 1, range.last).calendarPropertyValue("UID") == task.uid
    }
    requireNotNull(taskRange) { "The selected task component could not be found." }
    val taskStart = taskRange.first
    var taskEnd = taskRange.last
    val replacements = linkedMapOf(
        "SUMMARY" to "SUMMARY:${title.escapeCalendarText()}",
        "DUE" to due?.let { "DUE;VALUE=DATE:$it" },
        "STATUS" to "STATUS:${if (completed) "COMPLETED" else "NEEDS-ACTION"}",
        "PERCENT-COMPLETE" to "PERCENT-COMPLETE:${if (completed) 100 else 0}",
        "COMPLETED" to null,
        "DESCRIPTION" to description?.takeIf(String::isNotBlank)?.let {
            "DESCRIPTION:${it.escapeCalendarText()}"
        },
    )
    replacements.forEach { (name, replacement) ->
        val index = (taskStart + 1 until taskEnd).firstOrNull { lineIndex ->
            original[lineIndex].substringBefore(':').substringBefore(';').equals(name, true)
        }
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

private fun requireValidGroupwareTaskDueDate(value: String) {
    require(value.length == 8 && value.all(Char::isDigit)) { "The task due date is invalid." }
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
