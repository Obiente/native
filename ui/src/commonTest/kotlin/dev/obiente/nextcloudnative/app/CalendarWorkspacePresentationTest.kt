package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals

class CalendarWorkspacePresentationTest {
    @Test
    fun `calendar selection and search filter the same event model`() {
        val personal = calendar("personal", "Personal")
        val team = calendar("team", "Team")
        val events = listOf(
            event("personal", "20260803T090000Z", "Dentist", location = "Health centre"),
            event("team", "20260804T130000Z", "Design review", description = "Native Calendar"),
            event("team", "20260805T100000Z", "Planning"),
        )

        val presentation = buildCalendarWorkspacePresentation(
            events = events,
            calendars = listOf(personal, team),
            hiddenCalendarHrefs = setOf(personal.href),
            query = "native",
            selectedDate = "20260804",
        )

        assertEquals(listOf("Design review"), presentation.visibleEvents.map(GroupwareCalendarEvent::title))
        assertEquals(listOf("Design review"), presentation.selectedDateEvents.map(GroupwareCalendarEvent::title))
        assertEquals(mapOf(personal.href to 1, team.href to 2), presentation.eventCountByCalendar)
    }

    @Test
    fun `week dates are Monday through Sunday across month boundaries`() {
        assertEquals(
            listOf("20260727", "20260728", "20260729", "20260730", "20260731", "20260801", "20260802"),
            calendarWeekDates("20260801"),
        )
    }

    private fun calendar(id: String, name: String) = GroupwareCalendar(
        href = "/remote.php/dav/calendars/synthetic/$id/",
        displayName = name,
        writable = true,
    )

    private fun event(
        calendarId: String,
        start: String,
        title: String,
        location: String? = null,
        description: String? = null,
    ) = GroupwareCalendarEvent(
        href = "/remote.php/dav/calendars/synthetic/$calendarId/${title.lowercase().replace(' ', '-')}.ics",
        etag = "\"synthetic\"",
        calendarHref = "/remote.php/dav/calendars/synthetic/$calendarId/",
        uid = "synthetic-$calendarId-$start",
        title = title,
        start = start,
        end = start.take(8) + "T100000Z",
        allDay = false,
        location = location,
        description = description,
        rawCalendar = "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n",
    )
}
