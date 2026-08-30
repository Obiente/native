package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GroupwareTaskDuplicateIdentityTest {
    private val calendar = "/remote.php/dav/calendars/person/tasks/"
    private val href = "${calendar}tasks.ics"

    @Test
    fun duplicateMastersAndRecurringExceptionsRejectTheCalendarObject() {
        listOf(null, "20260830", "20260830T120000Z").forEach { recurrence ->
            val content = content(recurrence)
            assertFailsWith<IllegalArgumentException> {
                parseGroupwareTasksFromContent(calendar, href, "v1", duplicate(content))
            }
            assertFailsWith<IllegalArgumentException> {
                parseGroupwareTask(calendar, href, "v1", duplicate(content))
            }
        }
    }

    @Test
    fun retainedSelectionCannotRewriteTheFirstOfTwoAmbiguousComponents() {
        listOf(null, "20260830T120000Z").forEach { recurrence ->
            val content = content(recurrence)
            val selected = parseGroupwareTasksFromContent(calendar, href, "v1", content).single()
                .copy(rawCalendar = duplicate(content))
            assertFailsWith<IllegalArgumentException> {
                updateGroupwareTaskContent(selected, "Changed", null, false, null)
            }
            assertEquals(duplicate(content), selected.rawCalendar)
        }
    }

    @Test
    fun repeatedDavResponsesCannotSupplyDuplicateListKeys() {
        val content = content(null)
        fun response(objectHref: String) = """<d:response><d:href>$objectHref</d:href>
            <d:getetag>v1</d:getetag><c:calendar-data>${content.escapeDavXml()}</c:calendar-data></d:response>"""
        fun envelope(first: String, second: String) = NextcloudApiResponse(207,
            "<d:multistatus>${response(first)}${response(second)}</d:multistatus>".encodeToByteArray(), null, null)
        assertFailsWith<IllegalArgumentException> { parseGroupwareTasks(calendar, envelope(href, href)) }
        val distinctObjects = parseGroupwareTasks(calendar, envelope(href, "${calendar}other.ics"))
        assertEquals(2, distinctObjects.map(GroupwareTask::instanceId).distinct().size)
    }

    private fun content(recurrence: String?): String = createGroupwareTaskContent("series", "First", null, false)
        .let { content -> recurrence?.let { content.replace("END:VTODO", "RECURRENCE-ID:$it\r\nEND:VTODO") } ?: content }

    private fun duplicate(content: String): String {
        val component = content.substringAfter("BEGIN:VTODO\r\n").substringBefore("END:VTODO")
        return content.replace("END:VCALENDAR", "BEGIN:VTODO\r\n${component.replace("SUMMARY:First", "SUMMARY:Second")}END:VTODO\r\nEND:VCALENDAR")
    }
}
