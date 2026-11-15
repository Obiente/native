package dev.obiente.nextcloudnative.app

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CalendarDayLayoutTest {
    @Test
    fun timedEventsSitAtTheirMinutesAndAllDayEventsGoAbove() {
        val layout = calendarDayLayout("20261014", listOf(
            event("meeting", "20261014T160000", "20261014T170000"),
            event("festival", "20261014", "20261015", allDay = true),
        ))
        assertEquals(listOf("festival"), layout.allDay.map { it.uid })
        val meeting = layout.timed.single()
        assertEquals(16 * 60, meeting.startMinute)
        assertEquals(17 * 60, meeting.endMinute)
        assertEquals(0, meeting.lane)
        assertEquals(1, meeting.lanes)
    }

    @Test
    fun overlappingEventsShareTheWidthAndLaterOnesReuseAFreeLane() {
        val layout = calendarDayLayout("20261014", listOf(
            event("a", "20261014T090000", "20261014T110000"),
            event("b", "20261014T100000", "20261014T103000"),
            event("c", "20261014T104500", "20261014T120000"),
            event("d", "20261014T140000", "20261014T150000"),
        )).timed.associateBy { it.event.uid }
        assertEquals(2, layout.getValue("a").lanes)
        assertEquals(0, layout.getValue("a").lane)
        assertEquals(1, layout.getValue("b").lane)
        assertEquals(1, layout.getValue("c").lane, "c starts after b ends, so it takes b's lane beside a")
        assertEquals(1, layout.getValue("d").lanes, "A separate later event uses the full width")
    }

    @Test
    fun eventsCrossingMidnightAreClippedToEachDay() {
        val night = event("night", "20261014T220000", "20261015T020000")
        assertEquals(22 * 60 to MinutesPerDay, calendarDayLayout("20261014", listOf(night)).timed.single().let { it.startMinute to it.endMinute })
        assertEquals(0 to 2 * 60, calendarDayLayout("20261015", listOf(night)).timed.single().let { it.startMinute to it.endMinute })
        val spanning = event("trip", "20261013T080000", "20261016T180000")
        assertEquals(listOf("trip"), calendarDayLayout("20261014", listOf(spanning)).allDay.map { it.uid })
    }

    @Test
    fun anEventWithoutLengthStillGetsAVisibleBlock() {
        val instant = calendarDayLayout("20261014", listOf(event("call", "20261014T100000", "20261014T100000"))).timed.single()
        assertEquals(10 * 60 + MinimumEventMinutes, instant.endMinute)
    }

    @Test
    fun aTappedSlotStartsOnTheHalfHourBefore() {
        assertEquals("20261014T143000", calendarSlotStart("20261014", 14 * 60 + 47))
        assertEquals("20261014T000000", calendarSlotStart("20261014", -5))
        assertEquals(14 * 60 + 30, "20261014T143000".calendarMinuteOfDay())
        assertNull("20261014".calendarMinuteOfDay())
    }

    @Test
    fun serverCalendarColorsAreUsedAndInvalidOnesIgnored() {
        assertEquals(Color(0xFF0082C9), "#0082c9".parseCalendarColor())
        assertEquals(Color(0x800082C9), "#0082C980".parseCalendarColor())
        assertNull("blue".parseCalendarColor())
        assertNull("#12345".parseCalendarColor())
    }

    private fun event(uid: String, start: String, end: String?, allDay: Boolean = false) = GroupwareCalendarEvent(
        href = "/calendars/synthetic/$uid.ics", etag = null, calendarHref = "/calendars/synthetic/", uid = uid,
        title = uid, start = start, end = end, allDay = allDay, rawCalendar = "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n",
    )
}
