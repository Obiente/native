package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
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
        assertEquals(setOf(healthy.href), result.completedCalendarHrefs)
    }

    @Test
    fun `transient multiget errors stop before individual reads or later batches`() = runBlocking {
        val calendarHref = "/remote.php/dav/calendars/person/tasks/"
        val hrefs = (1..30).map { "$calendarHref$it.ics" }
        listOf(429, 500, 502, 503, 504).forEach { status ->
            val methods = mutableListOf<String>()
            val failure = assertFailsWith<IllegalStateException> {
                loadGroupwareTasksInBatches(calendarHref) { request ->
                    methods += request.method
                    if (request.method == "PROPFIND") listingResponse(calendarHref, hrefs)
                    else NextcloudApiResponse(status, byteArrayOf(), null, null)
                }
            }
            assertTrue(failure.message.orEmpty().contains(status.toString()))
            assertEquals(listOf("PROPFIND", "REPORT"), methods)
        }
    }

    @Test
    fun `duplicate task components fail their calendar without confirming selection removal`() = runBlocking {
        val healthy = GroupwareCalendar("/remote.php/dav/calendars/person/healthy/", "Healthy")
        val corrupt = GroupwareCalendar("/remote.php/dav/calendars/person/corrupt/", "Corrupt")
        val calendars = listOf(healthy, corrupt)
        val result = loadGroupwareTaskCalendars(calendars) { request ->
            val calendar = calendars.single { it.href == request.relativePath }
            val href = "${calendar.href}one.ics"
            if (request.method == "PROPFIND") listingResponse(calendar.href, listOf(href)) else {
                val response = multiGetResponse(listOf(href))
                if (calendar == healthy) response else response.copy(body = response.body.decodeToString().replace(
                    "END:VCALENDAR", "BEGIN:VTODO\r\nUID:one\r\nSUMMARY:Duplicate\r\nEND:VTODO\r\nEND:VCALENDAR",
                ).encodeToByteArray())
            }
        }
        assertEquals(listOf("Corrupt"), result.failedCalendarNames)
        assertEquals(setOf(healthy.href), result.completedCalendarHrefs)
        assertEquals(listOf("${healthy.href}one.ics"), result.tasks.map(GroupwareTask::href))
        val selection = result.tasks.single().copy(calendarHref = corrupt.href, href = "${corrupt.href}one.ics").selection()
        assertFalse(TasksLoadState.Ready(calendars, result.tasks, result.completedCalendarHrefs).confirmsSelectionRemoved(selection))
    }

    @Test
    fun `not implemented REPORT allows individual task reads`() = runBlocking {
        val calendarHref = "/remote.php/dav/calendars/person/tasks/"
        val methods = mutableListOf<String>()
        val tasks = loadGroupwareTasksInBatches(calendarHref) { request ->
            methods += request.method
            when (request.method) {
                "PROPFIND" -> listingResponse(calendarHref, listOf("${calendarHref}one.ics"))
                "REPORT" -> NextcloudApiResponse(501, byteArrayOf(), null, null)
                "GET" -> NextcloudApiResponse(200,
                    createGroupwareTaskContent("one", "Task", null, false).encodeToByteArray(), null, null)
                else -> error("Unexpected method")
            }
        }
        assertEquals(listOf("PROPFIND", "REPORT", "GET"), methods)
        assertEquals("one", tasks.single().uid)
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

    @Test
    fun `concurrent multiget deletions retain healthy tasks and report a partial refresh`() = runBlocking {
        val calendar = GroupwareCalendar("/remote.php/dav/calendars/person/tasks/", "Tasks")
        val healthyHref = "${calendar.href}healthy.ics"
        val deletedHref = "${calendar.href}deleted.ics"
        val omittedHref = "${calendar.href}omitted.ics"

        val result = loadGroupwareTaskCalendars(listOf(calendar)) { request ->
            if (request.method == "PROPFIND") {
                listingResponse(calendar.href, listOf(healthyHref, deletedHref, omittedHref))
            } else {
                val content = createGroupwareTaskContent("healthy", "Healthy task", null, false)
                NextcloudApiResponse(
                    status = 207,
                    contentType = "application/xml",
                    etag = null,
                    body = """
                        <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                          <d:response><d:href>$healthyHref</d:href><d:propstat><d:prop>
                            <d:getetag>&quot;healthy&quot;</d:getetag><c:calendar-data>$content</c:calendar-data>
                          </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
                          <d:response><d:href>$deletedHref</d:href><d:status>HTTP/1.1 404 Not Found</d:status></d:response>
                        </d:multistatus>
                    """.trimIndent().encodeToByteArray(),
                )
            }
        }

        assertEquals(listOf("Healthy task"), result.tasks.map(GroupwareTask::title))
        assertTrue(result.failedCalendarNames.isEmpty())
        assertEquals(2, result.concurrentlyDeletedObjectCount)
        assertEquals(setOf(calendar.href), result.completedCalendarHrefs)
    }

    @Test
    fun `empty successful calendar is complete even if another calendar with the same name fails`() = runBlocking {
        val healthy = GroupwareCalendar("/remote.php/dav/calendars/person/healthy/", "Tasks")
        val failed = GroupwareCalendar("/remote.php/dav/calendars/person/failed/", "Tasks")
        val result = loadGroupwareTaskCalendars(listOf(healthy, failed)) { request ->
            if (request.relativePath == healthy.href) listingResponse(healthy.href, emptyList())
            else NextcloudApiResponse(503, byteArrayOf(), null, null)
        }
        assertEquals(setOf(healthy.href), result.completedCalendarHrefs)
        assertEquals(listOf("Tasks"), result.failedCalendarNames)
    }

    @Test
    fun `budget omissions prevent completion only for the affected calendar`() = runBlocking {
        val large = GroupwareCalendar("/remote.php/dav/calendars/person/large/", "Large")
        val empty = GroupwareCalendar("/remote.php/dav/calendars/person/empty/", "Empty")
        val href = "${large.href}one.ics"
        val result = loadGroupwareTaskCalendars(
            listOf(large, empty), retentionBudget = GroupwareTaskRetentionBudget(1L),
        ) { request ->
            when {
                request.relativePath == empty.href -> listingResponse(empty.href, emptyList())
                request.method == "PROPFIND" -> listingResponse(large.href, listOf(href))
                else -> multiGetResponse(listOf(href))
            }
        }
        assertEquals(setOf(empty.href), result.completedCalendarHrefs)
        assertEquals(1, result.omittedObjectCount)
    }

    @Test
    fun `aggregate task retention budget reports omitted objects`() = runBlocking {
        val calendarHref = "/remote.php/dav/calendars/person/tasks/"
        val hrefs = listOf("${calendarHref}one.ics", "${calendarHref}two.ics")
        var omittedObjectCount = 0

        val tasks = loadGroupwareTasksInBatches(
            calendarHref = calendarHref,
            retentionBudget = GroupwareTaskRetentionBudget(maximumEstimatedBytes = 1L),
            onRetentionOmission = { count -> omittedObjectCount += count },
        ) { request ->
            if (request.method == "PROPFIND") listingResponse(calendarHref, hrefs) else multiGetResponse(hrefs)
        }

        assertTrue(tasks.isEmpty())
        assertEquals(2, omittedObjectCount)
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
