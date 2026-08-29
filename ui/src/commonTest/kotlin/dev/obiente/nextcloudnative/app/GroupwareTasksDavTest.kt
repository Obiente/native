package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupwareTasksDavTest {
    @Test
    fun `CalDAV tasks retain unknown properties across native create read and update`() {
        val href = "/remote.php/dav/calendars/person/tasks/task-1.ics"
        val calendarHref = "/remote.php/dav/calendars/person/tasks/"
        val created = createGroupwareTaskContent(
            uid = "task-1",
            title = "Test the native task workspace",
            dueDate = "20260830",
            completed = false,
            description = "Synthetic task",
        ).replace("END:VTODO", "X-NEXTCLOUD-EXTRA:keep-me\r\nEND:VTODO")
        val parsed = requireNotNull(parseGroupwareTask(calendarHref, href, "\"one\"", created))

        assertEquals("Test the native task workspace", parsed.title)
        assertEquals("20260830", parsed.due)
        assertTrue(parsed.dueAllDay)
        assertFalse(parsed.completed)

        val updated = updateGroupwareTaskContent(
            task = parsed,
            title = "Test the updated task workspace",
            dueDate = "20260831",
            completed = true,
            description = "Synthetic task updated",
        )
        val reparsed = requireNotNull(parseGroupwareTask(calendarHref, href, "\"two\"", updated))
        assertEquals("Test the updated task workspace", reparsed.title)
        assertEquals("20260831", reparsed.due)
        assertTrue(reparsed.completed)
        assertEquals("Synthetic task updated", reparsed.description)
        assertTrue("X-NEXTCLOUD-EXTRA:keep-me" in updated)
        assertEquals(1, Regex("STATUS:COMPLETED").findAll(updated).count())
        assertEquals(1, Regex("PERCENT-COMPLETE:100").findAll(updated).count())

        val response = NextcloudApiResponse(
            status = 207,
            contentType = "application/xml",
            etag = null,
            body = """
                <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                  <d:response>
                    <d:href>$href</d:href>
                    <d:propstat><d:prop><d:getetag>&quot;two&quot;</d:getetag>
                      <c:calendar-data>${updated.replace("&", "&amp;")}</c:calendar-data>
                    </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
                  </d:response>
                </d:multistatus>
            """.trimIndent().encodeToByteArray(),
        )
        val reported = parseGroupwareTasks(calendarHref, response).single()
        assertEquals(reparsed.title, reported.title)
        assertEquals(reparsed.due, reported.due)
        assertEquals(reparsed.completed, reported.completed)
    }

    @Test
    fun `task mutation recovery verifies normalized server state and account scope`() {
        val session = NextcloudSession("https://cloud.example.test", "person", "secret")
        val accountScope = durableMutationAccountScope(session)
        val href = "/remote.php/dav/calendars/person/tasks/task-1.ics"
        val calendarHref = "/remote.php/dav/calendars/person/tasks/"
        val draft = TaskDraft(
            title = "  Native task  ",
            dueDate = "2026-08-31",
            description = "  Updated task\r\n",
            completed = true,
        )
        val postcondition = TaskMutationPostcondition.Upsert(
            href = href,
            calendarHref = calendarHref,
            expectedUid = "task-1",
            previousEtag = "\"one\"",
            draft = draft,
        )
        val response = NextcloudApiResponse(
            status = 200,
            contentType = "text/calendar",
            etag = "\"two\"",
            body = createGroupwareTaskContent(
                uid = "task-1",
                title = draft.normalized().title,
                dueDate = "20260831",
                completed = true,
                description = draft.normalized().description,
            ).encodeToByteArray(),
        )

        assertTrue(postcondition.isSatisfiedBy(response))
        assertFalse(postcondition.isSatisfiedBy(response.copy(status = 404)))
        assertTrue(TaskMutationPostcondition.Delete(href).isSatisfiedBy(response.copy(status = 404)))
        val encoded = TaskMutationRecoveryState(accountScope, postcondition).encodeForSavedState()
        assertEquals(postcondition, decodeTaskMutationRecoveryState(encoded, accountScope))
        assertNull(decodeTaskMutationRecoveryState(encoded, "$accountScope-other"))
    }
}
