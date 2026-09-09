package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CalendarDatePresentationTest {
    @Test
    fun weekTitleKeepsTheYearVisibleIncludingNewYearBoundaries() {
        assertEquals("3-9 August 2026", calendarWeekTitle(calendarWeekDates("20260804")))
        assertEquals("27 July - 2 August 2026", calendarWeekTitle(calendarWeekDates("20260801")))
        assertEquals("28 December 2026 - 3 January 2027", calendarWeekTitle(calendarWeekDates("20270101")))
    }

    @Test
    fun multiDayEventsAppearOnEachCoveredDateWithoutRepeatingInTheAgenda() {
        val event = event("20260820", "20260824")
        val presentation = buildCalendarWorkspacePresentation(listOf(event), listOf(calendar), emptySet(), "", "20260822")
        assertEquals(listOf("20260820", "20260821", "20260822", "20260823"), presentation.eventsByDate.keys.toList())
        assertEquals(listOf(event), presentation.selectedDateEvents)
        assertEquals(listOf(event), presentation.visibleEvents)
        assertEquals(mapOf(calendar.href to 1), presentation.eventCountByCalendar)
    }

    @Test
    fun crossMonthWeekKeepsOverlappingEventsAndExcludesTheAllDayEnd() {
        val event = event("20260725", "20260729")
        val presentation = buildCalendarWorkspacePresentation(listOf(event), listOf(calendar), emptySet(), "", "20260801")
        assertEquals(listOf(event), presentation.eventsByDate["20260727"])
        assertEquals(listOf(event), presentation.eventsByDate["20260728"])
        assertTrue("20260729" !in presentation.eventsByDate)
    }

    @Test
    fun veryLongEventsAreBoundedToTheVisibleMonthAndBoundaryWeeks() {
        val event = event("20000101", "20991231")
        val presentation = buildCalendarWorkspacePresentation(listOf(event), listOf(calendar), emptySet(), "", "20260801")
        assertEquals(36, presentation.eventsByDate.size)
        assertEquals("20260727", presentation.eventsByDate.keys.first())
        assertEquals("20260831", presentation.eventsByDate.keys.last())
    }

    @Test
    fun hiddenCalendarsAndSearchAlsoFilterEveryDayOfASpanningEvent() {
        val event = event("20260820", "20260824")
        assertTrue(buildCalendarWorkspacePresentation(listOf(event), listOf(calendar), setOf(calendar.href), "", "20260822").eventsByDate.isEmpty())
        assertTrue(buildCalendarWorkspacePresentation(listOf(event), listOf(calendar), emptySet(), "unmatched", "20260822").eventsByDate.isEmpty())
    }

    @Test
    fun invalidOrReversedVisibleWindowsNeverExpandEvents() {
        val event = event("20260820", "20260824")
        assertTrue(calendarEventsByDate(listOf(event), "invalid", "20260831").isEmpty())
        assertTrue(calendarEventsByDate(listOf(event), "20260901", "20260831").isEmpty())
    }

    private val calendar = GroupwareCalendar("/calendars/synthetic/", "Team")
    private fun event(start: String, end: String) = GroupwareCalendarEvent(
        href = "/calendars/synthetic/break.ics", etag = "\"synthetic\"", calendarHref = calendar.href,
        uid = "break", title = "Summer break", start = start, end = end, allDay = true,
        rawCalendar = "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n",
    )
}
