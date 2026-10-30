package dev.obiente.nextcloudnative.app

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupwareCalendarCollectionsTest {
    private val home = "/remote.php/dav/calendars/alice/"

    @Test
    fun discoveryReadsOwnedSharedAndSubscribedCalendarsAndSkipsTheTrashBin() {
        val response = multistatus(
            response(home, "<d:resourcetype><d:collection/></d:resourcetype>"),
            response(
                "${home}personal/",
                "<d:displayname>Personal</d:displayname><d:resourcetype><d:collection/><c:calendar/></d:resourcetype>" +
                    comps("VEVENT", "VTODO") + "<x1:calendar-color>#0082C9</x1:calendar-color>" +
                    "<oc:owner-principal>principals/users/alice</oc:owner-principal>" + writePrivilege,
            ),
            response(
                "${home}team_shared_by_bob/",
                "<d:displayname>Team</d:displayname><d:resourcetype><d:collection/><c:calendar/></d:resourcetype>" +
                    comps("VEVENT") + "<oc:owner-principal>principals/users/bob</oc:owner-principal>" +
                    "<nc:owner-displayname>Bob Example</nc:owner-displayname>",
            ),
            response(
                "${home}holidays/",
                "<d:displayname>Holidays</d:displayname><d:resourcetype><d:collection/><c:calendar/></d:resourcetype>" +
                    "<cs:source><d:href>webcal://holidays.example.org/feed.ics?lang=en&amp;year=2026</d:href></cs:source>" + writePrivilege,
            ),
            response(
                "${home}old/",
                "<d:displayname>Old</d:displayname><d:resourcetype><d:collection/><c:calendar/></d:resourcetype>" +
                    comps("VEVENT") + "<nc:deleted-at>2026-10-01T10:00:00+00:00</nc:deleted-at>",
            ),
            response("${home}inbox/", "<d:resourcetype><d:collection/><c:schedule-inbox/></d:resourcetype>"),
        )

        val events = parseGroupwareCalendars(response)
        assertEquals(listOf("Personal", "Team", "Holidays"), events.map { it.displayName })
        val personal = events[0]
        assertEquals(GroupwareCalendarKind.Owned, personal.kind)
        assertEquals("#0082C9", personal.color)
        assertTrue(personal.writable && personal.supportsTasks)
        val team = events[1]
        assertEquals(GroupwareCalendarKind.Shared, team.kind)
        assertEquals("Bob Example", team.ownerName)
        assertFalse(team.writable)
        val holidays = events[2]
        assertEquals(GroupwareCalendarKind.Subscription, holidays.kind)
        assertEquals("webcal://holidays.example.org/feed.ics?lang=en&year=2026", holidays.sourceUrl)
        assertFalse(holidays.writable, "A subscribed feed is never writable, whatever the privileges say")

        assertEquals(listOf("Personal"), parseGroupwareTaskCalendars(response).map { it.displayName })
        assertEquals(listOf("Personal", "Team", "Holidays"), parseGroupwareCalendarsForComponent(response, null).map { it.displayName })
    }

    @Test
    fun aMissingSourceInAFailedPropstatIsNotASubscription() {
        val response = multistatus(
            "<d:response><d:href>${home}work/</d:href>" +
                "<d:propstat><d:prop><d:displayname>Work</d:displayname><d:resourcetype><d:collection/><c:calendar/></d:resourcetype>" +
                comps("VEVENT") + "</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>" +
                "<d:propstat><d:prop><cs:source/><nc:deleted-at/></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat></d:response>",
        )
        val calendar = parseGroupwareCalendars(response).single()
        assertEquals(GroupwareCalendarKind.Owned, calendar.kind)
        assertNull(calendar.sourceUrl)
    }

    @Test
    fun newCalendarAddressesAreReadableUniqueAndInsideTheHome() {
        val href = newCalendarCollectionHref(home, "Family & Friends 2026!", Random(7))
        assertTrue(Regex("^/remote\\.php/dav/calendars/alice/family-friends-2026-[a-z0-9]{6}/$").matches(href), href)
        assertTrue(newCalendarCollectionHref(home, "日本", Random(7)).startsWith("${home}calendar-"))
        assertTrue(newCalendarCollectionHref(home, "x".repeat(200), Random(7)).removePrefix(home).length <= 48)
    }

    @Test
    fun createRequestsUseMkcalendarWithEscapedDetails() {
        val href = "${home}family-abc123/"
        val request = calendarCollectionCreateRequest(href, CalendarCollectionDraft("Tom & <Jerry>", "#2E9E5B", withTasks = true))
        assertEquals("MKCALENDAR", request.method)
        assertEquals(href, request.relativePath)
        val body = request.body!!.decodeToString()
        assertTrue("<d:displayname>Tom &amp; &lt;Jerry&gt;</d:displayname>" in body, body)
        assertTrue("""<c:comp name="VEVENT"/><c:comp name="VTODO"/>""" in body)
        assertFalse("VTODO" in calendarCollectionCreateRequest(href, CalendarCollectionDraft("Family", "#2E9E5B")).body!!.decodeToString())
    }

    @Test
    fun subscriptionsAcceptWebAddressesOnlyAndNeverCredentials() {
        assertEquals("webcal://example.org/a.ics", "  webcal://example.org/a.ics ".calendarSubscriptionUrlOrNull())
        assertEquals("https://example.org/a.ics?x=1&y=2", "https://example.org/a.ics?x=1&y=2".calendarSubscriptionUrlOrNull())
        listOf(
            "ftp://example.org/a.ics", "javascript:alert(1)", "https://user:secret@example.org/a.ics",
            "https:///a.ics", "https://exa mple.org/a.ics", "example.org/a.ics", "https://example.org/" + "a".repeat(2_100),
        ).forEach { assertNull(it.calendarSubscriptionUrlOrNull(), it) }

        val request = calendarSubscriptionCreateRequest(
            "${home}holidays-abc123/",
            CalendarCollectionDraft("Holidays", "#E9322D", sourceUrl = "https://example.org/a.ics?x=1&y=2"),
        )
        assertEquals("MKCOL", request.method)
        val body = request.body!!.decodeToString()
        assertTrue("<cs:subscribed/>" in body)
        assertTrue("<cs:source><d:href>https://example.org/a.ics?x=1&amp;y=2</d:href></cs:source>" in body, body)
        assertFailsWith<IllegalArgumentException> {
            calendarSubscriptionCreateRequest("${home}x-abc123/", CalendarCollectionDraft("X", "#E9322D", sourceUrl = "ftp://example.org"))
        }
    }

    @Test
    fun colorChoicesKeepTheirOrderAndAddAForeignColorOnce() {
        assertEquals(CalendarCollectionColors, calendarColorChoices(null))
        assertEquals(CalendarCollectionColors, calendarColorChoices("#2e9e5bff"), "A palette color is not repeated")
        assertEquals(CalendarCollectionColors + "#123456", calendarColorChoices("#123456FF"))
        assertEquals(CalendarCollectionColors, calendarColorChoices("not a color"))
    }

    @Test
    fun draftsExplainWhatIsMissing() {
        assertEquals("Enter a name.", CalendarCollectionDraft(" ", "#0082C9").validationMessage())
        assertEquals("Choose a color.", CalendarCollectionDraft("Work", "blue").validationMessage())
        assertTrue(CalendarCollectionDraft("Work", "#0082C9", sourceUrl = "nope").validationMessage()!!.contains("https://"))
        assertNull(CalendarCollectionDraft("Work", "#0082C9FF").validationMessage())
        assertTrue(CalendarCollectionDraft("Wo\u0000rk", "#0082C9").validationMessage() != null)
    }

    @Test
    fun changesTargetOnlyCalendarsDirectlyInAHome() {
        listOf(
            "/remote.php/dav/calendars/alice/", "/remote.php/dav/calendars/alice/work/event.ics",
            "/remote.php/dav/calendars/alice/work/nested/", "/remote.php/dav/addressbooks/users/alice/contacts/",
            "/remote.php/dav/calendars/alice/../bob/work/",
        ).forEach { href -> assertFailsWith<IllegalArgumentException>(href) { calendarCollectionDeleteRequest(href) } }
        val delete = calendarCollectionDeleteRequest("${home}work/")
        assertEquals("DELETE", delete.method)
        assertNull(delete.body)
        assertEquals("PROPPATCH", calendarCollectionUpdateRequest("${home}work/", CalendarCollectionDraft("Work", "#0082C9")).method)
    }

    @Test
    fun propertyUpdatesSucceedOnlyWhenEveryPropertyWasApplied() {
        fun propstat(status: String) = "<d:propstat><d:prop><d:displayname/></d:prop><d:status>HTTP/1.1 $status</d:status></d:propstat>"
        fun reply(vararg statuses: String) = NextcloudApiResponse(207,
            multistatusXml("<d:response><d:href>${home}work/</d:href>${statuses.joinToString("") { propstat(it) }}</d:response>")
                .encodeToByteArray(), "application/xml", null)
        assertTrue(calendarPropertyUpdateApplied(reply("200 OK", "200 OK")))
        assertFalse(calendarPropertyUpdateApplied(reply("200 OK", "403 Forbidden")))
        assertFalse(calendarPropertyUpdateApplied(reply()))
        assertTrue(calendarPropertyUpdateApplied(NextcloudApiResponse(204, byteArrayOf(), null, null)))
    }

    private val writePrivilege =
        "<d:current-user-privilege-set><d:privilege><d:write-content/></d:privilege></d:current-user-privilege-set>"

    private fun comps(vararg names: String) =
        "<c:supported-calendar-component-set>${names.joinToString("") { """<c:comp name="$it"/>""" }}</c:supported-calendar-component-set>"

    private fun response(href: String, props: String) =
        "<d:response><d:href>$href</d:href><d:propstat><d:prop>$props</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"

    private fun multistatusXml(body: String) =
        "<d:multistatus xmlns:d=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:caldav\" xmlns:cs=\"http://calendarserver.org/ns/\" " +
            "xmlns:x1=\"http://apple.com/ns/ical/\" xmlns:oc=\"http://owncloud.org/ns\" xmlns:nc=\"http://nextcloud.com/ns\">$body</d:multistatus>"

    private fun multistatus(vararg responses: String) =
        NextcloudApiResponse(207, multistatusXml(responses.joinToString("")).encodeToByteArray(), "application/xml", null)
}
