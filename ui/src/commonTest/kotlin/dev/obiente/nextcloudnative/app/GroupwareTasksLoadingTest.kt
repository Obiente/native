package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GroupwareTasksLoadingTest {
    @Test
    fun `task calendars load object data through bounded multiget batches`() = runBlocking {
        val calendarHref = "/remote.php/dav/calendars/person/tasks/"
        val hrefs = (1..25).map { index -> "$calendarHref$index.ics" }
        val requests = mutableListOf<GroupwareDavRequest>()

        val tasks = loadGroupwareTasksInBatches(calendarHref) { request ->
            requests += request
            if (request.method == "PROPFIND") {
                listingResponse(calendarHref, hrefs)
            } else {
                val requested = hrefs.filter { href -> requireNotNull(request.body).decodeToString().contains(href) }
                multiGetResponse(requested)
            }
        }

        assertEquals(25, tasks.size)
        assertEquals(listOf("PROPFIND", "REPORT", "REPORT", "REPORT"), requests.map(GroupwareDavRequest::method))
        assertTrue(requests.all { request -> request.maximumResponseBytes <= 16L * 1024L * 1024L })
        assertTrue(requests.drop(1).all { request ->
            requireNotNull(request.body).decodeToString().split("<d:href>").size - 1 <= 10
        })
    }

    @Test
    fun `one failed task calendar preserves tasks from healthy calendars`() = runBlocking {
        val healthy = GroupwareCalendar("/remote.php/dav/calendars/person/healthy/", "Healthy")
        val failed = GroupwareCalendar("/remote.php/dav/calendars/person/failed/", "Unavailable")
        val objectHref = "${healthy.href}one.ics"

        val result = loadGroupwareTaskCalendars(listOf(healthy, failed)) { request ->
            when {
                request.relativePath == failed.href -> NextcloudApiResponse(503, byteArrayOf(), null, null)
                request.method == "PROPFIND" -> listingResponse(healthy.href, listOf(objectHref))
                else -> multiGetResponse(listOf(objectHref))
            }
        }

        assertEquals(listOf("Task one"), result.tasks.map(GroupwareTask::title))
        assertEquals(listOf("Unavailable"), result.failedCalendarNames)
    }

    @Test
    fun `individual GET fallback retains the listing ETag when the response omits it`() = runBlocking {
        val calendarHref = "/remote.php/dav/calendars/person/tasks/"
        val objectHref = "${calendarHref}one.ics"

        val tasks = loadGroupwareTasksInBatches(calendarHref) { request ->
            when (request.method) {
                "PROPFIND" -> listingResponse(calendarHref, listOf(objectHref))
                "REPORT" -> NextcloudApiResponse(405, byteArrayOf(), null, null)
                "GET" -> NextcloudApiResponse(
                    status = 200,
                    contentType = "text/calendar",
                    etag = null,
                    body = createGroupwareTaskContent("one", "Task one", null, false).encodeToByteArray(),
                )
                else -> error("Unexpected ${request.method} request")
            }
        }

        assertEquals("\"etag\"", tasks.single().etag)
    }

    private fun listingResponse(calendarHref: String, hrefs: List<String>): NextcloudApiResponse =
        NextcloudApiResponse(
            status = 207,
            contentType = "application/xml",
            etag = null,
            body = """
                <d:multistatus xmlns:d="DAV:">
                  <d:response><d:href>$calendarHref</d:href></d:response>
                  ${hrefs.joinToString("\n") { href ->
                    "<d:response><d:href>$href</d:href><d:propstat><d:prop><d:getetag>&quot;etag&quot;</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
                  }}
                </d:multistatus>
            """.trimIndent().encodeToByteArray(),
        )

    private fun multiGetResponse(hrefs: List<String>): NextcloudApiResponse = NextcloudApiResponse(
        status = 207,
        contentType = "application/xml",
        etag = null,
        body = """
            <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
              ${hrefs.joinToString("\n") { href ->
                val id = href.substringAfterLast('/').substringBefore('.')
                val content = createGroupwareTaskContent(id, "Task $id", null, false)
                "<d:response><d:href>$href</d:href><d:propstat><d:prop><d:getetag>&quot;etag-$id&quot;</d:getetag><c:calendar-data>$content</c:calendar-data></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
              }}
            </d:multistatus>
        """.trimIndent().encodeToByteArray(),
    )
}
