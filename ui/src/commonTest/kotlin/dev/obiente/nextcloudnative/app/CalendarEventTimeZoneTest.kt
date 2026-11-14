package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CalendarEventTimeZoneTest {
    @Test
    fun aZonedStartKeepsItsZoneWhenParsed() {
        val event = parse("DTSTART;TZID=Europe/Paris:20261014T090000", "DTEND;TZID=\"Europe/Paris\":20261014T100000")
        assertEquals("20261014T090000", event.start)
        assertEquals("Europe/Paris", event.startZone)
        assertEquals("Europe/Paris", event.endZone, "A quoted TZID parameter is unquoted")
        assertNull(parse("DTSTART:20261014T070000Z", "DTEND:20261014T080000Z").startZone)
    }

    @Test
    fun utcAndZonedTimesShowInDeviceTime() {
        val utc = parse("DTSTART:20261014T070000Z", "DTEND:20261014T080000Z").inDeviceTime(Amsterdam)
        assertEquals("20261014T090000" to "20261014T100000", utc.start to utc.end, "UTC+2 in October before the change")
        val newYork = parse("DTSTART;TZID=America/New_York:20261014T090000", "DTEND;TZID=America/New_York:20261014T100000")
            .inDeviceTime(Amsterdam)
        assertEquals("20261014T150000", newYork.start)
        val afterChange = parse("DTSTART:20261025T010000Z", "DTEND:20261025T020000Z").inDeviceTime(Amsterdam)
        assertEquals("20261025T020000", afterChange.start, "Daylight-saving time ended at 01:00 UTC")
    }

    @Test
    fun floatingAllDayAndUnknownZonesStayAsWritten() {
        val floating = parse("DTSTART:20261014T090000", "DTEND:20261014T100000")
        assertSame(floating, floating.inDeviceTime(Amsterdam))
        val allDay = parse("DTSTART;VALUE=DATE:20261014", "DTEND;VALUE=DATE:20261015")
        assertSame(allDay, allDay.inDeviceTime(Amsterdam))
        val unknown = parse("DTSTART;TZID=Mars/Olympus:20261014T090000", "DTEND;TZID=Mars/Olympus:20261014T100000")
        assertEquals("20261014T090000", unknown.inDeviceTime(Amsterdam).start, "An unknown zone is shown as written, not guessed")
    }

    @Test
    fun anEditIsStoredInUtcWhenTheEventHasNoZone() {
        val stored = draft("2026-10-14", "09:00", "10:00").inDavFrame(null, Amsterdam)
        assertEquals("20261014T070000Z", stored.startValue())
        assertEquals("20261014T080000Z", stored.endValue())
        assertNull(stored.zone)
    }

    @Test
    fun anEditCrossingMidnightInUtcKeepsItsEndAfterItsStart() {
        val stored = draft("2026-10-15", "01:00", "02:00").inDavFrame(null, Amsterdam)
        assertEquals("20261014T230000Z", stored.startValue())
        assertEquals("20261015T000000Z", stored.endValue())
    }

    @Test
    fun aZonedEventKeepsItsZoneSoRepeatsKeepTheirWallTime() {
        val stored = draft("2026-10-14", "15:00", "16:00").inDavFrame("America/New_York", Amsterdam)
        assertEquals("America/New_York", stored.zone)
        assertEquals("20261014T090000", stored.startValue())
        val content = createGroupwareCalendarEventContent("uid", "Standup", stored.startValue(), stored.endValue(), false, timeZone = stored.zone)
        assertTrue("DTSTART;TZID=America/New_York:20261014T090000" in content)
        val reparsed = parseGroupwareCalendarEventsFromContent("/remote.php/dav/calendars/synthetic/team/", "/remote.php/dav/calendars/synthetic/team/uid.ics", null, content).single()
        assertEquals(stored.startValue(), reparsed.start, "The save's postcondition compares exactly what was written")
        assertEquals("America/New_York", reparsed.startZone)
    }

    @Test
    fun malformedZonesAreNeverWrittenIntoAPropertyLine() {
        assertEquals(";TZID=Europe/Paris", calendarTimeParameter(false, "Europe/Paris"))
        assertEquals("", calendarTimeParameter(false, "Europe/Paris:20991231T000000"))
        assertEquals("", calendarTimeParameter(false, "Europe/Paris;X-EVIL=1"))
        assertEquals(";VALUE=DATE", calendarTimeParameter(true, "Europe/Paris"))
        val stored = draft("2026-10-14", "09:00", "10:00").inDavFrame("Bad:Zone", Amsterdam)
        assertNull(stored.zone, "A zone that cannot be written safely falls back to UTC")
        assertEquals("20261014T070000Z", stored.startValue())
    }

    private fun parse(start: String, end: String): GroupwareCalendarEvent = parseGroupwareCalendarEventsFromContent(
        "/remote.php/dav/calendars/synthetic/team/", "/remote.php/dav/calendars/synthetic/team/item.ics", null,
        "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:item\r\nSUMMARY:Item\r\n$start\r\n$end\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n",
    ).single()

    private fun draft(date: String, start: String, end: String) =
        EventDraft("Standup", date, start, end, allDay = false, location = "", description = "", recurrenceRule = null)

    private companion object {
        const val Amsterdam = "Europe/Amsterdam"
    }
}
