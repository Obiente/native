package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals

class CalendarEventDateRangeTest {
    @Test
    fun allDayEndIsExclusive() {
        assertEquals("20 August 2026 - 23 August 2026", event("20260820", "20260824", true).displayDateRange())
        assertEquals("20 August 2026", event("20260820", "20260821", true).displayDateRange())
    }

    @Test
    fun allDayRangeCrossesMonthsAndYears() {
        assertEquals("31 December 2026 - 1 January 2027", event("20261231", "20270102", true).displayDateRange())
        assertEquals("28 February 2024 - 29 February 2024", event("20240228", "20240301", true).displayDateRange())
    }

    @Test
    fun timedEventsRetainTheirActualEndDate() {
        assertEquals("20 August 2026 - 21 August 2026",
            event("20260820T230000Z", "20260821T010000Z", false).displayDateRange())
    }

    @Test
    fun missingOrEarlierEndsDoNotInventARange() {
        for (end in listOf(null, "invalid", "20260819", "20260820")) {
            assertEquals("20 August 2026", event("20260820", end, true).displayDateRange())
        }
    }

    private fun event(start: String, end: String?, allDay: Boolean) = GroupwareCalendarEvent(
        href = "/calendars/synthetic/event.ics", etag = "synthetic", calendarHref = "/calendars/synthetic/",
        uid = "event", title = "Example event", start = start, end = end, allDay = allDay,
        rawCalendar = "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n",
    )
}
