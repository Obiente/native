package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupwareTaskIdentityTest {
    private val calendarHref = "/remote.php/dav/calendars/person/tasks/"
    private val href = "${calendarHref}recurring.ics"

    @Test
    fun `master and exception remain separately selectable when UID equals recurrence ID`() {
        val recurrence = "20260830T120000Z"
        val content = recurringContent(recurrence, "RECURRENCE-ID:$recurrence")
        val tasks = parseGroupwareTasksFromContent(calendarHref, href, "\"one\"", content)
        assertEquals(2, tasks.size)
        assertEquals(2, tasks.map(GroupwareTask::instanceId).distinct().size)

        tasks.forEach { selected ->
            val restored = tasks.firstOrNull { it.instanceId == selected.instanceId }
            assertEquals(selected, restored)
            val updated = updateGroupwareTaskContent(requireNotNull(restored), "Changed", null, false, null)
            val refreshed = parseGroupwareTasksFromContent(calendarHref, href, "\"two\"", updated)
            assertEquals(tasks.map(GroupwareTask::instanceId), refreshed.map(GroupwareTask::instanceId))
            assertEquals("Changed", refreshed.single { it.instanceId == selected.instanceId }.title)
            assertEquals(
                tasks.single { it.instanceId != selected.instanceId }.title,
                refreshed.single { it.instanceId != selected.instanceId }.title,
            )
        }
    }

    @Test
    fun `identity includes both UID and recurrence and does not confuse separator text`() {
        val tasks = parseGroupwareTasksFromContent(
            calendarHref, href, "\"one\"", recurringContent("one", "RECURRENCE-ID:20260830T120000Z"),
        )
        val master = tasks.single { it.recurrenceId == null }
        val exception = tasks.single { it.recurrenceId != null }
        assertNotEquals(exception.instanceId, exception.copy(uid = "two").instanceId)
        assertNotEquals(exception.instanceId, exception.copy(href = "${calendarHref}other.ics").instanceId)
        assertNotEquals(master.instanceId, master.copy(uid = "one#master").instanceId)
        assertNotEquals(exception.instanceId, master.copy(uid = "one#exception:20260830T120000Z").instanceId)
    }

    @Test
    fun `valid date and date-time recurrence values survive exact exception edits`() {
        listOf(
            "RECURRENCE-ID;VALUE=DATE:20280229",
            "RECURRENCE-ID:20260830T120000Z",
            "RECURRENCE-ID:20260830t120000z",
            "RECURRENCE-ID;VALUE=DATE-TIME:20260830T120000Z",
            "RECURRENCE-ID:20260830T120000",
            "RECURRENCE-ID;TZID=Europe/Amsterdam:20260830T120000",
            "RECURRENCE-ID;RANGE=THISANDFUTURE:20260830T120000Z",
            "RECURRENCE-ID:20161231T235960Z",
        ).forEach { property ->
            val content = recurringContent("series", property)
            val tasks = parseGroupwareTasksFromContent(calendarHref, href, "\"one\"", content)
            assertEquals(2, tasks.size, property)
            val exception = tasks.single { it.recurrenceId != null }
            assertEquals(property.substringAfter(':'), exception.recurrenceId)
            val updated = updateGroupwareTaskContent(exception, "Changed", null, false, null)
            assertTrue(property in updated)
            val refreshed = parseGroupwareTasksFromContent(calendarHref, href, "\"two\"", updated)
            assertEquals("Master", refreshed.single { it.recurrenceId == null }.title)
            assertEquals("Changed", refreshed.single { it.instanceId == exception.instanceId }.title)
        }
    }

    @Test
    fun `invalid recurrence values are withheld without turning exceptions into masters`() {
        val valid = "20260830T120000Z"
        val invalid = listOf(
            "", " ", "\t$valid", "$valid\t", "\u0000$valid", "$valid\u0000",
            "20260830T12\u000100Z", "20260830T12\u007f00Z", "20260830T12\u008500Z",
            " $valid", "$valid ", "20260830T120000ZZ", "x".repeat(1_025),
            "20260229", "20261301", "20260830T240000Z", "20260830T126000Z", "20260830T120061Z",
            "20260830T120000+0200", "2026-08-30", "20260830T1200", "20260830X120000Z",
        )
        invalid.forEachIndexed { index, value ->
            val property = "RECURRENCE-ID:$value"
            val content = recurringContent("series", property)
            val tasks = parseGroupwareTasksFromContent(calendarHref, href, "\"one\"", content)
            assertEquals(1, tasks.size, "Invalid recurrence fixture $index")
            val master = tasks.single()
            assertNull(master.recurrenceId)
            assertEquals("Master", master.title)
            assertEquals(content, master.rawCalendar)
            assertFalse(isGroupwareTaskObjectDeleteSafe(master))
            val updated = updateGroupwareTaskContent(master, "Changed master", null, false, null)
            assertTrue("$property\r\nSUMMARY:Exception" in updated)
        }
    }

    @Test
    fun `oversized recurrence values cannot enter saved task selection`() {
        val original = createGroupwareTaskContent("series", "Exception", null, false)
        val oversized = original.replace("END:VTODO", "RECURRENCE-ID:${"x".repeat(512 * 1_024)}\r\nEND:VTODO")
        assertTrue(parseGroupwareTasksFromContent(calendarHref, href, "\"one\"", oversized).isEmpty())
    }

    @Test
    fun `folded recurrence values are validated after unfolding`() {
        val content = recurringContent("series", "RECURRENCE-ID:20260830T\r\n 120000Z")
        val tasks = parseGroupwareTasksFromContent(calendarHref, href, "\"one\"", content)
        assertEquals(2, tasks.size)
        assertEquals("20260830T120000Z", tasks.single { it.recurrenceId != null }.recurrenceId)
        val oversized = content.replace(" 120000Z", " 120000Z" + "x".repeat(1_024))
        assertNull(parseGroupwareTasksFromContent(calendarHref, href, "\"one\"", oversized).single().recurrenceId)
    }

    @Test
    fun `editing the master skips malformed siblings that precede it`() {
        listOf("RECURRENCE-ID:", "RECURRENCE-ID:\t", "UID:\tseries\t").forEach { malformed ->
            val content = listOf(
                "BEGIN:VCALENDAR", "VERSION:2.0",
                "BEGIN:VTODO", malformed,
                if (malformed.startsWith("RECURRENCE-ID")) "UID:series" else "STATUS:NEEDS-ACTION",
                "SUMMARY:Malformed", "END:VTODO",
                "BEGIN:VTODO", "UID:series", "SUMMARY:Master", "END:VTODO",
                "END:VCALENDAR",
            ).joinToString("\r\n", postfix = "\r\n")
            val master = parseGroupwareTasksFromContent(calendarHref, href, "\"one\"", content).single()
            val updated = updateGroupwareTaskContent(master, "Changed master", null, false, null)
            assertTrue("SUMMARY:Malformed" in updated)
            assertTrue(malformed in updated)
            assertEquals("Changed master", parseGroupwareTask(calendarHref, href, "\"two\"", updated)?.title)
        }
    }

    @Test
    fun `largest accepted task identity remains small for saved selection`() {
        val longHref = calendarHref + "x".repeat(4_096 - calendarHref.length)
        val content = recurringContent("x".repeat(1_024), "RECURRENCE-ID:20260830T120000Z")
        val tasks = parseGroupwareTasksFromContent(calendarHref, longHref, "\"one\"", content)
        assertEquals(2, tasks.size)
        tasks.forEach { assertTrue(it.instanceId.length < 8_192) }
    }

    private fun recurringContent(uid: String, recurrenceProperty: String): String = listOf(
        "BEGIN:VCALENDAR", "VERSION:2.0",
        "BEGIN:VTODO", "UID:$uid", "SUMMARY:Master", "END:VTODO",
        "BEGIN:VTODO", "UID:$uid", recurrenceProperty, "SUMMARY:Exception", "END:VTODO",
        "END:VCALENDAR",
    ).joinToString("\r\n", postfix = "\r\n")
}
