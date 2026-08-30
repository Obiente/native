package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class GroupwareTasksDavTest {
    @Test
    fun `editing another task field preserves an unchanged timed due value`() {
        val href = "/remote.php/dav/calendars/person/tasks/timed.ics"
        val calendarHref = "/remote.php/dav/calendars/person/tasks/"
        val original = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VTODO
            UID:timed
            SUMMARY:Before
            DUE;TZID=Europe/Amsterdam:20260830T120000
            STATUS:NEEDS-ACTION
            PERCENT-COMPLETE:0
            END:VTODO
            END:VCALENDAR
        """.trimIndent().replace("\n", "\r\n") + "\r\n"
        val task = requireNotNull(parseGroupwareTask(calendarHref, href, "\"one\"", original))

        val updated = updateGroupwareTaskContent(task, "After", "20260830", false, null)

        assertTrue("DUE;TZID=Europe/Amsterdam:20260830T120000" in updated)
        assertEquals("20260830T120000", parseGroupwareTask(calendarHref, href, "\"two\"", updated)?.due)
        assertEquals("20260830T120000", expectedGroupwareTaskDueAfterDateEdit(task, "20260830"))
    }

    @Test
    fun `changing a timed due date preserves its timezone and time`() {
        val href = "/remote.php/dav/calendars/person/tasks/timed.ics"
        val calendarHref = "/remote.php/dav/calendars/person/tasks/"
        val original = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VTODO
            UID:timed
            SUMMARY:Before
            DUE;TZID=Europe/Amsterdam:20260830T120000
            STATUS:NEEDS-ACTION
            END:VTODO
            END:VCALENDAR
        """.trimIndent().replace("\n", "\r\n") + "\r\n"
        val task = requireNotNull(parseGroupwareTask(calendarHref, href, "\"one\"", original))

        val updated = updateGroupwareTaskContent(task, "After", "20260831", false, null)

        assertTrue("DUE;TZID=Europe/Amsterdam:20260831T120000" in updated)
        assertEquals("20260831T120000", parseGroupwareTask(calendarHref, href, "\"two\"", updated)?.due)
        assertEquals("20260831T120000", expectedGroupwareTaskDueAfterDateEdit(task, "20260831"))
    }

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
            dtstamp = "20260829T101112Z",
        ).replace("END:VTODO", "X-NEXTCLOUD-EXTRA:keep-me\r\nEND:VTODO")
        val parsed = requireNotNull(parseGroupwareTask(calendarHref, href, "\"one\"", created))

        assertEquals("Test the native task workspace", parsed.title)
        assertEquals("20260830", parsed.due)
        assertTrue(parsed.dueAllDay)
        assertFalse(parsed.completed)
        assertTrue("DTSTAMP:20260829T101112Z" in created)

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
        assertTrue(Regex("COMPLETED:[0-9]{8}T[0-9]{6}Z").containsMatchIn(updated))

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
    fun `completion timestamps survive unrelated edits and change only with completion state`() {
        val href = "/remote.php/dav/calendars/person/tasks/completed.ics"
        val calendarHref = "/remote.php/dav/calendars/person/tasks/"
        val original = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VTODO
            UID:completed
            SUMMARY:Before
            STATUS:COMPLETED
            PERCENT-COMPLETE:100
            COMPLETED:20260829T101112Z
            END:VTODO
            END:VCALENDAR
        """.trimIndent().replace("\n", "\r\n") + "\r\n"
        val task = requireNotNull(parseGroupwareTask(calendarHref, href, "\"one\"", original))

        val edited = updateGroupwareTaskContent(
            task,
            title = "After",
            dueDate = null,
            completed = true,
            description = null,
            completionTimestamp = "20260830T000000Z",
        )
        val reopened = updateGroupwareTaskContent(
            task,
            title = "After",
            dueDate = null,
            completed = false,
            description = null,
            completionTimestamp = "20260830T000000Z",
        )

        assertTrue("COMPLETED:20260829T101112Z" in edited)
        assertFalse("COMPLETED:" in reopened)
        assertEquals("20260829T101112Z", parseGroupwareTask(calendarHref, href, "\"two\"", edited)?.completedAt)
    }

    @Test
    fun `unrelated edits preserve non-binary task status and progress`() {
        val calendarHref = "/remote.php/dav/calendars/person/tasks/"
        listOf(
            "IN-PROCESS" to 40,
            "CANCELLED" to 60,
        ).forEach { (status, progress) ->
            val href = "$calendarHref${status.lowercase()}.ics"
            val original = """
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VTODO
                UID:$status
                SUMMARY:Before
                STATUS:$status
                PERCENT-COMPLETE:$progress
                END:VTODO
                END:VCALENDAR
            """.trimIndent().replace("\n", "\r\n") + "\r\n"
            val task = requireNotNull(parseGroupwareTask(calendarHref, href, "\"one\"", original))

            val updated = updateGroupwareTaskContent(
                task = task,
                title = "After",
                dueDate = null,
                completed = false,
                description = null,
            )

            assertTrue("STATUS:$status" in updated)
            assertTrue("PERCENT-COMPLETE:$progress" in updated)
            assertFalse("STATUS:NEEDS-ACTION" in updated)
            assertFalse("PERCENT-COMPLETE:0" in updated)
        }
    }

    @Test
    fun `recurring task components have distinct identities and exact component updates`() {
        val href = "/remote.php/dav/calendars/person/tasks/recurring.ics"
        val calendarHref = "/remote.php/dav/calendars/person/tasks/"
        val original = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VTODO
            UID:recurring
            SUMMARY:Master
            STATUS:NEEDS-ACTION
            END:VTODO
            BEGIN:VTODO
            UID:recurring
            RECURRENCE-ID:20260830T090000Z
            SUMMARY:Exception
            STATUS:NEEDS-ACTION
            END:VTODO
            END:VCALENDAR
        """.trimIndent().replace("\n", "\r\n") + "\r\n"
        val tasks = parseGroupwareTasksFromContent(calendarHref, href, "\"one\"", original)

        assertEquals(2, tasks.map(GroupwareTask::instanceId).distinct().size)
        val exception = tasks.single { it.recurrenceId != null }
        val updated = updateGroupwareTaskContent(exception, "Changed exception", null, false, null)
        val reparsed = parseGroupwareTasksFromContent(calendarHref, href, "\"two\"", updated)
        assertEquals("Master", reparsed.single { it.recurrenceId == null }.title)
        assertEquals("Changed exception", reparsed.single { it.recurrenceId != null }.title)
    }

    @Test
    fun `task edits preserve nested alarm properties`() {
        val href = "/remote.php/dav/calendars/person/tasks/alarm.ics"
        val calendarHref = "/remote.php/dav/calendars/person/tasks/"
        val original = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VTODO
            UID:alarm
            BEGIN:VALARM
            ACTION:DISPLAY
            DESCRIPTION:Reminder text
            SUMMARY:Reminder title
            TRIGGER:-PT15M
            END:VALARM
            END:VTODO
            END:VCALENDAR
        """.trimIndent().replace("\n", "\r\n") + "\r\n"
        val task = requireNotNull(parseGroupwareTask(calendarHref, href, "\"one\"", original))

        assertEquals("Untitled task", task.title)
        assertNull(task.description)
        val updated = updateGroupwareTaskContent(task, "Task title", null, false, null)

        assertTrue("DESCRIPTION:Reminder text" in updated)
        assertTrue("SUMMARY:Reminder title" in updated)
        assertEquals("Task title", parseGroupwareTask(calendarHref, href, "\"two\"", updated)?.title)
    }

    @Test
    fun `task parser withholds malformed components without a UID`() {
        val content = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VTODO
            SUMMARY:Missing identity
            END:VTODO
            END:VCALENDAR
        """.trimIndent().replace("\n", "\r\n") + "\r\n"

        assertTrue(
            parseGroupwareTasksFromContent(
                "/remote.php/dav/calendars/person/tasks/",
                "/remote.php/dav/calendars/person/tasks/malformed.ics",
                "\"one\"",
                content,
            ).isEmpty(),
        )
    }

    @Test
    fun `task parser rejects unsafe and oversized UIDs before exposing editing`() {
        val invalid = listOf("a\u0000b", "a\u0001b", "a\tb", "a\u007fb", "\u0000a", "a\u0000", "x".repeat(1_025))
        val base = createGroupwareTaskContent("safe", "Task", null, false)
        invalid.forEach { uid ->
            val content = base.replace("UID:safe", "UID:$uid")
            assertTrue(parseGroupwareTasksFromContent(
                "/remote.php/dav/calendars/person/tasks/", "/remote.php/dav/calendars/person/tasks/task.ics", "\"one\"", content,
            ).isEmpty())
            assertFailsWith<IllegalArgumentException> { createGroupwareTaskContent(uid, "Task", null, false) }
        }
        listOf("valid-id", "x".repeat(1_024), "t\u00e2che").forEach { uid ->
            val task = parseGroupwareTask("/remote.php/dav/calendars/person/tasks/", "/remote.php/dav/calendars/person/tasks/task.ics", "\"one\"",
                createGroupwareTaskContent(uid, "Task", null, false))
            assertEquals(uid, task?.uid)
        }
    }

    @Test
    fun `unrelated task edits preserve description whitespace including blank descriptions`() {
        val calendar = "/remote.php/dav/calendars/person/tasks/"
        val href = "${calendar}one.ics"
        listOf("\n    indented Markdown\n\n", " \t ", "\n\n", "leading\r\ntrailing\r").forEach { description ->
            val normalized = description.normalizeGroupwareTextLineEndings()
            val draft = TaskDraft("Task", "", description, true).normalized()
            assertEquals(normalized, draft.description)
            val task = requireNotNull(parseGroupwareTask(calendar, href, "\"one\"",
                createGroupwareTaskContent("one", "Task", null, false, description = normalized)))
            assertEquals(normalized, task.description)
            val content = updateGroupwareTaskContent(task, "Renamed", null, true, draft.description)
            assertEquals(normalized, parseGroupwareTask(calendar, href, "\"two\"", content)?.description)
            val postcondition = TaskMutationPostcondition.Upsert(
                href, calendar, "one", previousEtag = "\"one\"", draft = draft.copy(title = "Renamed"),
            )
            assertTrue(postcondition.isSatisfiedBy(NextcloudApiResponse(200, content.encodeToByteArray(), null, "\"two\"")))
        }
    }

    @Test
    fun `hidden malformed siblings prevent whole-object task deletion`() {
        val calendarHref = "/remote.php/dav/calendars/person/tasks/"
        val href = "${calendarHref}mixed.ics"
        val content = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VTODO
            UID:visible
            SUMMARY:Visible task
            END:VTODO
            BEGIN:VTODO
            SUMMARY:Malformed hidden sibling
            END:VTODO
            END:VCALENDAR
        """.trimIndent().replace("\n", "\r\n") + "\r\n"

        val task = parseGroupwareTasksFromContent(calendarHref, href, "\"one\"", content).single()

        assertFalse(isGroupwareTaskObjectDeleteSafe(task))
    }

    @Test
    fun `task due dates reject impossible Gregorian dates`() {
        assertTrue(isValidGroupwareTaskDueDate("20240229"))
        assertFalse(isValidGroupwareTaskDueDate("20230229"))
        assertFalse(isValidGroupwareTaskDueDate("20260231"))
        assertFalse(isValidGroupwareTaskDueDate("20269901"))
        assertFailsWith<IllegalArgumentException> {
            TaskDraft("Impossible", "2026-02-31", "", false).compactDueDateOrNull()
        }
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
        assertEquals(TaskRecoveryVerification.Applied, postcondition.verify(response))
        assertFalse(postcondition.isSatisfiedBy(response.copy(status = 404)))
        val unchangedResponse = response.copy(
            etag = "\"one\"",
            body = createGroupwareTaskContent(
                uid = "task-1",
                title = "Previous task",
                dueDate = null,
                completed = false,
            ).encodeToByteArray(),
        )
        assertEquals(
            TaskRecoveryVerification.Unapplied,
            postcondition.verify(unchangedResponse),
        )
        assertEquals(
            TaskRecoveryVerification.Unapplied,
            TaskMutationPostcondition.Upsert(
                href = "$calendarHref-new.ics",
                calendarHref = calendarHref,
                expectedUid = "new",
                previousEtag = null,
                draft = draft,
            ).verify(response.copy(status = 404)),
        )
        assertTrue(TaskMutationPostcondition.Delete(href).isSatisfiedBy(response.copy(status = 404)))
        assertEquals(
            TaskRecoveryVerification.Unapplied,
            TaskMutationPostcondition.Delete(href, "\"one\"").verify(response.copy(etag = "\"one\"")),
        )
        val encoded = TaskMutationRecoveryState(accountScope, postcondition).encodeForSavedState()
        assertEquals(postcondition, decodeTaskMutationRecoveryState(encoded, accountScope))
        assertNull(decodeTaskMutationRecoveryState(encoded, "$accountScope-other"))
    }
}
