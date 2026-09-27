package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals

class GroupwareDavCalendarTest {
    @Test
    fun `calendar collection href ignores subscribed calendar source href`() {
        val response = calendarDiscoveryResponse(
            """
                <d:propstat><d:prop>
                  <cs:source><d:href>https://external.example.test/calendar.ics</d:href></cs:source>
                </d:prop></d:propstat>
            """.trimIndent(),
        )

        val calendars = parseGroupwareCalendars(response)

        assertEquals("/remote.php/dav/calendars/opaque-user/subscribed/", calendars.single().href)
    }

    @Test
    fun `calendar collection href ignores markup in comments before response href`() {
        val response = calendarDiscoveryResponse(
            """
                <!-- > <note> not an element -->
            """.trimIndent(),
        )

        val calendars = parseGroupwareCalendars(response)

        assertEquals("/remote.php/dav/calendars/opaque-user/subscribed/", calendars.single().href)
    }

    private fun calendarDiscoveryResponse(prefix: String): NextcloudApiResponse = NextcloudApiResponse(
        status = 207,
        contentType = "application/xml",
        etag = null,
        body = """
            <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"
                xmlns:cs="http://calendarserver.org/ns/">
              <d:response>
                $prefix
                <d:href>/remote.php/dav/calendars/opaque-user/subscribed/</d:href>
                <d:resourcetype><d:collection/><c:calendar/><cs:subscribed/></d:resourcetype>
                <c:supported-calendar-component-set>
                  <c:comp name="VCALENDAR"><c:comp name="VEVENT"/></c:comp>
                </c:supported-calendar-component-set>
              </d:response>
            </d:multistatus>
        """.trimIndent().encodeToByteArray(),
    )
}
