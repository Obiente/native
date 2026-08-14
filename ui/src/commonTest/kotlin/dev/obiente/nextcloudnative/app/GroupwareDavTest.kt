package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupwareDavTest {
    @Test
    fun `only authoritative client errors release mutation recovery`() {
        assertFalse(groupwareMutationResponseProvesRejection(204))
        assertTrue(groupwareMutationResponseProvesRejection(409))
        assertTrue(groupwareMutationResponseProvesRejection(412))
        assertFalse(groupwareMutationResponseProvesRejection(408))
        assertFalse(groupwareMutationResponseProvesRejection(499))
        assertFalse(groupwareMutationResponseProvesRejection(500))
        assertFalse(groupwareMutationResponseProvesRejection(504))
        assertTrue(groupwareDeleteResponseProvesAbsence(404))
        assertTrue(groupwareDeleteResponseProvesAbsence(410))
        assertFalse(groupwareDeleteResponseProvesAbsence(409))
    }

    @Test
    fun `contact navigation detects every editable draft field`() {
        val initial = ContactDraft(
            name = "Alex Example",
            email = "alex@example.test",
            phone = "+31 6 123",
            organization = "Example",
            address = "Main Street 1",
            notes = "Planning contact",
        )

        assertFalse(contactDraftIsDirty(initial, initial.copy(), "personal", "personal"))
        listOf(
            initial.copy(name = "Alexandra Example"),
            initial.copy(email = "other@example.test"),
            initial.copy(phone = "+31 6 456"),
            initial.copy(organization = "Other"),
            initial.copy(address = "Side Street 2"),
            initial.copy(notes = "Updated notes"),
        ).forEach { changed ->
            assertTrue(contactDraftIsDirty(initial, changed, "personal", "personal"))
        }
        assertTrue(contactDraftIsDirty(initial, initial, "personal", "team"))

        val href = "/remote.php/dav/addressbooks/users/person/contacts/alex.vcf"
        val stale = NextcloudApiResponse(
            status = 200,
            contentType = "text/vcard",
            etag = "\"old\"",
            body = createGroupwareContactContent(
                uid = "alex",
                displayName = "Old name",
                email = "",
                phone = "",
                organization = "",
                address = "",
                notes = "",
            ).encodeToByteArray(),
        )
        val updated = stale.copy(
            etag = "\"new\"",
            body = createGroupwareContactContent(
                uid = "alex",
                displayName = initial.name,
                email = initial.email,
                phone = initial.phone,
                organization = initial.organization,
                address = initial.address,
                notes = initial.notes,
            ).encodeToByteArray(),
        )
        val missing = updated.copy(status = 404, body = byteArrayOf())
        val upsert = ContactMutationPostcondition.Upsert(
            href,
            "/remote.php/dav/addressbooks/users/person/contacts/",
            "alex",
            "\"old\"",
            initial,
        )
        val deletion = ContactMutationPostcondition.Delete(href)
        assertFalse(upsert.isSatisfiedBy(stale))
        assertTrue(upsert.isSatisfiedBy(updated))
        assertFalse(deletion.isSatisfiedBy(updated))
        assertTrue(deletion.isSatisfiedBy(missing))

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
        assertFailsWith<IllegalArgumentException> {
            ContactMutationRecoveryState("https://cloud.example.test|person|person-id", upsert)
        }
        val encoded = ContactMutationRecoveryState(accountScope, upsert).encodeForSavedState()
        assertEquals(upsert, decodeContactMutationRecoveryState(encoded, accountScope))
        assertNull(decodeContactMutationRecoveryState(encoded, "$accountScope-other"))
        assertNull(decodeContactMutationRecoveryState("not-json", accountScope))
    }

    @Test
    fun `contact mutation reconciliation uses the values normalized by the vCard writer`() {
        val href = "/remote.php/dav/addressbooks/users/person/contacts/alex.vcf"
        val draft = ContactDraft(
            name = "Alex Example",
            email = "  alex@example.test  ",
            phone = "  +31 6 123  ",
            organization = "  Example  ",
            address = "  Suite A; Building B  ",
            notes = "  Planning contact  ",
        )
        val response = NextcloudApiResponse(
            status = 200,
            contentType = "text/vcard",
            etag = "\"new\"",
            body = createGroupwareContactContent(
                uid = "alex",
                displayName = draft.name,
                email = draft.email,
                phone = draft.phone,
                organization = draft.organization,
                address = draft.address,
                notes = draft.notes,
            ).encodeToByteArray(),
        )

        assertTrue(
            ContactMutationPostcondition.Upsert(
                href = href,
                addressBookHref = "/remote.php/dav/addressbooks/users/person/contacts/",
                expectedUid = "alex",
                previousEtag = "\"old\"",
                draft = draft,
            ).isSatisfiedBy(response),
        )
        assertEquals(
            "Suite A; Building B",
            parseGroupwareContact(
                addressBookHref = "/remote.php/dav/addressbooks/users/person/contacts/",
                href = href,
                etag = response.etag,
                content = response.body.decodeToString(),
            )?.address,
        )
    }

    @Test
    fun `vcard text decoding consumes escapes once and preserves literal organization semicolons`() {
        val href = "/remote.php/dav/addressbooks/users/person/contacts/escape.vcf"
        val notes = "Path C:\\new, archive; ready"
        val organization = "Example;"
        val content = createGroupwareContactContent(
            uid = "escape",
            displayName = "Escape Example",
            email = null,
            phone = null,
            organization = organization,
            address = null,
            notes = notes,
        )

        val parsed = requireNotNull(
            parseGroupwareContact(
                addressBookHref = "/remote.php/dav/addressbooks/users/person/contacts/",
                href = href,
                etag = "\"new\"",
                content = content,
            ),
        )

        assertEquals(notes, parsed.notes)
        assertEquals(organization, parsed.organization)
        assertEquals(
            "Example",
            parseGroupwareContact(
                addressBookHref = parsed.addressBookHref,
                href = href,
                etag = parsed.etag,
                content = content.replace("ORG:Example\\;", "ORG:Example;"),
            )?.organization,
        )
        assertTrue(
            ContactMutationPostcondition.Upsert(
                href = href,
                addressBookHref = parsed.addressBookHref,
                expectedUid = parsed.uid,
                previousEtag = "\"old\"",
                draft = ContactDraft(
                    name = parsed.displayName,
                    email = "",
                    phone = "",
                    organization = organization,
                    address = "",
                    notes = notes,
                ),
            ).isSatisfiedBy(
                NextcloudApiResponse(
                    status = 200,
                    contentType = "text/vcard",
                    etag = parsed.etag,
                    body = content.encodeToByteArray(),
                ),
            ),
        )
    }

    @Test
    fun `carddav discovery and contact report become native semantic records`() {
        val discovery = NextcloudApiResponse(
            status = 207,
            contentType = "application/xml",
            etag = null,
            body = """
                <d:multistatus xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav">
                  <d:response><d:href>/remote.php/dav/addressbooks/users/person/contacts/</d:href>
                    <d:propstat><d:prop>
                      <d:displayname>People &amp; teams</d:displayname>
                      <d:resourcetype><d:collection/><card:addressbook/></d:resourcetype>
                      <d:current-user-privilege-set>
                        <d:privilege><d:read/></d:privilege>
                        <d:privilege><d:write-content/></d:privilege>
                      </d:current-user-privilege-set>
                    </d:prop></d:propstat>
                  </d:response>
                </d:multistatus>
            """.trimIndent().encodeToByteArray(),
        )
        val addressBook = parseGroupwareAddressBooks(discovery).single()
        val report = NextcloudApiResponse(
            status = 207,
            contentType = "application/xml",
            etag = null,
            body = """
                <d:multistatus xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav">
                  <d:response><d:href>/remote.php/dav/addressbooks/users/person/contacts/alex.vcf</d:href>
                    <d:propstat><d:prop>
                      <d:getetag>&quot;contact-etag&quot;</d:getetag>
                      <card:address-data>BEGIN:VCARD
VERSION:4.0
UID:alex
FN:Alex Example
EMAIL;TYPE=work:alex@example.test
EMAIL;TYPE=home:other@example.test
TEL;TYPE=cell:+31 6 123
ORG:Obiente
NOTE:Works across 
 folded lines
X-CUSTOM:preserve-me
END:VCARD</card:address-data>
                    </d:prop></d:propstat>
                  </d:response>
                </d:multistatus>
            """.trimIndent().encodeToByteArray(),
        )
        val contact = parseGroupwareContacts(addressBook.href, report).single()

        assertEquals("People & teams", addressBook.displayName)
        assertTrue(addressBook.writable)
        assertEquals("Alex Example", contact.displayName)
        assertEquals(listOf("alex@example.test", "other@example.test"), contact.emails)
        assertEquals("+31 6 123", contact.phones.single())
        assertEquals("Works across folded lines", contact.notes)
        assertEquals("\"contact-etag\"", contact.etag)
    }

    @Test
    fun `contact update preserves extra values and unknown vcard properties`() {
        val original = requireNotNull(
            parseGroupwareContact(
                addressBookHref = "/remote.php/dav/addressbooks/users/person/contacts/",
                href = "/remote.php/dav/addressbooks/users/person/contacts/alex.vcf",
                etag = "\"etag\"",
                content = """
                    BEGIN:VCARD
                    VERSION:4.0
                    UID:alex
                    FN:Alex Example
                    N:Example;Alex;;;
                    EMAIL;TYPE=work:old@example.test
                    EMAIL;TYPE=home:private@example.test
                    PHOTO:data:image/png;base64,opaque
                    END:VCARD
                """.trimIndent(),
            ),
        )

        val updated = updateGroupwareContactContent(
            contact = original,
            displayName = "Alex Updated",
            email = "new@example.test",
            phone = "+31 6 456",
            organization = "Obiente",
            address = null,
            notes = "Native",
        )

        assertTrue("FN:Alex Updated" in updated)
        assertTrue("EMAIL:new@example.test" in updated)
        assertTrue("EMAIL;TYPE=home:private@example.test" in updated)
        assertTrue("PHOTO:data:image/png;base64,opaque" in updated)
        assertTrue("NOTE:Native" in updated)
        val request = GroupwareDavMutationSpec(
            kind = GroupwareDavKind.Contact,
            mutation = GroupwareDavMutation.Update,
            objectHref = original.href,
            etag = original.etag,
            content = updated,
        ).toGroupwareDavRequest()
        assertEquals("\"etag\"", request.headers["If-Match"])
    }

    @Test
    fun `carddav numeric carriage return entities are decoded before vcard parsing`() {
        val response = NextcloudApiResponse(
            status = 207,
            contentType = "application/xml",
            etag = null,
            body = """
                <d:multistatus xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav">
                  <d:response>
                    <d:href>/remote.php/dav/addressbooks/users/person/contacts/numeric.vcf</d:href>
                    <d:propstat><d:prop>
                      <card:address-data>BEGIN:VCARD&#13;
VERSION:4.0&#x0D;
UID:numeric&#13;
FN:Numeric Entity&#13;
END:VCARD&#13;</card:address-data>
                    </d:prop></d:propstat>
                  </d:response>
                </d:multistatus>
            """.trimIndent().encodeToByteArray(),
        )

        val contact = parseGroupwareContacts(
            "/remote.php/dav/addressbooks/users/person/contacts/",
            response,
        ).single()

        assertEquals("Numeric Entity", contact.displayName)
    }

    @Test
    fun `nextcloud calendar multistatus becomes semantic writable calendars`() {
        val response = NextcloudApiResponse(
            status = 207,
            contentType = "application/xml",
            etag = null,
            body = """
                <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                  <d:response>
                    <d:href>/remote.php/dav/calendars/opaque-user/personal/</d:href>
                    <d:propstat><d:prop>
                      <d:displayname>Personal &amp; family</d:displayname>
                      <d:resourcetype><d:collection/><c:calendar/></d:resourcetype>
                      <d:current-user-privilege-set>
                        <d:privilege><d:read/></d:privilege>
                        <d:privilege><d:write-content/></d:privilege>
                      </d:current-user-privilege-set>
                      <c:supported-calendar-component-set>
                        <c:comp name="VCALENDAR"><c:comp name="VEVENT"/></c:comp>
                      </c:supported-calendar-component-set>
                    </d:prop></d:propstat>
                  </d:response>
                  <d:response>
                    <d:href>/remote.php/dav/calendars/opaque-user/tasks/</d:href>
                    <d:propstat><d:prop>
                      <d:displayname>Tasks</d:displayname>
                      <d:resourcetype><d:collection/><c:calendar/></d:resourcetype>
                      <c:supported-calendar-component-set>
                        <c:comp name="VCALENDAR"><c:comp name="VTODO"/></c:comp>
                      </c:supported-calendar-component-set>
                    </d:prop></d:propstat>
                  </d:response>
                </d:multistatus>
            """.trimIndent().encodeToByteArray(),
        )

        val calendars = parseGroupwareCalendars(response)

        assertEquals(1, calendars.size)
        assertEquals("Personal & family", calendars.single().displayName)
        assertTrue(calendars.single().writable)
    }

    @Test
    fun `calendar report parses folded escaped and all-day event data`() {
        val response = NextcloudApiResponse(
            status = 207,
            contentType = "application/xml",
            etag = null,
            body = """
                <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                  <d:response>
                    <d:href>/remote.php/dav/calendars/opaque-user/personal/leap.ics</d:href>
                    <d:propstat><d:prop>
                      <d:getetag>&quot;event-etag&quot;</d:getetag>
                      <c:calendar-data>BEGIN:VCALENDAR
BEGIN:VEVENT
UID:leap-event
DTSTART;VALUE=DATE:20240229
DTEND;VALUE=DATE:20240301
SUMMARY:Leap day\, planning
DESCRIPTION:A long 
 folded note
LOCATION:Studio\; north
END:VEVENT
END:VCALENDAR</c:calendar-data>
                    </d:prop></d:propstat>
                  </d:response>
                </d:multistatus>
            """.trimIndent().encodeToByteArray(),
        )

        val event = parseGroupwareCalendarEvents(
            "/remote.php/dav/calendars/opaque-user/personal/",
            response,
        ).single()

        assertEquals("Leap day, planning", event.title)
        assertEquals("A long folded note", event.description)
        assertEquals("Studio; north", event.location)
        assertEquals("20240229", event.start)
        assertTrue(event.allDay)
        assertEquals("\"event-etag\"", event.etag)
    }

    @Test
    fun `calendar serializer escapes text and leap year month has 29 days`() {
        val content = createGroupwareCalendarEventContent(
            uid = "event-1",
            title = "Review, plan; repeat",
            start = "20240229",
            end = "20240301",
            allDay = true,
            description = "Line one\nLine two",
        )

        assertTrue("SUMMARY:Review\\, plan\\; repeat" in content)
        assertTrue("DESCRIPTION:Line one\\nLine two" in content)
        assertEquals(29, groupwareCalendarDaysInMonth(2024, 2))
        assertEquals(28, groupwareCalendarDaysInMonth(2025, 2))
    }

    @Test
    fun `calendar serializer creates preserves and removes recurrence safely`() {
        val content = createGroupwareCalendarEventContent(
            uid = "event-recurring",
            title = "Planning",
            start = "20260803T090000Z",
            end = "20260803T100000Z",
            allDay = false,
            recurrenceRule = "FREQ=WEEKLY;BYDAY=MO,WE",
        )
        assertTrue("RRULE:FREQ=WEEKLY;BYDAY=MO,WE" in content)

        val event = requireNotNull(
            parseGroupwareCalendarEvent(
                calendarHref = "/remote.php/dav/calendars/person/work/",
                href = "/remote.php/dav/calendars/person/work/event-recurring.ics",
                etag = "event-etag",
                content = content,
            ),
        )
        val preserved = updateGroupwareCalendarEventContent(
            event = event,
            title = event.title,
            start = event.start,
            end = event.end,
            allDay = event.allDay,
            location = event.location,
            description = event.description,
        )
        assertTrue("RRULE:FREQ=WEEKLY;BYDAY=MO,WE" in preserved)

        val removed = updateGroupwareCalendarEventContent(
            event = event,
            title = event.title,
            start = event.start,
            end = event.end,
            allDay = event.allDay,
            location = event.location,
            description = event.description,
            recurrenceRule = null,
        )
        assertFalse("RRULE:" in removed)
        assertFailsWith<IllegalArgumentException> {
            createGroupwareCalendarEventContent(
                uid = "invalid-recurrence",
                title = "Unsafe",
                start = "20260803T090000Z",
                end = null,
                allDay = false,
                recurrenceRule = "FREQ=WEEKLY\nATTENDEE:mailto:other@example.invalid",
            )
        }
    }

    @Test
    fun `calendar writes reject recurrence rules the local expander cannot reproduce`() {
        listOf(
            "FREQ=YEARLY",
            "FREQ=HOURLY",
            "FREQ=DAILY;BYDAY=MO",
            "FREQ=WEEKLY;BYDAY=1MO",
            "FREQ=MONTHLY;BYDAY=1MO;BYMONTHDAY=1",
            "FREQ=MONTHLY;BYSETPOS=1;BYDAY=MO",
            "FREQ=WEEKLY;INTERVAL=zero",
            "FREQ=DAILY;COUNT=3;UNTIL=20260830T090000Z",
        ).forEach { rule ->
            assertFalse(isSupportedCalendarRecurrenceRuleForWrite(rule), rule)
            assertFailsWith<IllegalArgumentException>(rule) {
                createGroupwareCalendarEventContent(
                    uid = "unsupported-recurrence",
                    title = "Planning",
                    start = "20260803T090000Z",
                    end = null,
                    allDay = false,
                    recurrenceRule = rule,
                )
            }
        }
        listOf(
            "FREQ=DAILY;INTERVAL=2;COUNT=4",
            "FREQ=WEEKLY;BYDAY=MO,WE;WKST=MO",
            "FREQ=MONTHLY;BYDAY=1MO;UNTIL=20261231T235959Z",
            "FREQ=MONTHLY;BYMONTHDAY=-1",
        ).forEach { rule ->
            assertTrue(isSupportedCalendarRecurrenceRuleForWrite(rule), rule)
        }
    }

    @Test
    fun `daily recurrence respects interval count exclusions and stable identities`() {
        val master = calendarEvent(
            """
                BEGIN:VCALENDAR
                BEGIN:VEVENT
                UID:daily-series
                DTSTART:20260701T090000Z
                DTEND:20260701T100000Z
                SUMMARY:Standup
                RRULE:FREQ=DAILY;INTERVAL=2;COUNT=4
                EXDATE:20260705T090000Z
                END:VEVENT
                END:VCALENDAR
            """.trimIndent(),
        )

        val expanded = expandGroupwareCalendarEvents(
            listOf(master),
            GroupwareDavTimeWindow("20260701T000000Z", "20260801T000000Z"),
        )

        assertEquals(
            listOf("20260701T090000Z", "20260703T090000Z", "20260707T090000Z"),
            expanded.map(GroupwareCalendarEvent::start),
        )
        assertEquals("20260707T100000Z", expanded.last().end)
        assertEquals(3, expanded.map(GroupwareCalendarEvent::instanceId).distinct().size)
        assertFalse(expanded.first().isGeneratedOccurrence)
        assertTrue(expanded.last().isGeneratedOccurrence)
    }

    @Test
    fun `weekly recurrence supports byday interval and until`() {
        val master = calendarEvent(
            """
                BEGIN:VCALENDAR
                BEGIN:VEVENT
                UID:weekly-series
                DTSTART:20260701T180000Z
                SUMMARY:Training
                RRULE:FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE;UNTIL=20260729T180000Z
                END:VEVENT
                END:VCALENDAR
            """.trimIndent(),
        )

        val expanded = expandGroupwareCalendarEvents(
            listOf(master),
            GroupwareDavTimeWindow("20260701T000000Z", "20260801T000000Z"),
        )

        assertEquals(
            listOf(
                "20260701T180000Z",
                "20260713T180000Z",
                "20260715T180000Z",
                "20260727T180000Z",
                "20260729T180000Z",
            ),
            expanded.map(GroupwareCalendarEvent::start),
        )
    }

    @Test
    fun `monthly recurrence supports missing month days and ordinal weekdays`() {
        val monthEnd = calendarEvent(
            """
                BEGIN:VCALENDAR
                BEGIN:VEVENT
                UID:month-end
                DTSTART;VALUE=DATE:20260131
                SUMMARY:Close books
                RRULE:FREQ=MONTHLY;BYMONTHDAY=31;COUNT=4
                END:VEVENT
                END:VCALENDAR
            """.trimIndent(),
            href = "/remote.php/dav/calendars/opaque-user/personal/month-end.ics",
        )
        val firstMonday = calendarEvent(
            """
                BEGIN:VCALENDAR
                BEGIN:VEVENT
                UID:first-monday
                DTSTART;VALUE=DATE:20260105
                SUMMARY:Planning
                RRULE:FREQ=MONTHLY;BYDAY=1MO;COUNT=4
                END:VEVENT
                END:VCALENDAR
            """.trimIndent(),
            href = "/remote.php/dav/calendars/opaque-user/personal/first-monday.ics",
        )

        val expanded = expandGroupwareCalendarEvents(
            listOf(monthEnd, firstMonday),
            GroupwareDavTimeWindow("20260101T000000Z", "20260601T000000Z"),
        )

        assertEquals(
            listOf("20260131", "20260331", "20260531"),
            expanded.filter { it.uid == "month-end" }.map(GroupwareCalendarEvent::start),
        )
        assertEquals(
            listOf("20260105", "20260202", "20260302", "20260406"),
            expanded.filter { it.uid == "first-monday" }.map(GroupwareCalendarEvent::start),
        )
    }

    @Test
    fun `detached override replaces an occurrence and remains mutation protected`() {
        val response = NextcloudApiResponse(
            status = 207,
            contentType = "application/xml",
            etag = null,
            body = """
                <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                  <d:response>
                    <d:href>/remote.php/dav/calendars/opaque-user/personal/series.ics</d:href>
                    <d:propstat><d:prop>
                      <d:getetag>&quot;series-etag&quot;</d:getetag>
                      <c:calendar-data>BEGIN:VCALENDAR
BEGIN:VEVENT
UID:series
DTSTART:20260701T090000Z
SUMMARY:Regular
RRULE:FREQ=WEEKLY;COUNT=3
END:VEVENT
BEGIN:VEVENT
UID:series
RECURRENCE-ID:20260708T090000Z
DTSTART:20260709T110000Z
SUMMARY:Moved
END:VEVENT
END:VCALENDAR</c:calendar-data>
                    </d:prop></d:propstat>
                  </d:response>
                </d:multistatus>
            """.trimIndent().encodeToByteArray(),
        )
        val parsed = parseGroupwareCalendarEvents(
            "/remote.php/dav/calendars/opaque-user/personal/",
            response,
        )

        val expanded = expandGroupwareCalendarEvents(
            parsed,
            GroupwareDavTimeWindow("20260701T000000Z", "20260801T000000Z"),
        )

        assertEquals(
            listOf("20260701T090000Z", "20260709T110000Z", "20260715T090000Z"),
            expanded.map(GroupwareCalendarEvent::start),
        )
        assertEquals("Moved", expanded[1].title)
        assertTrue(expanded[1].isGeneratedOccurrence)
        assertEquals(
            "/remote.php/dav/calendars/opaque-user/personal/series.ics#20260708T090000Z",
            expanded[1].instanceId,
        )
    }
    @Test
    fun `discovery follows server hrefs and requests groupware capabilities`() {
        val principal = groupwareDavPrincipalDiscoveryRequest()
        val home = groupwareDavHomeDiscoveryRequest("/remote.php/dav/principals/users/opaque-user/")
        val collections = groupwareDavCollectionDiscoveryRequest("/remote.php/dav/calendars/opaque-user/")

        assertEquals("PROPFIND", principal.method)
        assertEquals(0, principal.depth)
        assertTrue(principal.bodyText().contains("current-user-principal"))
        assertFalse(principal.bodyText().contains("opaque-user"))
        assertEquals("/remote.php/dav/principals/users/opaque-user/", home.relativePath)
        assertTrue(home.bodyText().contains("calendar-home-set"))
        assertTrue(home.bodyText().contains("addressbook-home-set"))
        assertEquals(1, collections.depth)
        assertTrue(collections.bodyText().contains("sync-token"))
        assertTrue(collections.bodyText().contains("supported-calendar-component-set"))
        assertTrue(collections.maximumResponseBytes <= 4L * 1024L * 1024L)
    }

    @Test
    fun `collection queries are bounded and retain contact event and task shapes`() {
        val contact = groupwareDavCollectionQueryRequest(
            "/remote.php/dav/addressbooks/users/opaque-user/contacts/",
            GroupwareDavKind.Contact,
            maxResults = 2,
        )
        val event = groupwareDavCollectionQueryRequest(
            "/remote.php/dav/calendars/opaque-user/personal/",
            GroupwareDavKind.Event,
            timeWindow = GroupwareDavTimeWindow("20260701T000000Z", "20260801T000000Z"),
        )
        val task = groupwareDavCollectionQueryRequest(
            "/remote.php/dav/calendars/opaque-user/tasks/",
            GroupwareDavKind.Task,
        )

        assertTrue(contact.bodyText().contains("addressbook-query"))
        assertTrue(contact.bodyText().contains("<card:nresults>2</card:nresults>"))
        assertTrue(event.bodyText().contains("name=\"VEVENT\""))
        assertTrue(event.bodyText().contains("start=\"20260701T000000Z\" end=\"20260801T000000Z\""))
        assertTrue(task.bodyText().contains("name=\"VTODO\""))
        assertFalse(task.bodyText().contains("time-range"))
        assertFailsWith<IllegalArgumentException> {
            groupwareDavCollectionQueryRequest(
                "/remote.php/dav/calendars/opaque-user/personal/",
                GroupwareDavKind.Event,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            groupwareDavCollectionQueryRequest(
                "/remote.php/dav/addressbooks/users/opaque-user/contacts/",
                GroupwareDavKind.Contact,
                maxResults = 251,
            )
        }
    }

    @Test
    fun `sync reports escape opaque tokens and stop on completion or repetition`() {
        val token = GroupwareDavSyncToken("https://cloud.example.test/token?a=1&b=<next>")
        val request = groupwareDavSyncRequest(
            "/remote.php/dav/calendars/opaque-user/personal/",
            token,
            maxResults = 25,
        )

        assertTrue(request.bodyText().contains("a=1&amp;b=&lt;next&gt;"))
        assertTrue(request.bodyText().contains("<d:nresults>25</d:nresults>"))

        val initial = GroupwareDavSyncProgress(token)
        val continued = advanceGroupwareDavSync(
            initial,
            GroupwareDavSyncToken("next-token"),
            truncated = true,
        )
        assertTrue(continued.canContinue)
        val repeated = advanceGroupwareDavSync(
            continued,
            token,
            truncated = true,
        )
        assertEquals(GroupwareDavSyncStopReason.RepeatedToken, repeated.stopReason)
        assertFalse(repeated.canContinue)

        val complete = advanceGroupwareDavSync(
            initial,
            GroupwareDavSyncToken("final-token"),
            truncated = false,
        )
        assertEquals(GroupwareDavSyncStopReason.Complete, complete.stopReason)
        assertNull(complete.token)
    }

    @Test
    fun `sync reports enforce a finite page cap`() {
        var progress = GroupwareDavSyncProgress(GroupwareDavSyncToken("token-0"))
        repeat(100) { index ->
            progress = advanceGroupwareDavSync(
                progress,
                GroupwareDavSyncToken("token-${index + 1}"),
                truncated = true,
            )
        }

        assertEquals(100, progress.loadedPages)
        assertEquals(GroupwareDavSyncStopReason.PageLimit, progress.stopReason)
        assertFalse(progress.canContinue)
    }

    @Test
    fun `unsafe or non discovered hrefs are rejected`() {
        listOf(
            "https://cloud.example.test/remote.php/dav/calendars/user/",
            "/remote.php/dav/calendars/user/../admin/",
            "/remote.php/dav/calendars/user/%2e%2e/admin/",
            "/remote.php/dav/calendars/user/?share=1",
            "/remote.php/dav/calendars/user/#fragment",
            "/remote.php/dav\\calendars\\user",
        ).forEach { href ->
            assertFailsWith<IllegalArgumentException> { groupwareDavDetailRequest(href) }
        }
    }

    @Test
    fun `write builders require conditional headers and matching content`() {
        val contact = GroupwareDavMutationSpec(
            kind = GroupwareDavKind.Contact,
            mutation = GroupwareDavMutation.Create,
            objectHref = "/remote.php/dav/addressbooks/users/opaque-user/contacts/new.vcf",
            content = "BEGIN:VCARD\r\nVERSION:4.0\r\nFN:New contact\r\nEND:VCARD\r\n",
        ).toGroupwareDavRequest()
        val task = GroupwareDavMutationSpec(
            kind = GroupwareDavKind.Task,
            mutation = GroupwareDavMutation.Update,
            objectHref = "/remote.php/dav/calendars/opaque-user/tasks/task.ics",
            etag = "\"opaque-etag\"",
            content = """
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VTODO
                UID:opaque
                SUMMARY:Task
                END:VTODO
                END:VCALENDAR
            """.trimIndent(),
        ).toGroupwareDavRequest()
        val delete = GroupwareDavMutationSpec(
            kind = GroupwareDavKind.Event,
            mutation = GroupwareDavMutation.Delete,
            objectHref = "/remote.php/dav/calendars/opaque-user/personal/event.ics",
            etag = "\"opaque-etag\"",
        ).toGroupwareDavRequest()

        assertEquals("PUT", contact.method)
        assertEquals("*", contact.headers["If-None-Match"])
        assertEquals("text/vcard; charset=utf-8", contact.contentType)
        assertEquals("\"opaque-etag\"", task.headers["If-Match"])
        assertEquals("DELETE", delete.method)
        assertNull(delete.body)
        assertFailsWith<IllegalArgumentException> {
            GroupwareDavMutationSpec(
                kind = GroupwareDavKind.Task,
                mutation = GroupwareDavMutation.Update,
                objectHref = "/remote.php/dav/calendars/opaque-user/tasks/task.ics",
                content = task.bodyText(),
            ).toGroupwareDavRequest()
        }
        assertFailsWith<IllegalArgumentException> {
            GroupwareDavMutationSpec(
                kind = GroupwareDavKind.Contact,
                mutation = GroupwareDavMutation.Create,
                objectHref = "/remote.php/dav/addressbooks/users/opaque-user/contacts/new.vcf",
                content = task.bodyText(),
            ).toGroupwareDavRequest()
        }
    }

    private fun GroupwareDavRequest.bodyText(): String = requireNotNull(body).decodeToString()

    private fun calendarEvent(
        content: String,
        href: String = "/remote.php/dav/calendars/opaque-user/personal/event.ics",
    ): GroupwareCalendarEvent = requireNotNull(
        parseGroupwareCalendarEvent(
            calendarHref = "/remote.php/dav/calendars/opaque-user/personal/",
            href = href,
            etag = "\"etag\"",
            content = content,
        ),
    )
}
