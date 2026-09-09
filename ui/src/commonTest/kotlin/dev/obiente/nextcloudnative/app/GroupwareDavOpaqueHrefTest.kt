package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GroupwareDavOpaqueHrefTest {
    private val calendar = "/remote.php/dav/calendars/person/tasks/"
    private val content = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VTODO\r\nUID:task-1\r\nSUMMARY:Original\r\nEND:VTODO\r\nEND:VCALENDAR\r\n"

    @Test
    fun loadedOpaqueTaskHrefsSurviveEditsCompletionAndDeletes() {
        listOf("123", "task%20one", "opaque.resource", "task.ics").forEach { name ->
            val href = calendar + name
            val task = assertNotNull(parseGroupwareTask(calendar, href, "\"v1\"", content))
            val updated = updateGroupwareTaskContent(task, "Edited", null, true, "New description",
                completionTimestamp = "20260830T200000Z")
            listOf(GroupwareDavMutation.Update, GroupwareDavMutation.Delete).forEach { mutation ->
                val request = GroupwareDavMutationSpec(GroupwareDavKind.Task, mutation, task.href, task.etag,
                    updated.takeIf { mutation == GroupwareDavMutation.Update }).toGroupwareDavRequest()
                assertEquals(href, request.relativePath)
                assertEquals(mapOf("If-Match" to "\"v1\""), request.headers)
                assertEquals(if (mutation == GroupwareDavMutation.Update) "PUT" else "DELETE", request.method)
            }
            val reread = assertNotNull(parseGroupwareTask(calendar, href, "\"v2\"", updated))
            assertEquals("Edited", reread.title)
            assertTrue(reread.completed)
        }
    }

    @Test
    fun opaqueHrefsRetainConditionalWriteAndScopeGuardsForEveryKind() {
        GroupwareDavKind.entries.forEach { kind ->
            val validContent = when (kind) {
                GroupwareDavKind.Task -> content
                GroupwareDavKind.Event -> content.replace("VTODO", "VEVENT")
                GroupwareDavKind.Contact -> "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Person\r\nEND:VCARD"
            }
            listOf(GroupwareDavMutation.Update, GroupwareDavMutation.Delete).forEach { mutation ->
                val spec = GroupwareDavMutationSpec(kind, mutation, calendar + "123", "\"v1\"",
                    validContent.takeIf { mutation == GroupwareDavMutation.Update })
                assertEquals(calendar + "123", spec.toGroupwareDavRequest().relativePath)
                assertFailsWith<IllegalArgumentException> { spec.copy(etag = null).toGroupwareDavRequest() }
                listOf(calendar, "/remote.php/dav/", calendar + "../123", calendar + "%2e%2e/123",
                    calendar + "123?query", "https://foreign.test/123", "/apps/tasks/123").forEach { unsafe ->
                    assertFailsWith<IllegalArgumentException>(unsafe) { spec.copy(objectHref = unsafe).toGroupwareDavRequest() }
                }
            }
            val create = GroupwareDavMutationSpec(kind, GroupwareDavMutation.Create, calendar + "123", content = validContent)
            assertFailsWith<IllegalArgumentException> { create.toGroupwareDavRequest() }
            val suffix = if (kind == GroupwareDavKind.Contact) ".vcf" else ".ics"
            assertEquals(mapOf("If-None-Match" to "*"), create.copy(objectHref = calendar + "123" + suffix)
                .toGroupwareDavRequest().headers)
            assertFailsWith<IllegalArgumentException> {
                create.copy(objectHref = calendar + "123" + suffix, content = "wrong content").toGroupwareDavRequest()
            }
        }
    }
}
