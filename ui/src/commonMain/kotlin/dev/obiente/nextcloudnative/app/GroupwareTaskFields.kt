package dev.obiente.nextcloudnative.app

import kotlinx.serialization.Serializable

/**
 * Task fields beyond title, due date, notes and completion (RFC 5545 VTODO): priority, progress,
 * start date, categories, and the parent task (RELATED-TO with RELTYPE=PARENT, which Nextcloud
 * Tasks uses for subtasks). Kept in the recovery record; null there means the save did not touch them.
 */
@Serializable
internal data class TaskExtras(
    /** 0 for none, 1 (highest) to 9 (lowest). */
    val priority: Int = 0,
    val percentComplete: Int = 0,
    /** `YYYY-MM-DD` or empty. */
    val startDate: String = "",
    val categories: List<String> = emptyList(),
    /** The UID of the parent task, or empty for a top-level task. */
    val parentUid: String = "",
) {
    fun normalized(): TaskExtras = copy(
        startDate = startDate.trim(),
        categories = categories.map(String::trim).filter(String::isNotEmpty).distinct(),
        parentUid = parentUid.trim(),
    )

    fun validationMessage(): String? = when {
        priority !in 0..9 -> "Choose a priority."
        percentComplete !in 0..100 -> "Progress must be between 0 and 100 percent."
        startDate.isNotBlank() && startDate.trim().taskCompactDate() == null -> "Enter the start date as YYYY-MM-DD."
        categories.size > MAX_TASK_CATEGORIES || categories.any { it.length > 100 || it.any(Char::isISOControl) } -> "Check the tags."
        parentUid.isNotBlank() && !isSafeGroupwareTaskUid(parentUid) -> "The parent task is invalid."
        else -> null
    }
}

internal enum class TaskPriority(val label: String, val value: Int) {
    None("None", 0),
    High("High", 1),
    Medium("Medium", 5),
    Low("Low", 9),
    ;

    companion object {
        fun of(value: Int?): TaskPriority = when (value) {
            null, 0 -> None
            in 1..4 -> High
            5 -> Medium
            else -> Low
        }
    }
}

internal fun GroupwareTask.extras(): TaskExtras = TaskExtras(
    priority = priority ?: 0,
    percentComplete = percentComplete ?: if (completed) 100 else 0,
    startDate = start?.take(8)?.takeIf { it.length == 8 && it.all(Char::isDigit) }
        ?.let { "${it.take(4)}-${it.substring(4, 6)}-${it.takeLast(2)}" }.orEmpty(),
    categories = categories,
    parentUid = parentUid.orEmpty(),
)

/** Reads the extra fields of one VTODO's lines, for [parseGroupwareTasksFromContent]. */
internal fun List<String>.taskDirectProperties(): List<ICalendarContentLine> {
    var depth = 0
    return mapNotNull { line ->
        when {
            line.startsWith("BEGIN:", ignoreCase = true) -> { depth++; null }
            line.startsWith("END:", ignoreCase = true) -> { depth = maxOf(0, depth - 1); null }
            depth == 0 -> parseICalendarContentLine(line)
            else -> null
        }
    }
}

internal fun List<ICalendarContentLine>.taskParentUid(): String? = firstOrNull { line ->
    line.name.equals("RELATED-TO", true) && (line.parameter("RELTYPE")?.equals("PARENT", true) ?: true)
}?.value?.trim()?.takeIf(::isSafeGroupwareTaskUid)

internal fun List<ICalendarContentLine>.taskCategories(): List<String> =
    filter { it.name.equals("CATEGORIES", true) }
        .flatMap { it.value.split(Regex("(?<!\\\\),")) }
        .map { it.decodeCalendarText().trim() }
        .filter(String::isNotEmpty)
        .distinct()

/**
 * Writes [extras] into the task component with [uid] and [recurrenceId]. Progress follows
 * completion: a completed task keeps 100 percent, and an open one stays below it.
 */
internal fun applyGroupwareTaskExtras(
    content: String,
    uid: String,
    recurrenceId: String?,
    extras: TaskExtras,
    completed: Boolean,
): String {
    val wanted = extras.normalized()
    require(wanted.validationMessage() == null) { "The task fields are invalid." }
    require(wanted.parentUid != uid) { "A task cannot be its own parent." }
    val lines = content.unfoldCalendarLines().toMutableList()
    val range = requireNotNull(lines.icalendarComponents("VTODO").firstOrNull { range ->
        val properties = lines.icalendarDirectProperties(range).map { it.second }
        properties.firstOrNull { it.name.equals("UID", true) }?.value?.trim() == uid &&
            properties.firstOrNull { it.name.equals("RECURRENCE-ID", true) }?.value?.trim() == recurrenceId
    }) { "The task could not be found." }
    var end = range.end
    fun replace(name: String, replacement: List<String>, keep: (ICalendarContentLine) -> Boolean = { false }) {
        val existing = lines.icalendarDirectProperties(ICalendarComponentRange(range.begin, end))
            .filter { (_, line) -> line.name.equals(name, true) && !keep(line) }
        if (existing.map { lines[it.first] } == replacement) return
        val at = existing.firstOrNull()?.first ?: end
        existing.reversed().forEach { (index, _) -> lines.removeAt(index); end-- }
        lines.addAll(at.coerceAtMost(end), replacement)
        end += replacement.size
    }
    replace("PRIORITY", listOfNotNull(wanted.priority.takeIf { it > 0 }?.let { "PRIORITY:$it" }))
    if (!completed) {
        // Reopening a finished task starts it over rather than leaving it at 100 percent.
        val percent = if (wanted.percentComplete >= 100) 0 else wanted.percentComplete
        val stored = lines.icalendarDirectProperties(ICalendarComponentRange(range.begin, end))
            .firstOrNull { it.second.name.equals("PERCENT-COMPLETE", true) }?.second?.value?.trim()?.toIntOrNull() ?: 0
        if (stored != percent) {
            replace("PERCENT-COMPLETE", listOf("PERCENT-COMPLETE:$percent"))
            replace("STATUS", listOf("STATUS:" + if (percent > 0) "IN-PROCESS" else "NEEDS-ACTION"))
        }
    }
    val start = wanted.startDate.taskCompactDate()
    val existingStart = lines.icalendarDirectProperties(ICalendarComponentRange(range.begin, end))
        .firstOrNull { it.second.name.equals("DTSTART", true) }?.second
    // A start that only moved its date keeps its time and zone.
    if (existingStart?.value?.take(8) != start) {
        replace("DTSTART", listOfNotNull(start?.let { "DTSTART;VALUE=DATE:$it" }))
    }
    replace("CATEGORIES", listOfNotNull(wanted.categories.takeIf(List<String>::isNotEmpty)?.let { tags ->
        "CATEGORIES:" + tags.joinToString(",") { it.escapeCalendarText() }
    }))
    replace(
        "RELATED-TO",
        listOfNotNull(wanted.parentUid.takeIf(String::isNotEmpty)?.let { "RELATED-TO;RELTYPE=PARENT:${it.escapeCalendarText()}" }),
        keep = { line -> line.parameter("RELTYPE")?.let { !it.equals("PARENT", true) } ?: false },
    )
    return lines.joinICalendarLines()
}

/** Tasks ordered so each subtask follows its parent, with its nesting depth. Cycles are cut. */
internal fun List<GroupwareTask>.inTaskHierarchy(): List<Pair<GroupwareTask, Int>> {
    val byUid = filter { it.recurrenceId == null }.associateBy { it.calendarHref to it.uid }
    val children = groupBy { task ->
        task.parentUid?.let { parent -> (task.calendarHref to parent).takeIf { it in byUid && parent != task.uid } }
    }
    val result = mutableListOf<Pair<GroupwareTask, Int>>()
    val placed = mutableSetOf<String>()
    fun place(task: GroupwareTask, depth: Int) {
        if (!placed.add(task.instanceId)) return
        result += task to depth
        if (depth < MAX_TASK_DEPTH) children[task.calendarHref to task.uid].orEmpty().forEach { place(it, depth + 1) }
    }
    children[null].orEmpty().forEach { place(it, 0) }
    // Anything left is in a cycle or under a filtered-out parent: show it at the top level.
    forEach { place(it, 0) }
    return result
}

/** Tasks that may become [task]'s parent: in the same list, not the task itself or one of its subtasks. */
internal fun taskParentCandidates(task: GroupwareTask?, calendarHref: String, tasks: List<GroupwareTask>): List<GroupwareTask> {
    val sameList = tasks.filter { it.calendarHref == calendarHref && it.recurrenceId == null }
    if (task == null) return sameList
    val descendants = mutableSetOf(task.uid)
    var grew = true
    while (grew) {
        grew = sameList.filter { it.parentUid in descendants && it.uid !in descendants }.map { descendants.add(it.uid) }.isNotEmpty()
    }
    return sameList.filter { it.uid !in descendants }
}

internal fun String.taskCompactDate(): String? = trim().takeIf { it.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) }
    ?.replace("-", "")?.takeIf(::isValidGroupwareTaskDueDate)

private const val MAX_TASK_CATEGORIES = 30
private const val MAX_TASK_DEPTH = 8
