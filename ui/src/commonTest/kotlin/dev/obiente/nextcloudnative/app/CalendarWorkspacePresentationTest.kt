package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CalendarWorkspacePresentationTest {
    @Test
    fun `recurrence rules are presented as user facing schedules`() {
        assertEquals("Weekly on Monday", calendarRecurrenceDescription("FREQ=WEEKLY;BYDAY=MO"))
        assertEquals("Every 2 weeks on Monday, Wednesday", calendarRecurrenceDescription("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE"))
        assertEquals("Custom recurrence", calendarRecurrenceDescription("FREQ=YEARLY"))
    }

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

    @Test
    fun `week query covers every displayed day across month boundaries`() {
        val window = calendarWorkspaceQueryWindow(
            view = CalendarWorkspaceView.Week,
            month = CalendarMonth(2026, 8),
            selectedDate = "20260801",
        )

        assertEquals("20260727T000000Z", window.startUtc)
        assertEquals("20260803T000000Z", window.endUtc)
    }

    @Test
    fun `retained calendar data must match the requested month and window`() {
        val july = GroupwareDavTimeWindow("20260701T000000Z", "20260801T000000Z")
        val august = GroupwareDavTimeWindow("20260801T000000Z", "20260901T000000Z")

        assertFalse(
            calendarReadyMatchesRequest(
                readyMonth = CalendarMonth(2026, 7),
                readyWindow = july,
                requestedMonth = CalendarMonth(2026, 8),
                requestedWindow = august,
            ),
        )
    }

    @Test
    fun `week includes events that started before the displayed range`() {
        val ongoing = event(
            calendarId = "team",
            start = "20260725T090000Z",
            title = "Conference",
            end = "20260729T170000Z",
        )

        assertTrue(ongoing.overlapsCalendarDateRange("20260727", "20260802"))
        assertFalse(ongoing.overlapsCalendarDateRange("20260803", "20260809"))
    }

    @Test
    fun `all day event end is exclusive when testing week overlap`() {
        val sundayOnly = event(
            calendarId = "team",
            start = "20260802",
            title = "Sunday event",
            end = "20260803",
            allDay = true,
        )

        assertFalse(sundayOnly.overlapsCalendarDateRange("20260803", "20260809"))
    }

    @Test
    fun `all day event without explicit end occupies its start date`() {
        val event = event(
            calendarId = "team",
            start = "20260803",
            title = "Release day",
            allDay = true,
        ).copy(end = null)

        assertEquals(listOf("20260803"), event.occupiedCalendarDates())
    }

    @Test
    fun `all day event with explicit end excludes that end date`() {
        val event = event(
            calendarId = "team",
            start = "20260803",
            title = "Conference",
            end = "20260805",
            allDay = true,
        )

        assertEquals(listOf("20260803", "20260804"), event.occupiedCalendarDates())
    }

    @Test
    fun `long spanning event is clamped to the requested calendar window`() {
        val event = event(
            calendarId = "team",
            start = "20240101",
            title = "Long project",
            end = "20260805",
            allDay = true,
        )

        assertEquals(
            listOf("20260801", "20260802", "20260803", "20260804"),
            event.occupiedCalendarDates("20260801", "20260831"),
        )
    }

    @Test
    fun `timed event ending on the first range date remains visible`() {
        val mondayMorning = event(
            calendarId = "team",
            start = "20260803T090000Z",
            title = "Monday event",
            end = "20260803T100000Z",
        )

        assertTrue(mondayMorning.overlapsCalendarDateRange("20260803", "20260809"))
    }

    @Test
    fun `padded calendar weeks always have unique positional keys`() {
        val month = CalendarMonth(2026, 2)
        assertEquals(6, (0 until 6).map { calendarMonthWeekKey(month, it) }.distinct().size)
    }

    @Test
    fun `agenda capture scenarios select the agenda presentation`() {
        assertEquals(
            CalendarWorkspaceView.Agenda,
            MarketingCaptureScenario.CalendarWorkspaceMobileDark.marketingCalendarView(),
        )
        assertEquals(
            CalendarWorkspaceView.Month,
            MarketingCaptureScenario.CalendarMonthMobile.marketingCalendarView(),
        )
    }

    @Test
    fun `calendar navigation detects every user editable draft field`() {
        val initial = EventDraft(
            title = "Planning",
            date = "2026-08-14",
            startTime = "09:00",
            endTime = "10:00",
            allDay = false,
            location = "Team room",
            description = "Weekly planning",
            recurrenceRule = "FREQ=WEEKLY",
        )

        assertFalse(calendarEventDraftIsDirty(initial, initial.copy(), "personal", "personal"))
        listOf(
            initial.copy(title = "Roadmap planning"),
            initial.copy(date = "2026-08-15"),
            initial.copy(startTime = "09:30"),
            initial.copy(endTime = "10:30"),
            initial.copy(allDay = true),
            initial.copy(location = "Talk"),
            initial.copy(description = "Updated agenda"),
            initial.copy(recurrenceRule = "FREQ=MONTHLY"),
        ).forEach { changed ->
            assertTrue(calendarEventDraftIsDirty(initial, changed, "personal", "personal"))
        }
        assertTrue(calendarEventDraftIsDirty(initial, initial, "personal", "team"))
    }

    @Test
    fun `calendar mutation postconditions reject stale reads`() {
        val draft = EventDraft(
            title = "Planning",
            date = "2026-08-14",
            startTime = "09:00",
            endTime = "10:00",
            allDay = false,
            location = "Team room",
            description = "Weekly planning",
            recurrenceRule = null,
        )
        val href = "/remote.php/dav/calendars/synthetic/team/planning.ics"
        val stale = NextcloudApiResponse(
            status = 200,
            contentType = "text/calendar",
            etag = "\"old\"",
            body = createGroupwareCalendarEventContent(
                uid = "planning",
                title = "Old planning",
                start = draft.startValue(),
                end = draft.endValue(),
                allDay = draft.allDay,
            ).encodeToByteArray(),
        )
        val updated = stale.copy(
            etag = "\"new\"",
            body = createGroupwareCalendarEventContent(
                uid = "planning",
                title = draft.title,
                start = draft.startValue(),
                end = draft.endValue(),
                allDay = draft.allDay,
                location = draft.location,
                description = draft.description,
            ).encodeToByteArray(),
        )
        val missing = updated.copy(status = 404, body = byteArrayOf())
        val staleEnd = updated.copy(
            body = createGroupwareCalendarEventContent(
                uid = "planning",
                title = draft.title,
                start = draft.startValue(),
                end = "20260814T110000Z",
                allDay = draft.allDay,
                location = draft.location,
                description = draft.description,
            ).encodeToByteArray(),
        )
        val upsert = CalendarMutationPostcondition.Upsert(
            href,
            "/remote.php/dav/calendars/synthetic/team/",
            "planning",
            "\"old\"",
            draft,
        )
        val deletion = CalendarMutationPostcondition.Delete(href)

        assertFalse(upsert.isSatisfiedBy(stale))
        assertFalse(upsert.isSatisfiedBy(staleEnd))
        assertTrue(upsert.isSatisfiedBy(updated))
        val overrideBeforeMasterLines = listOf(
            "BEGIN:VCALENDAR",
            "BEGIN:VEVENT",
            "UID:planning",
            "RECURRENCE-ID:20260814T090000Z",
            "DTSTART:20260814T120000Z",
            "DTEND:20260814T130000Z",
            "SUMMARY:${draft.title}",
            "END:VEVENT",
        ) + updated.body.decodeToString().trim().lines().drop(1).dropLast(1) + "END:VCALENDAR"
        val overrideBeforeUpdatedMaster = updated.copy(
            body = overrideBeforeMasterLines.joinToString("\r\n", postfix = "\r\n").encodeToByteArray(),
        )
        assertTrue(upsert.isSatisfiedBy(overrideBeforeUpdatedMaster))
        val blankOptionalDraft = draft.copy(location = "   ", description = "\t")
        val blankOptionalResponse = updated.copy(
            body = createGroupwareCalendarEventContent(
                uid = "planning",
                title = blankOptionalDraft.title,
                start = blankOptionalDraft.startValue(),
                end = blankOptionalDraft.endValue(),
                allDay = blankOptionalDraft.allDay,
                location = blankOptionalDraft.location,
                description = blankOptionalDraft.description,
            ).encodeToByteArray(),
        )
        assertTrue(
            CalendarMutationPostcondition.Upsert(
                href,
                "/remote.php/dav/calendars/synthetic/team/",
                "planning",
                "\"old\"",
                blankOptionalDraft,
            ).isSatisfiedBy(blankOptionalResponse),
        )
        assertFalse(deletion.isSatisfiedBy(updated))
        assertTrue(deletion.isSatisfiedBy(missing))
        assertTrue(deletion.isSatisfiedBy(missing.copy(status = 410)))

        val session = NextcloudSession(
            serverUrl = "https://cloud.example.test/nextcloud",
            loginName = "person",
            appPassword = "secret",
        )
        val accountScope = durableMutationAccountScope(session)
        assertEquals(64, accountScope.length)
        assertTrue(accountScope.all { it in '0'..'9' || it in 'a'..'f' })
        assertFalse(accountScope.contains("cloud.example.test"))
        assertFalse(accountScope.contains("person"))
        val encoded = CalendarMutationRecoveryState(accountScope, upsert).encodeForSavedState()
        assertEquals(upsert, decodeCalendarMutationRecoveryState(encoded, accountScope))
        assertNull(decodeCalendarMutationRecoveryState(encoded, "$accountScope-other"))
        assertNull(decodeCalendarMutationRecoveryState("not-json", accountScope))
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
        end: String? = null,
        allDay: Boolean = false,
        location: String? = null,
        description: String? = null,
    ) = GroupwareCalendarEvent(
        href = "/remote.php/dav/calendars/synthetic/$calendarId/${title.lowercase().replace(' ', '-')}.ics",
        etag = "\"synthetic\"",
        calendarHref = "/remote.php/dav/calendars/synthetic/$calendarId/",
        uid = "synthetic-$calendarId-$start",
        title = title,
        start = start,
        end = end ?: start.take(8) + "T100000Z",
        allDay = allDay,
        location = location,
        description = description,
        rawCalendar = "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n",
    )
}
