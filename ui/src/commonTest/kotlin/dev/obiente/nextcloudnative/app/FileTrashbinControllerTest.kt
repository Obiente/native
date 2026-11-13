package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.app.FileTrashbinProtocolTest.Companion.capabilities
import dev.obiente.nextcloudnative.app.FileTrashbinProtocolTest.Companion.entry
import dev.obiente.nextcloudnative.app.FileTrashbinProtocolTest.Companion.multistatus
import dev.obiente.nextcloudnative.app.FileTrashbinProtocolTest.Companion.response
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileTrashbinControllerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @AfterTest
    fun cancelScope() = scope.cancel()

    /** Synthetic trash bin: items keyed by DAV segment. */
    private class FakeTrash {
        val items = linkedMapOf("a.d1" to "a", "b.d2" to "b", "c.d3" to "c")
        var capabilitiesJson = """{"files":{"undelete":true}}"""
        val writeStatus = mutableMapOf<String, Int>()
        val writeThrows = mutableSetOf<String>()
        /** Keys whose write throws after the server already applied it. */
        val appliedThenThrows = mutableSetOf<String>()
        var listFails = false
        val requests = mutableListOf<FilesDavRequest>()

        suspend fun execute(request: FilesDavRequest): NextcloudApiResponse {
            requests += request
            if (request.method == FilesDavMethod.PROPFIND) {
                if (listFails) throw IllegalStateException("offline")
                return multistatus(
                    entry("/remote.php/dav/trashbin/alice/trash/", folder = true),
                    *items.entries.mapIndexed { index, (key, name) ->
                        entry("/remote.php/dav/trashbin/alice/trash/$key", name = name, time = index.toLong())
                    }.toTypedArray(),
                )
            }
            val key = request.relativePath.substringAfterLast('/')
            if (request.relativePath.endsWith("/trash")) {
                items.clear()
                return response(204)
            }
            if (key in writeThrows) throw IllegalStateException("reset")
            val status = writeStatus[key] ?: if (request.method == FilesDavMethod.MOVE) 201 else 204
            if (status in 200..299 || key in appliedThenThrows) items.remove(key)
            if (key in appliedThenThrows) throw IllegalStateException("reset after apply")
            return response(status)
        }

        fun writes() = requests.filter { it.method != FilesDavMethod.PROPFIND }
    }

    private fun controller(fake: FakeTrash, restored: MutableList<String> = mutableListOf()) = FileTrashbinController(
        scope = scope,
        userId = "alice",
        execute = fake::execute,
        readCapabilities = { capabilities(fake.capabilitiesJson) },
        onItemsRestored = { items -> restored += items.map { it.key } },
    )

    @Test
    fun loadsAvailabilityAndItems() {
        val controller = controller(FakeTrash()).also { it.start() }
        assertEquals(TrashbinAvailability.Available(true), controller.state.availability)
        assertEquals(setOf("a.d1", "b.d2", "c.d3"), controller.state.items!!.map { it.key }.toSet())
    }

    @Test
    fun disabledTrashbinShowsUnavailableWithoutListing() {
        val fake = FakeTrash().apply { capabilitiesJson = """{"files":{"undelete":false}}""" }
        val controller = controller(fake).also { it.start() }
        assertEquals(TrashbinAvailability.NotEnabled, controller.state.availability)
        assertTrue(fake.requests.isEmpty())
    }

    @Test
    fun bulkRestoreReportsEachItemAndVerifiesWithAFreshListing() {
        val fake = FakeTrash().apply { writeStatus["b.d2"] = 507 }
        val restored = mutableListOf<String>()
        val controller = controller(fake, restored).also { it.start() }
        controller.restore(setOf("a.d1", "b.d2"))
        val report = assertIs<BulkOperationReport>(controller.state.report)
        assertEquals(listOf("a.d1"), report.completed.map { it.itemKey })
        val failed = report.failed.single()
        assertEquals("b.d2", failed.itemKey)
        assertTrue(failed.retrySafe)
        assertEquals(listOf("a.d1"), restored)
        assertEquals(setOf("b.d2", "c.d3"), controller.state.items!!.map { it.key }.toSet())
        assertEquals(1, fake.writes().count { it.relativePath.endsWith("a.d1") })
    }

    @Test
    fun permanentDeleteRequiresSeparateConfirmation() {
        val fake = FakeTrash()
        val controller = controller(fake).also { it.start() }
        controller.requestDelete(setOf("a.d1"))
        assertTrue(fake.writes().isEmpty())
        assertIs<TrashbinConfirmation.DeleteItems>(controller.state.confirmation)
        controller.cancelConfirmation()
        assertTrue(fake.writes().isEmpty())
        controller.requestDelete(setOf("a.d1"))
        controller.confirm()
        assertEquals(1, fake.writes().size)
        assertEquals(FilesDavMethod.DELETE, fake.writes().single().method)
        assertTrue(controller.state.report!!.fullySucceeded)
    }

    @Test
    fun deleteIsHiddenWhenAdministratorDisallowsIt() {
        val fake = FakeTrash().apply { capabilitiesJson = """{"files":{"undelete":true,"delete_from_trash":false}}""" }
        val controller = controller(fake).also { it.start() }
        controller.requestDelete(setOf("a.d1"))
        controller.requestEmpty()
        assertNull(controller.state.confirmation)
        assertTrue(fake.writes().isEmpty())
    }

    @Test
    fun unknownResultIsResolvedByTheVerificationListing() {
        val fake = FakeTrash().apply {
            appliedThenThrows += "a.d1"
            writeThrows += "b.d2"
        }
        val controller = controller(fake).also { it.start() }
        controller.restore(setOf("a.d1", "b.d2"))
        val report = controller.state.report!!
        assertEquals(BulkItemOutcome.Completed, report.results.first { it.itemKey == "a.d1" }.outcome)
        val notApplied = report.results.first { it.itemKey == "b.d2" }
        assertEquals(BulkItemOutcome.Failed, notApplied.outcome)
        assertTrue(notApplied.retrySafe)
    }

    @Test
    fun unknownResultIsNotResolvedByATruncatedListing() {
        val fake = FakeTrash().apply {
            items.clear()
            // The oldest entry is the one being restored; the listing is exactly full.
            items["old.d0"] = "old"
            repeat(MAX_TRASH_ITEMS - 1) { index -> items["k$index.d${index + 1}"] = "k$index" }
            writeThrows += "old.d0"
        }
        val restored = mutableListOf<String>()
        val controller = controller(fake, restored).also { it.start() }
        assertTrue(controller.state.items!!.any { it.key == "old.d0" })
        // Another client deletes a newer file, which pushes the oldest entry out of the kept listing.
        fake.items["newer.d999999"] = "newer"
        controller.restore(setOf("old.d0"))
        val result = assertIs<BulkOperationReport>(controller.state.report).results.single()
        assertEquals(BulkItemOutcome.Unconfirmed, result.outcome)
        assertTrue(restored.isEmpty(), "A restore that never happened is not announced as done.")
    }

    @Test
    fun unverifiableUnknownResultBlocksFurtherWritesUntilAFreshListing() {
        val fake = FakeTrash()
        val controller = controller(fake).also { it.start() }
        fake.writeThrows += "a.d1"
        fake.listFails = true
        controller.restore(setOf("a.d1"))
        val result = controller.state.report!!.results.single()
        assertEquals(BulkItemOutcome.Unconfirmed, result.outcome)
        assertEquals(setOf("a.d1"), controller.state.unconfirmedKeys)
        val writesBefore = fake.writes().size
        controller.restore(setOf("a.d1"))
        assertEquals(writesBefore, fake.writes().size)
        fake.listFails = false
        controller.refresh()
        assertTrue(controller.state.unconfirmedKeys.isEmpty())
    }

    @Test
    fun emptyAsksForConfirmationAndVerifies() {
        val fake = FakeTrash()
        val controller = controller(fake).also { it.start() }
        controller.requestEmpty()
        assertEquals(TrashbinConfirmation.Empty, controller.state.confirmation)
        controller.confirm()
        assertEquals("The trash bin is empty.", controller.state.notice)
        assertTrue(controller.state.items!!.isEmpty())
    }

    @Test
    fun alreadyDeletedItemCountsAsDone() {
        val fake = FakeTrash().apply { writeStatus["a.d1"] = 404 }
        val controller = controller(fake).also { it.start() }
        controller.requestDelete(setOf("a.d1"))
        controller.confirm()
        assertEquals(BulkItemOutcome.AlreadyDone, controller.state.report!!.results.single().outcome)
    }

    @Test
    fun listFailureKeepsLastListing() {
        val fake = FakeTrash()
        val controller = controller(fake).also { it.start() }
        fake.listFails = true
        controller.refresh()
        assertEquals(FilesDavFailureKind.Offline, controller.state.failure)
        assertEquals(3, controller.state.items!!.size)
    }
}
