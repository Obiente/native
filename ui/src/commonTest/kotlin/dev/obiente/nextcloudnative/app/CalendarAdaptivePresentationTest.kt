package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CalendarAdaptivePresentationTest {
    @Test
    fun `narrow desktop preserves the calendar canvas by default`() {
        val panes = calendarPaneLayout(940, null, null, eventSelected = true)
        assertFalse(panes.sourcesVisible)
        assertFalse(panes.detailsVisible)
        assertFalse(panes.sourcesInline)
        assertFalse(panes.detailsInline)
    }

    @Test
    fun `explicit narrow pane requests use overlays instead of squeezing the grid`() {
        val panes = calendarPaneLayout(940, true, true, eventSelected = true)
        assertFalse(panes.sourcesVisible)
        assertTrue(panes.detailsVisible)
        assertFalse(panes.sourcesInline)
        assertFalse(panes.detailsInline)
    }

    @Test
    fun `wide screens retain useful panes and respect explicit closure`() {
        val wide = calendarPaneLayout(1440, null, null, eventSelected = true)
        assertTrue(wide.sourcesInline)
        assertTrue(wide.detailsInline)
        val closed = calendarPaneLayout(1440, false, false, eventSelected = true)
        assertFalse(closed.sourcesVisible)
        assertFalse(closed.detailsVisible)
        assertFalse(calendarPaneLayout(1440, null, null, eventSelected = false).detailsVisible)
    }

    @Test
    fun `date picker preserves UTC calendar days across leap day and DST boundaries`() {
        listOf("2024-02-29", "2026-03-29", "2026-10-25", "1970-01-01").forEach { date ->
            assertEquals(date, calendarDateFromEpochMillis(requireNotNull(calendarDateEpochMillis(date))))
        }
        assertEquals(0L, calendarDateEpochMillis("1970-01-01"))
        assertNull(calendarDateEpochMillis("2025-02-29"))
        assertNull(calendarDateEpochMillis("2026-13-01"))
        assertNull(calendarDateEpochMillis("not a date"))
    }

    @Test
    fun `editor title states series scope`() {
        assertEquals("New event", calendarEditorTitle(null))
        assertEquals("Edit event", calendarEditorTitle(event()))
        assertEquals("Edit series", calendarEditorTitle(event().copy(recurrenceRule = "FREQ=WEEKLY")))
    }

    private fun event() = GroupwareCalendarEvent(
        href = "/calendars/synthetic/event.ics", etag = "\"synthetic\"",
        calendarHref = "/calendars/synthetic/", uid = "synthetic", title = "Example",
        start = "20260804T090000Z", end = "20260804T100000Z", allDay = false,
        location = null, description = null, recurrenceRule = null,
        rawCalendar = "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n",
    )
}
