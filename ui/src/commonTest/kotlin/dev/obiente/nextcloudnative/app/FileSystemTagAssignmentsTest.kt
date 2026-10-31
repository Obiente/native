package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.app.FileTrashbinProtocolTest.Companion.response
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileSystemTagAssignmentsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @AfterTest
    fun cancelScope() = scope.cancel()

    private val catalog = listOf(
        NextcloudSystemTag(1, "Invoices", userVisible = true, userAssignable = true, canAssign = true),
        NextcloudSystemTag(2, "Legal hold", userVisible = true, userAssignable = false, canAssign = false),
        NextcloudSystemTag(3, "Hidden", userVisible = false, userAssignable = false, canAssign = true),
    )

    private class FakeTags {
        val assigned = mutableSetOf(2L)
        val names = mutableMapOf(1L to "Invoices", 2L to "Legal hold")
        var putThrowsAfterApply = false
        var listFails = false
        val requests = mutableListOf<FilesDavRequest>()

        suspend fun execute(request: FilesDavRequest): NextcloudApiResponse {
            requests += request
            val tagId = request.relativePath.substringAfterLast('/').toLongOrNull()
            return when (request.method) {
                FilesDavMethod.PROPFIND -> {
                    if (listFails) throw IllegalStateException("offline")
                    response(207, listing())
                }
                FilesDavMethod.PUT -> {
                    if (tagId in assigned) return response(409)
                    assigned += tagId!!
                    if (putThrowsAfterApply) throw IllegalStateException("reset")
                    response(201)
                }
                FilesDavMethod.DELETE -> if (assigned.remove(tagId)) response(204) else response(404)
                FilesDavMethod.POST -> {
                    names[9] = "Receipts"
                    assigned += 9
                    response(201)
                }
                else -> error("unexpected")
            }
        }

        fun listing() = buildString {
            append("""<d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">""")
            append("<d:response><d:href>/remote.php/dav/systemtags-relations/files/42/</d:href><d:propstat><d:prop/><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>")
            assigned.forEach { id ->
                append("<d:response><d:href>/remote.php/dav/systemtags-relations/files/42/$id</d:href><d:propstat><d:prop>")
                append("<oc:id>$id</oc:id><oc:display-name>${names[id]}</oc:display-name><oc:user-visible>true</oc:user-visible>")
                append("<oc:user-assignable>${id != 2L}</oc:user-assignable><oc:can-assign>${id != 2L}</oc:can-assign>")
                append("</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>")
            }
            append("</d:multistatus>")
        }
    }

    private fun controller(fake: FakeTags) = FileTagsController(scope, 42L, fake::execute) { catalog }

    @Test
    fun requestsTargetTheFileRelationCollection() {
        assertEquals("/remote.php/dav/systemtags-relations/files/42/7", fileTagAssignRequest(42, 7).relativePath)
        assertEquals(FilesDavMethod.DELETE, fileTagUnassignRequest(42, 7).method)
        assertFailsWith<IllegalArgumentException> { fileTagAssignRequest(42, 0) }
        assertTrue(fileTagCreateAndAssignRequest(42, " Receipts ").body!!.decodeToString().contains("\"name\":\"Receipts\""))
        assertFailsWith<IllegalArgumentException> { fileTagCreateAndAssignRequest(42, " ") }
    }

    @Test
    fun malformedAssignmentResponseFailsClosed() {
        val body = """<d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns"><d:response><d:href>/remote.php/dav/systemtags-relations/files/42/1</d:href><d:propstat><d:prop><oc:id>abc</oc:id><oc:display-name>x</oc:display-name></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"""
        assertFailsWith<FilesDavProtocolException> { parseFileTagAssignments(response(207, body)) }
    }

    @Test
    fun offersOnlyVisibleAssignableTagsAndKeepsRestrictedAssignedTagsReadOnly() {
        val controller = controller(FakeTags()).also { it.start() }
        val offered = controller.state.offered
        assertEquals(listOf(2L, 1L), offered.map { it.id })
        assertFalse(controller.state.canToggle(offered.first()))
        assertTrue(controller.state.canToggle(offered.last()))
    }

    @Test
    fun assignAndUnassignVerifyWithAFreshRead() {
        val fake = FakeTags()
        val controller = controller(fake).also { it.start() }
        val invoices = catalog.first()
        controller.toggle(invoices)
        assertTrue(1L in controller.state.assignedIds)
        controller.toggle(controller.state.assigned!!.first { it.id == 1L })
        assertFalse(1L in controller.state.assignedIds)
        assertNull(controller.state.notice)
    }

    @Test
    fun alreadyAssignedConflictCountsAsDone() {
        val fake = FakeTags()
        val controller = controller(fake).also { it.start() }
        fake.assigned += 1L
        controller.toggle(catalog.first())
        assertNull(controller.state.notice)
        assertTrue(1L in controller.state.assignedIds)
    }

    @Test
    fun unknownAssignResultIsVerifiedOrBlocks() {
        val fake = FakeTags().apply { putThrowsAfterApply = true }
        val controller = controller(fake).also { it.start() }
        controller.toggle(catalog.first())
        assertNull(controller.state.notice)
        assertTrue(1L in controller.state.assignedIds)

        val blocked = FakeTags().apply { putThrowsAfterApply = true }
        val second = controller(blocked).also { it.start() }
        blocked.listFails = true
        second.toggle(catalog.first())
        assertEquals(setOf(1L), second.state.unconfirmedIds)
        assertFalse(second.state.canToggle(catalog.first()))
    }

    @Test
    fun createAndAssignRefusesDuplicateNamesLocally() {
        val fake = FakeTags()
        val controller = controller(fake).also { it.start() }
        controller.createAndAssign("invoices") {}
        assertTrue(fake.requests.none { it.method == FilesDavMethod.POST })
        var created = false
        controller.createAndAssign("Receipts") { created = true }
        assertTrue(created)
        assertTrue(9L in controller.state.assignedIds)
    }
}
