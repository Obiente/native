package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupwareTaskFieldsTest {
    private val list = "/remote.php/dav/calendars/alice/tasks/"

    @Test
    fun tasksReadPriorityProgressStartTagsAndParent() {
        val task = task(
            "UID:child", "SUMMARY:Child", "PRIORITY:1", "PERCENT-COMPLETE:40", "DTSTART;VALUE=DATE:20261101",
            "CATEGORIES:home,urgent\\,ish", "RELATED-TO;RELTYPE=PARENT:parent", "RELATED-TO;RELTYPE=SIBLING:other",
        )
        assertEquals(1, task.priority)
        assertEquals(40, task.percentComplete)
        assertEquals("20261101", task.start)
        assertEquals(listOf("home", "urgent,ish"), task.categories)
        assertEquals("parent", task.parentUid)
        assertEquals(TaskPriority.High, TaskPriority.of(task.priority))
        assertEquals(TaskExtras(1, 40, "2026-11-01", listOf("home", "urgent,ish"), "parent"), task.extras())
        assertEquals("parent", task("UID:x", "SUMMARY:x", "RELATED-TO:parent").parentUid, "RELTYPE defaults to PARENT.")
        assertNull(task("UID:x", "SUMMARY:x", "RELATED-TO;RELTYPE=CHILD:kid").parentUid)
    }

    @Test
    fun extrasAreWrittenAndOtherRelationsAreKept() {
        val original = task("UID:child", "SUMMARY:Child", "RELATED-TO;RELTYPE=SIBLING:other", "STATUS:NEEDS-ACTION")
        val content = applyGroupwareTaskExtras(
            original.rawCalendar, "child", null,
            TaskExtras(priority = 5, percentComplete = 30, startDate = "2026-11-02", categories = listOf("a", "b"), parentUid = "parent"),
            completed = false,
        )
        val updated = reparse(content)
        assertEquals(5, updated.priority)
        assertEquals(30, updated.percentComplete)
        assertEquals("20261102", updated.start)
        assertEquals(listOf("a", "b"), updated.categories)
        assertEquals("parent", updated.parentUid)
        assertTrue("RELATED-TO;RELTYPE=SIBLING:other" in content)
        assertTrue("STATUS:IN-PROCESS" in content)

        val cleared = reparse(applyGroupwareTaskExtras(content, "child", null, TaskExtras(), completed = false))
        assertNull(cleared.priority)
        assertNull(cleared.start)
        assertTrue(cleared.categories.isEmpty())
        assertNull(cleared.parentUid)
        assertEquals(0, cleared.percentComplete)
        assertTrue("RELATED-TO;RELTYPE=SIBLING:other" in cleared.rawCalendar)
    }

    @Test
    fun unchangedExtrasLeaveTheTaskAsItWas() {
        val original = task("UID:t", "SUMMARY:T", "PRIORITY:9", "DTSTART:20261101T080000Z", "STATUS:CANCELLED")
        val content = applyGroupwareTaskExtras(original.rawCalendar, "t", null, original.extras(), completed = false)
        assertEquals(original.rawCalendar.unfoldCalendarLines(), content.unfoldCalendarLines())
        assertTrue("DTSTART:20261101T080000Z" in content, "A start whose date is unchanged keeps its time.")
        val zoned = task("UID:t", "SUMMARY:T", "DTSTART;TZID=Europe/Paris:20261101T080000")
        val moved = applyGroupwareTaskExtras(zoned.rawCalendar, "t", null, zoned.extras().copy(startDate = "2026-11-02"), completed = false)
        assertTrue("DTSTART;TZID=Europe/Paris:20261102T080000" in moved, "A moved start keeps its time and time zone: $moved")
        val cleared = applyGroupwareTaskExtras(zoned.rawCalendar, "t", null, zoned.extras().copy(startDate = ""), completed = false)
        assertTrue("DTSTART" !in cleared)
    }

    @Test
    fun completedTasksKeepFullProgressAndReopenedOnesStartOver() {
        val done = task("UID:t", "SUMMARY:T", "STATUS:COMPLETED", "PERCENT-COMPLETE:100")
        assertEquals(done.rawCalendar.unfoldCalendarLines(),
            applyGroupwareTaskExtras(done.rawCalendar, "t", null, done.extras(), completed = true).unfoldCalendarLines())
        val reopened = updateGroupwareTaskContent(done, "T", null, completed = false, description = null, completionTimestamp = "20261101T080000Z")
        val content = applyGroupwareTaskExtras(reopened, "t", null, done.extras(), completed = false)
        assertEquals(0, reparse(content).percentComplete)
        assertFalse(reparse(content).completed)
    }

    @Test
    fun invalidExtrasAreRejected() {
        val base = task("UID:t", "SUMMARY:T").rawCalendar
        assertFailsWith<IllegalArgumentException> { applyGroupwareTaskExtras(base, "t", null, TaskExtras(priority = 10), false) }
        assertFailsWith<IllegalArgumentException> { applyGroupwareTaskExtras(base, "t", null, TaskExtras(startDate = "2026-02-30"), false) }
        assertFailsWith<IllegalArgumentException> { applyGroupwareTaskExtras(base, "t", null, TaskExtras(parentUid = "t"), false) }
        assertFailsWith<IllegalArgumentException> { applyGroupwareTaskExtras(base, "missing", null, TaskExtras(), false) }
    }

    @Test
    fun hierarchyPlacesSubtasksAfterTheirParentAndCutsCycles() {
        val parent = task("UID:p", "SUMMARY:Parent")
        val child = task("UID:c", "SUMMARY:Child", "RELATED-TO:p")
        val grandchild = task("UID:g", "SUMMARY:Grandchild", "RELATED-TO:c")
        val orphan = task("UID:o", "SUMMARY:Orphan", "RELATED-TO:gone")
        val loopA = task("UID:a", "SUMMARY:A", "RELATED-TO:b")
        val loopB = task("UID:b", "SUMMARY:B", "RELATED-TO:a")
        val ordered = listOf(grandchild, orphan, child, loopA, parent, loopB).inTaskHierarchy()
        assertEquals(6, ordered.size)
        val names = ordered.map { it.first.title to it.second }
        assertEquals(listOf("Parent" to 0, "Child" to 1, "Grandchild" to 2), names.dropWhile { it.first != "Parent" }.take(3))
        assertTrue("Orphan" to 0 in names)
        assertEquals(setOf("A", "B"), ordered.map { it.first.title }.filter { it == "A" || it == "B" }.toSet())
    }

    @Test
    fun aTaskCannotBecomeTheSubtaskOfItsOwnSubtasks() {
        val parent = task("UID:p", "SUMMARY:Parent")
        val child = task("UID:c", "SUMMARY:Child", "RELATED-TO:p")
        val grandchild = task("UID:g", "SUMMARY:Grandchild", "RELATED-TO:c")
        val other = task("UID:o", "SUMMARY:Other")
        val candidates = taskParentCandidates(parent, list, listOf(parent, child, grandchild, other))
        assertEquals(listOf("o"), candidates.map { it.uid })
        assertEquals(4, taskParentCandidates(null, list, listOf(parent, child, grandchild, other)).size)
        val relations = taskRelations(parent, listOf(parent, child, grandchild, other))
        assertEquals(listOf("c"), relations.subtasks.map { it.uid })
        assertEquals("p", taskRelations(child, listOf(parent, child)).parent?.uid)
    }

    @Test
    fun extrasPostconditionsDetectAMissingChange() {
        val original = task("UID:t", "SUMMARY:T")
        val extras = TaskExtras(priority = 1, categories = listOf("x"))
        val content = applyGroupwareTaskExtras(original.rawCalendar, "t", null, extras, completed = false)
        val draft = TaskDraft("T", "", "", false, extras)
        val postcondition = TaskMutationPostcondition.Upsert(original.href, list, "t", null, "\"1\"", draft.normalized(), expectedDue = null)
        assertTrue(postcondition.isSatisfiedBy(ok(content)))
        assertFalse(postcondition.isSatisfiedBy(ok(original.rawCalendar)))
        val legacy = postcondition.copy(draft = TaskDraft("T", "", "", false))
        assertTrue(legacy.isSatisfiedBy(ok(original.rawCalendar)), "Records without extras verify as before.")
    }

    @Test
    fun aProgressOnlyChangeIsNotVerifiedAgainstTheUnchangedTask() {
        val original = task("UID:t", "SUMMARY:T", "PERCENT-COMPLETE:20", "STATUS:IN-PROCESS")
        val extras = original.extras().copy(percentComplete = 50)
        val content = applyGroupwareTaskExtras(original.rawCalendar, "t", null, extras, completed = false)
        val draft = TaskDraft("T", "", "", false, extras)
        val postcondition = TaskMutationPostcondition.Upsert(original.href, list, "t", null, "\"1\"", draft.normalized(), expectedDue = null)
        assertTrue(postcondition.isSatisfiedBy(ok(content)))
        assertFalse(postcondition.isSatisfiedBy(ok(original.rawCalendar)), "The task still at 20 percent is not the saved one.")
        val reopened = postcondition.copy(draft = draft.copy(extras = extras.copy(percentComplete = 100)))
        val restarted = applyGroupwareTaskExtras(original.rawCalendar, "t", null, extras.copy(percentComplete = 100), completed = false)
        assertTrue(reopened.isSatisfiedBy(ok(restarted)), "An open task saved at 100 percent starts over at 0.")
    }

    @Test
    fun rowSummariesListTheUsefulFields() {
        val task = task("UID:t", "SUMMARY:T", "DUE;VALUE=DATE:20261105", "PRIORITY:9", "PERCENT-COMPLETE:50", "CATEGORIES:home")
        assertEquals("Due 05-11-2026 - Low priority - 50% done - home", taskRowSummary(task))
        assertNull(taskRowSummary(task("UID:t", "SUMMARY:T")))
    }

    private fun task(vararg properties: String): GroupwareTask {
        val content = (listOf("BEGIN:VCALENDAR", "VERSION:2.0", "BEGIN:VTODO") + properties + listOf("END:VTODO", "END:VCALENDAR"))
            .joinToString("\r\n", postfix = "\r\n")
        val uid = properties.first { it.startsWith("UID:") }.removePrefix("UID:")
        return assertNotNull(parseGroupwareTask(list, "$list$uid.ics", "\"1\"", content))
    }

    private fun reparse(content: String): GroupwareTask = assertNotNull(parseGroupwareTask(list, "${list}t.ics", "\"2\"", content))

    private fun ok(content: String) = NextcloudApiResponse(200, content.encodeToByteArray(), "text/calendar", "\"2\"")
}
