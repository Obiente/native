package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CalendarEventSchedulingTest {
    private val calendarHref = "/remote.php/dav/calendars/alice/personal/"
    private val href = "${calendarHref}standup.ics"
    private val alice = CalendarUserIdentity("Alice Example", listOf("alice@example.test"))

    @Test
    fun contentLinesKeepQuotedParameterValuesWithSeparators() {
        val line = assertNotNull(parseICalendarContentLine("""ATTENDEE;CN="Doe, Jane: QA";PARTSTAT=ACCEPTED:mailto:jane@example.test"""))
        assertEquals("ATTENDEE", line.name)
        assertEquals("Doe, Jane: QA", line.parameter("cn"))
        assertEquals("mailto:jane@example.test", line.value)
        assertEquals(line, parseICalendarContentLine(line.format()))
        assertNull(parseICalendarContentLine("""ATTENDEE;CN="unterminated:mailto:x@example.test"""))
        assertNull(parseICalendarContentLine("no separator"))
    }

    @Test
    fun aReminderDescriptionIsNotReadAsTheEventDescription() {
        val event = event(alarms = listOf("BEGIN:VALARM", "ACTION:DISPLAY", "DESCRIPTION:Standup", "TRIGGER:-PT5M", "END:VALARM"))
        assertNull(event.description)
        assertEquals("Standup", event.title)
    }

    @Test
    fun reminderDurationsParseOnlyWholeMinutes() {
        assertEquals(-15, "-PT15M".parseReminderOffsetMinutes())
        assertEquals(-1_440, "-P1D".parseReminderOffsetMinutes())
        assertEquals(-10_080, "-P1W".parseReminderOffsetMinutes())
        assertEquals(-1_500, "-P1DT1H".parseReminderOffsetMinutes())
        assertEquals(0, "PT0S".parseReminderOffsetMinutes())
        assertEquals(30, "PT30M".parseReminderOffsetMinutes())
        assertNull("-PT30S".parseReminderOffsetMinutes())
        assertNull("-P".parseReminderOffsetMinutes())
        assertNull("-PT".parseReminderOffsetMinutes())
        assertNull("soon".parseReminderOffsetMinutes())
        assertEquals("-PT15M", 15.reminderTriggerDuration())
        assertEquals("-P1D", 1_440.reminderTriggerDuration())
        assertEquals("PT0S", 0.reminderTriggerDuration())
        assertEquals("PT30M", (-30).reminderTriggerDuration())
    }

    @Test
    fun remindersAreReadAndOnlyEditableOnesAreReplaced() {
        val event = event(
            alarms = listOf(
                "BEGIN:VALARM", "ACTION:EMAIL", "TRIGGER:-PT1H", "END:VALARM",
                "BEGIN:VALARM", "ACTION:DISPLAY", "TRIGGER:-PT15M", "END:VALARM",
                "BEGIN:VALARM", "ACTION:DISPLAY", "TRIGGER;VALUE=DATE-TIME:20261101T080000Z", "END:VALARM",
            ),
        )
        assertEquals(listOf(15, 60), event.reminderMinutes())
        assertEquals(1, event.reminders().count { !it.editable })

        val content = applyCalendarEventExtras(event.rawCalendar, event.uid, null, null, draft(reminders = listOf(60, 1_440)), alice)
        val updated = reparse(content)
        assertEquals(listOf(60, 1_440), updated.reminderMinutes())
        assertTrue("ACTION:EMAIL" in content, "An existing reminder at a kept offset keeps its action.")
        assertTrue("TRIGGER;VALUE=DATE-TIME:20261101T080000Z" in content, "A fixed-time reminder is never removed.")
        assertTrue("TRIGGER:-P1D" in content)
        assertFalse("TRIGGER:-PT15M" in content)
    }

    @Test
    fun invitationsReadOrganizerGuestsAndTheUsersOwnEntry() {
        val event = event(
            properties = listOf(
                "ORGANIZER;CN=Bob:mailto:bob@example.test",
                "ATTENDEE;CN=Alice Example;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:Alice@Example.test",
                "ATTENDEE;PARTSTAT=ACCEPTED;ROLE=OPT-PARTICIPANT:mailto:carol@example.test",
                "ATTENDEE;CUTYPE=ROOM:mailto:room@example.test",
            ),
        )
        val invitation = event.invitation()
        assertEquals("bob@example.test", invitation.organizer?.address)
        assertEquals(3, invitation.attendees.size)
        assertTrue(invitation.attendees[1].optional)
        assertTrue(invitation.attendees[2].nonPerson)
        assertEquals("alice@example.test", invitation.invitationFor(alice)?.address)
        assertFalse(invitation.canManageAttendees(alice))
        assertNull(invitation.invitationFor(null))
    }

    @Test
    fun answeringAnInvitationChangesOnlyTheUsersParticipation() {
        val event = event(
            properties = listOf(
                "ORGANIZER:mailto:bob@example.test",
                "ATTENDEE;CN=\"Example, Alice\";PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:alice@example.test",
                "ATTENDEE;PARTSTAT=NEEDS-ACTION:mailto:carol@example.test",
            ),
        )
        val content = respondToCalendarEventContent(event, alice, CalendarParticipation.Accepted)
        val updated = reparse(content).invitation()
        assertEquals(CalendarParticipation.Accepted, updated.attendees.first { it.address == "alice@example.test" }.participation)
        assertEquals(CalendarParticipation.NeedsAction, updated.attendees.first { it.address == "carol@example.test" }.participation)
        assertEquals("Example, Alice", updated.attendees.first().name)

        val postcondition = CalendarMutationPostcondition.Respond(href, calendarHref, "standup", null, "alice@example.test", "ACCEPTED")
        assertTrue(postcondition.isSatisfiedBy(ok(content)))
        assertFalse(postcondition.isSatisfiedBy(ok(event.rawCalendar)))
        assertFalse(postcondition.isSatisfiedBy(NextcloudApiResponse(404, byteArrayOf(), null, null)))

        val stranger = CalendarUserIdentity(null, listOf("dave@example.test"))
        assertFailsWith<IllegalArgumentException> { respondToCalendarEventContent(event, stranger, CalendarParticipation.Accepted) }
    }

    @Test
    fun theFirstGuestMakesTheUserOrganizerAndTheLastRemovesIt() {
        val event = event()
        val invited = applyCalendarEventExtras(event.rawCalendar, event.uid, null, null,
            draft(attendees = listOf("Carol@Example.test", "dave@example.test")), alice)
        val invitation = reparse(invited).invitation()
        assertEquals("alice@example.test", invitation.organizer?.address)
        assertEquals("Alice Example", invitation.organizer?.name)
        assertEquals(listOf("carol@example.test", "dave@example.test"), invitation.attendees.map { it.address })
        assertTrue("SEQUENCE:1" in invited)

        val carolAccepted = invited.replace("PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:carol", "PARTSTAT=ACCEPTED;RSVP=TRUE:mailto:carol")
        val trimmed = applyCalendarEventExtras(carolAccepted, event.uid, null, null, draft(attendees = listOf("carol@example.test")), alice)
        val kept = reparse(trimmed).invitation()
        assertEquals(CalendarParticipation.Accepted, kept.attendees.single().participation, "A kept guest keeps their answer.")
        assertTrue("SEQUENCE:2" in trimmed)

        val cleared = applyCalendarEventExtras(trimmed, event.uid, null, null, draft(attendees = emptyList()), alice)
        assertNull(reparse(cleared).invitation().organizer)
        assertFalse(reparse(cleared).invitation().isMeeting)
    }

    @Test
    fun anInvitedPersonsRemindersDoNotRaiseTheMeetingSequence() {
        val event = event(properties = listOf("ORGANIZER:mailto:bob@example.test", "ATTENDEE:mailto:alice@example.test", "SEQUENCE:3"))
        val content = applyCalendarEventExtras(event.rawCalendar, event.uid, null, null, draft(reminders = listOf(10)), alice)
        assertTrue("SEQUENCE:3" in content)
        assertEquals(listOf(10), reparse(content).reminderMinutes())
    }

    @Test
    fun guestListsAreValidatedAndOnlyTheOrganizerMayChangeThem() {
        val event = event(properties = listOf("ORGANIZER:mailto:bob@example.test", "ATTENDEE:mailto:alice@example.test"))
        assertFailsWith<IllegalArgumentException> {
            applyCalendarEventExtras(event.rawCalendar, event.uid, null, null, draft(attendees = listOf("x@example.test")), alice)
        }
        assertFailsWith<IllegalArgumentException> {
            applyCalendarEventExtras(event().rawCalendar, "standup", null, null, draft(attendees = listOf("not an address")), alice)
        }
        assertFailsWith<IllegalArgumentException> {
            applyCalendarEventExtras(event().rawCalendar, "standup", null, null, draft(attendees = listOf("x@example.test")),
                CalendarUserIdentity("No mail", emptyList()))
        }
        val withUrn = event(properties = listOf("ORGANIZER:mailto:alice@example.test", "ATTENDEE:urn:uuid:resource-1"))
        val content = applyCalendarEventExtras(withUrn.rawCalendar, "standup", null, null, draft(attendees = emptyList()), alice)
        assertTrue("ATTENDEE:urn:uuid:resource-1" in content, "An attendee that cannot be shown is never dropped.")
        assertTrue("ORGANIZER:mailto:alice@example.test" in content)
    }

    @Test
    fun deletingOneOccurrenceAddsAnExdateLikeTheSeriesStartAndDropsItsException() {
        val series = event(
            start = "DTSTART;TZID=Europe/Berlin:20261102T090000",
            properties = listOf("RRULE:FREQ=WEEKLY"),
            extraComponents = listOf(
                "BEGIN:VEVENT", "UID:standup", "RECURRENCE-ID;TZID=Europe/Berlin:20261109T090000",
                "DTSTART;TZID=Europe/Berlin:20261109T100000", "SUMMARY:Moved standup", "END:VEVENT",
            ),
        )
        val occurrence = series.copy(recurrenceId = "20261109T090000", isGeneratedOccurrence = true)
        val content = excludeCalendarOccurrenceContent(occurrence)
        assertTrue("EXDATE;TZID=Europe/Berlin:20261109T090000" in content)
        assertFalse("Moved standup" in content)
        val postcondition = CalendarMutationPostcondition.ExcludeOccurrence(href, calendarHref, "standup", "20261109T090000")
        assertTrue(postcondition.isSatisfiedBy(ok(content)))
        assertFalse(postcondition.isSatisfiedBy(ok(series.rawCalendar)))

        val first = excludeCalendarOccurrenceContent(series)
        assertTrue("EXDATE;TZID=Europe/Berlin:20261102T090000" in first, "The first occurrence is named by the series start.")
        assertFailsWith<IllegalArgumentException> { excludeCalendarOccurrenceContent(event()) }
    }

    @Test
    fun editingOneOccurrenceCreatesAnExceptionWithoutTheRule() {
        val series = event(start = "DTSTART;VALUE=DATE:20261102", allDay = true, properties = listOf("RRULE:FREQ=DAILY;COUNT=5", "EXDATE;VALUE=DATE:20261104"))
        val occurrence = assertNotNull(series.copy(recurrenceId = "20261103", isGeneratedOccurrence = true).asSingleOccurrence())
        val draft = draft(title = "Holiday", date = "2026-11-03", allDay = true, reminders = listOf(60))
        val content = applyCalendarEventDraftContent(occurrence, draft, CalendarChangeScope.Occurrence, alice)
        val components = parseGroupwareCalendarEventsFromContent(calendarHref, href, "\"2\"", content)
        val master = components.single { it.recurrenceId == null }
        val exception = components.single { it.recurrenceId == "20261103" }
        assertEquals("Standup", master.title)
        assertEquals("FREQ=DAILY;COUNT=5", master.recurrenceRule)
        assertEquals("Holiday", exception.title)
        assertNull(exception.recurrenceRule)
        assertTrue(exception.excludedStarts.isEmpty())
        assertTrue("RECURRENCE-ID;VALUE=DATE:20261103" in content)
        assertEquals(listOf(60), exception.reminderMinutes())

        val postcondition = CalendarMutationPostcondition.Upsert(href, calendarHref, "standup", "\"1\"", draft, "20261103")
        assertTrue(postcondition.isSatisfiedBy(ok(content)))
        assertFalse(postcondition.copy(expectedRecurrenceId = null).isSatisfiedBy(ok(content)))

        // A second edit of the same occurrence changes its exception rather than adding another.
        val again = applyCalendarEventDraftContent(
            occurrence.copy(rawCalendar = content), draft.copy(title = "Day off"), CalendarChangeScope.Occurrence, alice,
        )
        assertEquals(1, parseGroupwareCalendarEventsFromContent(calendarHref, href, null, again).count { it.recurrenceId == "20261103" })
        assertTrue("SUMMARY:Day off" in again)
    }

    @Test
    fun aSeriesEditKeepsExceptionsAndASingleEventNeedsNoScope() {
        val single = event()
        assertFalse(single.isPartOfSeries)
        assertNull(single.occurrenceId())
        assertEquals(single, single.editableFor(CalendarChangeScope.Occurrence))
        val series = event(properties = listOf("RRULE:FREQ=WEEKLY"))
        assertTrue(series.isPartOfSeries)
        assertEquals("20261102T090000Z", series.occurrenceId())
        assertNotNull(series.seriesMaster())
    }

    @Test
    fun upsertPostconditionChecksRemindersAndGuestsOnlyWhenTheyWereChanged() {
        val event = event(alarms = listOf("BEGIN:VALARM", "ACTION:DISPLAY", "TRIGGER:-PT15M", "END:VALARM"))
        val base = CalendarMutationPostcondition.Upsert(href, calendarHref, "standup", "\"1\"", draft())
        assertTrue(base.isSatisfiedBy(ok(event.rawCalendar)))
        assertTrue(base.copy(draft = draft(reminders = listOf(15))).isSatisfiedBy(ok(event.rawCalendar)))
        assertFalse(base.copy(draft = draft(reminders = listOf(30))).isSatisfiedBy(ok(event.rawCalendar)))
        assertFalse(base.copy(draft = draft(attendees = listOf("x@example.test"))).isSatisfiedBy(ok(event.rawCalendar)))
    }

    @Test
    fun theUsersCalendarAddressesComeFromThePrincipal() {
        val identity = parseCalendarUserIdentity(
            ok(
                """
                <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:response>
                  <d:href>/remote.php/dav/principals/users/alice/</d:href>
                  <d:propstat><d:prop><d:displayname>Alice &amp; Co</d:displayname>
                    <c:calendar-user-address-set>
                      <d:href>mailto:Alice@Example.test</d:href>
                      <d:href>/remote.php/dav/principals/users/alice/</d:href>
                      <d:href>mailto:not valid</d:href>
                    </c:calendar-user-address-set>
                  </d:prop></d:propstat>
                </d:response></d:multistatus>
                """.trimIndent(),
            ),
        )
        assertEquals("Alice & Co", identity.displayName)
        assertEquals(listOf("alice@example.test"), identity.addresses)
        assertFailsWith<IllegalArgumentException> { parseCalendarUserIdentity(NextcloudApiResponse(403, byteArrayOf(), null, null)) }
        assertEquals("/remote.php/dav/principals/users/a%20b/", calendarUserIdentityRequest("a b").relativePath)
    }

    private fun draft(
        title: String = "Standup",
        date: String = "2026-11-02",
        allDay: Boolean = false,
        reminders: List<Int>? = null,
        attendees: List<String>? = null,
    ) = EventDraft(
        title = title, date = date, startTime = "09:00", endTime = "09:15", allDay = allDay,
        location = "", description = "", recurrenceRule = null, reminderMinutes = reminders, attendees = attendees,
    )

    private fun event(
        start: String = "DTSTART:20261102T090000Z",
        allDay: Boolean = false,
        properties: List<String> = emptyList(),
        alarms: List<String> = emptyList(),
        extraComponents: List<String> = emptyList(),
    ): GroupwareCalendarEvent {
        val end = if (allDay) "DTEND;VALUE=DATE:20261103" else "DTEND:20261102T091500Z"
        val content = (
            listOf("BEGIN:VCALENDAR", "VERSION:2.0", "BEGIN:VEVENT", "UID:standup", start, end, "SUMMARY:Standup") +
                properties + alarms + listOf("END:VEVENT") + extraComponents + listOf("END:VCALENDAR")
            ).joinToString("\r\n", postfix = "\r\n")
        return reparse(content)
    }

    private fun reparse(content: String): GroupwareCalendarEvent =
        assertNotNull(parseGroupwareCalendarEvent(calendarHref, href, "\"1\"", content))

    private fun ok(body: String) = NextcloudApiResponse(200, body.encodeToByteArray(), "text/calendar", "\"2\"")
}
