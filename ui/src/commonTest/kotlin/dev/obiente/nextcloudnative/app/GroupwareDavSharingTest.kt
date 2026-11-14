package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupwareDavSharingTest {
    private val calendar = "/remote.php/dav/calendars/alice/personal/"

    @Test
    fun sharingStateReadsInvitesAccessAndThePublicLink() {
        val state = parseDavSharingState(
            ok(
                """
                <d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:cs="http://calendarserver.org/ns/">
                <d:response><d:href>$calendar</d:href>
                  <d:propstat><d:prop>
                    <oc:invite>
                      <oc:user><d:href>principal:principals/users/bob</d:href><oc:common-name>Bob &amp; Sons</oc:common-name>
                        <oc:invite-accepted/><oc:access><oc:read-write/></oc:access></oc:user>
                      <oc:user><d:href>principal:principals/groups/team</d:href>
                        <oc:invite-accepted/><oc:access><oc:read/></oc:access></oc:user>
                      <oc:user><d:href>mailto:someone@example.test</d:href><oc:access><oc:read/></oc:access></oc:user>
                      <oc:user><d:href>principal:principals/users/../admin</d:href><oc:access><oc:read/></oc:access></oc:user>
                    </oc:invite>
                    <cs:publish-url><d:href>https://cloud.example.test/remote.php/dav/public-calendars/token</d:href></cs:publish-url>
                  </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
                </d:response></d:multistatus>
                """.trimIndent(),
            ),
        )
        assertEquals(listOf("principals/users/bob", "principals/groups/team"), state.shares.map { it.sharee.principal })
        assertEquals("Bob & Sons", state.shares[0].sharee.displayName)
        assertTrue(state.shares[0].writable)
        assertFalse(state.shares[1].writable)
        assertEquals(DavShareeKind.Group, state.shares[1].sharee.kind)
        assertEquals("team", state.shares[1].sharee.displayName)
        assertEquals("https://cloud.example.test/remote.php/dav/public-calendars/token", state.publishUrl)
        assertTrue(state.canPublish)
    }

    @Test
    fun propertiesTheServerDidNotReturnAreIgnored() {
        val state = parseDavSharingState(
            ok(
                """
                <d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:cs="http://calendarserver.org/ns/">
                <d:response><d:href>/remote.php/dav/addressbooks/users/alice/contacts/</d:href>
                  <d:propstat><d:prop><oc:invite/></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
                  <d:propstat><d:prop><cs:publish-url/><cs:allowed-sharing-modes/></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat>
                </d:response></d:multistatus>
                """.trimIndent(),
            ),
        )
        assertTrue(state.shares.isEmpty())
        assertNull(state.publishUrl)
        assertFalse(state.canPublish)
        assertFailsWith<IllegalArgumentException> { parseDavSharingState(NextcloudApiResponse(403, byteArrayOf(), null, null)) }
    }

    @Test
    fun aSharingPropertyThatFailedToReadIsNotReadAsAbsent() {
        fun answer(inviteStatus: String, publishStatus: String) = ok(
            """
            <d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:cs="http://calendarserver.org/ns/">
            <d:response><d:href>$calendar</d:href>
              <d:propstat><d:prop><oc:invite/></d:prop><d:status>HTTP/1.1 $inviteStatus</d:status></d:propstat>
              <d:propstat><d:prop><cs:publish-url/></d:prop><d:status>HTTP/1.1 $publishStatus</d:status></d:propstat>
            </d:response></d:multistatus>
            """.trimIndent(),
        )
        assertNull(parseDavSharingState(answer("200 OK", "404 Not Found")).publishUrl)
        assertFailsWith<IllegalArgumentException>("A failed read does not prove the link is gone.") {
            parseDavSharingState(answer("200 OK", "500 Internal Server Error"))
        }
        assertFailsWith<IllegalArgumentException>("A failed read does not prove nobody has access.") {
            parseDavSharingState(answer("503 Service Unavailable", "404 Not Found"))
        }
    }

    @Test
    fun shareRequestsUseTheOwncloudShareDocument() {
        val bob = DavSharee("principals/users/bob", "Bob", DavShareeKind.User)
        val team = DavSharee("principals/groups/team", "Team", DavShareeKind.Group)
        val request = davShareUpdateRequest(calendar, set = listOf(DavShare(bob, writable = true)), remove = listOf(team))
        val body = request.body!!.decodeToString()
        assertEquals("POST", request.method)
        assertEquals(calendar, request.relativePath)
        assertTrue(request.contentType!!.startsWith("application/xml"))
        assertTrue("<oc:set><d:href>principal:principals/users/bob</d:href><oc:read-write/></oc:set>" in body)
        assertTrue("<oc:remove><d:href>principal:principals/groups/team</d:href></oc:remove>" in body)
        assertFalse("read-write" in davShareUpdateRequest(calendar, set = listOf(DavShare(bob, false))).body!!.decodeToString())
        assertFailsWith<IllegalArgumentException> { davShareUpdateRequest(calendar) }
        assertFailsWith<IllegalArgumentException> { davShareUpdateRequest(calendar, remove = List(11) { bob }) }
        assertFailsWith<IllegalArgumentException> { davShareUpdateRequest("/remote.php/dav/files/alice/", remove = listOf(bob)) }
        assertFailsWith<IllegalArgumentException> {
            davShareUpdateRequest(calendar, remove = listOf(bob.copy(principal = "principals/users/a<b")))
        }
    }

    @Test
    fun publishingTogglesWithCalendarServerDocuments() {
        assertTrue("<cs:publish-calendar" in calendarPublishRequest(calendar, true).body!!.decodeToString())
        assertTrue("<cs:unpublish-calendar" in calendarPublishRequest(calendar, false).body!!.decodeToString())
    }

    @Test
    fun principalSearchFindsUsersAndGroupsButNotRooms() {
        val request = davPrincipalSearchRequest(" bo<b ")
        val body = request.body!!.decodeToString()
        assertEquals("REPORT", request.method)
        assertTrue("<d:match>bo&lt;b</d:match>" in body)
        assertTrue("apply-to-principal-collection-set" in body)
        assertFailsWith<IllegalArgumentException> { davPrincipalSearchRequest("b") }

        val results = parseDavPrincipalSearch(
            ok(
                """
                <d:multistatus xmlns:d="DAV:">
                <d:response><d:href>/remote.php/dav/principals/users/bob/</d:href>
                  <d:propstat><d:prop><d:displayname>Bob Example</d:displayname></d:prop></d:propstat></d:response>
                <d:response><d:href>/remote.php/dav/principals/groups/bobs/</d:href>
                  <d:propstat><d:prop><d:displayname/></d:prop></d:propstat></d:response>
                <d:response><d:href>/remote.php/dav/principals/calendar-rooms/room-1/</d:href>
                  <d:propstat><d:prop><d:displayname>Board room</d:displayname></d:prop></d:propstat></d:response>
                <d:response><d:href>https://elsewhere.example.test/principals/users/eve/</d:href></d:response>
                </d:multistatus>
                """.trimIndent(),
            ),
        )
        assertEquals(listOf("principals/users/bob", "principals/groups/bobs"), results.map { it.principal })
        assertEquals("bobs", results[1].displayName)
    }

    @Test
    fun trashBinListsDeletedCalendarsAndObjects() {
        val calendars = parseDeletedCalendars(
            ok(
                """
                <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav" xmlns:nc="http://nextcloud.com/ns">
                <d:response><d:href>/remote.php/dav/calendars/alice/personal/</d:href>
                  <d:propstat><d:prop><d:resourcetype><d:collection/><c:calendar/></d:resourcetype></d:prop></d:propstat></d:response>
                <d:response><d:href>/remote.php/dav/calendars/alice/old-1/</d:href>
                  <d:propstat><d:prop><d:displayname>Old</d:displayname><d:resourcetype><d:collection/><c:calendar/></d:resourcetype>
                  <nc:deleted-at>2026-10-01T10:00:00+00:00</nc:deleted-at></d:prop></d:propstat></d:response>
                </d:multistatus>
                """.trimIndent(),
            ),
        )
        assertEquals(listOf("Old"), calendars.map { it.name })
        assertEquals(CalendarTrashKind.Calendar, calendars.single().kind)

        val objectsHref = calendarTrashHomeHref("alice") + "objects/"
        val objects = parseDeletedCalendarObjects(
            ok(
                """
                <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav" xmlns:nc="http://nextcloud.com/ns">
                <d:response><d:href>${objectsHref}12.ics</d:href><d:propstat><d:prop>
                  <c:calendar-data>BEGIN:VCALENDAR&#13;
                BEGIN:VTODO&#13;
                UID:t&#13;
                SUMMARY:Buy milk&#13;
                END:VTODO&#13;
                END:VCALENDAR</c:calendar-data>
                  <nc:calendar-uri>personal</nc:calendar-uri><nc:deleted-at>2026-10-02T10:00:00+00:00</nc:deleted-at>
                </d:prop></d:propstat></d:response>
                <d:response><d:href>/remote.php/dav/calendars/alice/personal/x.ics</d:href><d:propstat><d:prop>
                  <c:calendar-data>BEGIN:VCALENDAR
                BEGIN:VEVENT
                SUMMARY:Elsewhere
                END:VEVENT
                END:VCALENDAR</c:calendar-data></d:prop></d:propstat></d:response>
                </d:multistatus>
                """.trimIndent(),
            ),
            objectsHref,
        )
        assertEquals(1, objects.size)
        assertEquals("Buy milk", objects.single().name)
        assertEquals(CalendarTrashKind.Task, objects.single().kind)
        assertEquals("personal", objects.single().calendarUri)
    }

    @Test
    fun restoringMovesIntoTheRestoreCollectionAndDeletingCalendarsSkipsTheTrashBin() {
        val deletedCalendar = CalendarTrashItem("/remote.php/dav/calendars/alice/old-1/", "Old", CalendarTrashKind.Calendar, null)
        val restore = calendarTrashRestoreRequest("https://cloud.example.test/", "alice", deletedCalendar)
        assertEquals("MOVE", restore.method)
        assertEquals("https://cloud.example.test/remote.php/dav/calendars/alice/trashbin/restore/old-1", restore.headers["Destination"])
        assertEquals("1", calendarTrashDeleteRequest("alice", deletedCalendar).headers["X-NC-CalDAV-No-Trashbin"])

        val deletedEvent = CalendarTrashItem("/remote.php/dav/calendars/alice/trashbin/objects/12.ics", "E", CalendarTrashKind.Event, null)
        assertTrue(calendarTrashDeleteRequest("alice", deletedEvent).headers.isEmpty())
        assertEquals(
            "https://cloud.example.test/remote.php/dav/calendars/alice/trashbin/restore/12.ics",
            calendarTrashRestoreRequest("https://cloud.example.test", "alice", deletedEvent).headers["Destination"],
        )
        assertFailsWith<IllegalArgumentException> {
            calendarTrashDeleteRequest("alice", deletedEvent.copy(href = "/remote.php/dav/calendars/bob/trashbin/objects/12.ics"))
        }
        assertFailsWith<IllegalArgumentException> {
            calendarTrashDeleteRequest("alice", deletedCalendar.copy(href = "/remote.php/dav/calendars/alice/trashbin/"))
        }
    }

    @Test
    fun retentionIsReadInDays() {
        val response = ok("<d:multistatus xmlns:d=\"DAV:\" xmlns:nc=\"http://nextcloud.com/ns\"><nc:trash-bin-retention-duration>2592000</nc:trash-bin-retention-duration></d:multistatus>")
        assertEquals(30, parseCalendarTrashRetentionDays(response))
        assertNull(parseCalendarTrashRetentionDays(ok("<x/>")))
        assertNull(parseCalendarTrashRetentionDays(NextcloudApiResponse(404, byteArrayOf(), null, null)))
    }

    @Test
    fun theBirthdayCalendarIsRecognisedAndEnabledOnTheHome() {
        assertTrue(GroupwareCalendar("/remote.php/dav/calendars/alice/contact_birthdays/", "Birthdays").isBirthdayCalendar())
        assertFalse(GroupwareCalendar("/remote.php/dav/calendars/alice/personal/", "Personal").isBirthdayCalendar())
        val request = birthdayCalendarEnableRequest("alice")
        assertEquals("POST", request.method)
        assertEquals("/remote.php/dav/calendars/alice/", request.relativePath)
        assertTrue("enable-birthday-calendar" in request.body!!.decodeToString())
    }

    private fun ok(body: String) = NextcloudApiResponse(207, body.encodeToByteArray(), "application/xml", null)
}
