package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileDeleteFlowTest {
    private val report = file("Documents/report.txt", etag = "v1")

    @Test
    fun `confirmed delete closes the dialog and refreshes the folder`(): Unit = runBlocking {
        val state = FileDeleteDialogState().apply { open(report) }
        val fake = FakeDeleteBoundary(deleteResult = { NextcloudFileMutationResult(null, null) })

        val effect = state.runDelete(state.beginOrFail(), fake::delete, fake::read)

        assertEquals(FileDeleteScreenEffect("Deleted report.txt", reloadFolder = true), effect)
        assertNull(state.target)
        assertFalse(state.running)
        assertEquals(listOf(NextcloudFileMutation.Delete("Documents/report.txt", "v1")), fake.deletes)
        assertTrue(fake.reads.isEmpty())
    }

    @Test
    fun `directory delete keeps the collection precondition and verifies its own parent`(): Unit = runBlocking {
        val folder = file("Documents/Archive", etag = "d1", directory = true)
        val state = FileDeleteDialogState().apply { open(folder) }
        val fake = FakeDeleteBoundary(
            deleteResult = { throw IllegalStateException("connection reset") },
            listing = { NextcloudFileListing(emptyList(), NextcloudFileListingSource.Network) },
        )

        state.runDelete(state.beginOrFail(), fake::delete, fake::read)

        assertTrue(fake.deletes.single().sourceIsDirectory)
        assertEquals(listOf("Documents"), fake.reads)
    }

    @Test
    fun `server-confirmed delete is reported even when local cleanup is still running or failed`(): Unit =
        runBlocking {
            for ((followUp, notice) in listOf(
                FileMutationLocalFollowUp.StillRunning to
                    "Deleted report.txt. Local file status is still updating in the background.",
                FileMutationLocalFollowUp.Failed to
                    "Deleted report.txt. Local file status could not be updated; refresh if it still appears.",
            )) {
                val state = FileDeleteDialogState().apply { open(report) }
                val fake = FakeDeleteBoundary(deleteResult = { NextcloudFileMutationResult(null, null, followUp) })

                val effect = state.runDelete(state.beginOrFail(), fake::delete, fake::read)

                assertEquals(FileDeleteScreenEffect(notice, reloadFolder = true), effect)
                assertNull(state.target, "The dialog must close after the server confirmed the delete.")
                assertTrue(fake.reads.isEmpty(), "A confirmed result must not be verified by another request.")
            }
        }

    @Test
    fun `definitive server rejection stays in the dialog and allows a safe retry`(): Unit = runBlocking {
        val state = FileDeleteDialogState().apply { open(report) }
        val fake = FakeDeleteBoundary(deleteResult = { throw fileOperationException(412) })

        val effect = state.runDelete(state.beginOrFail(), fake::delete, fake::read)

        assertEquals(FileDeleteScreenEffect(null, reloadFolder = false), effect)
        assertEquals(report, state.target)
        assertFalse(state.running)
        assertEquals("The file or destination changed. Refresh and try again.", state.error)
        assertFalse(state.retryBlocked)
        assertEquals(1, fake.deletes.size)
        assertTrue(fake.reads.isEmpty())
        assertNotNull(state.begin(), "A definitive rejection did not change the server, so retry is safe.")
    }

    @Test
    fun `missing item closes the dialog without claiming this request deleted it`(): Unit = runBlocking {
        val state = FileDeleteDialogState().apply { open(report) }
        val fake = FakeDeleteBoundary(deleteResult = { throw fileOperationException(404) })

        val effect = state.runDelete(state.beginOrFail(), fake::delete, fake::read)

        assertEquals(FileDeleteScreenEffect("report.txt is no longer on the server.", true), effect)
        assertNull(state.target)
    }

    @Test
    fun `unknown result is verified by reading the folder and never resends the delete`(): Unit = runBlocking {
        val state = FileDeleteDialogState().apply { open(report) }
        val fake = FakeDeleteBoundary(
            deleteResult = { throw IllegalStateException("response interrupted") },
            listing = { NextcloudFileListing(listOf(file("Documents/other.txt")), NextcloudFileListingSource.Network) },
        )

        val effect = state.runDelete(state.beginOrFail(), fake::delete, fake::read)

        assertEquals(
            FileDeleteScreenEffect(
                "Deleted report.txt. The server response was interrupted, so the folder was checked.",
                reloadFolder = true,
            ),
            effect,
        )
        assertNull(state.target)
        assertEquals(1, fake.deletes.size)
        assertEquals(listOf("Documents"), fake.reads)
    }

    @Test
    fun `server failure is verified because a partial delete is possible`(): Unit = runBlocking {
        val state = FileDeleteDialogState().apply { open(report) }
        val fake = FakeDeleteBoundary(
            deleteResult = { throw fileOperationException(503) },
            listing = { NextcloudFileListing(listOf(report), NextcloudFileListingSource.Network) },
        )

        state.runDelete(state.beginOrFail(), fake::delete, fake::read)

        assertEquals("The delete did not finish. report.txt is still on the server.", state.error)
        assertFalse(state.retryBlocked, "The exact version is still present, so retry is safe.")
        assertEquals(1, fake.deletes.size)
    }

    @Test
    fun `unverifiable or changed state blocks retry until the folder is refreshed`(): Unit = runBlocking {
        val unverifiable = listOf<suspend () -> NextcloudFileListing>(
            { throw IllegalStateException("offline") },
            { NextcloudFileListing(emptyList(), NextcloudFileListingSource.Cache) },
            { NextcloudFileListing(listOf(report.copy(etag = "v2")), NextcloudFileListingSource.Network) },
        )
        for (listing in unverifiable) {
            val state = FileDeleteDialogState().apply { open(report) }
            val fake = FakeDeleteBoundary(deleteResult = { throw IllegalStateException("reset") }, listing = listing)

            val effect = state.runDelete(state.beginOrFail(), fake::delete, fake::read)

            assertEquals(FileDeleteScreenEffect(null, reloadFolder = true), effect)
            assertTrue(state.retryBlocked)
            assertNotNull(state.error)
            assertFalse(state.running)
            assertNull(state.begin(), "An unknown delete result must not be retried blindly.")
            assertEquals(1, fake.deletes.size)
        }
    }

    @Test
    fun `cancellation propagates and releases the dialog without an error or effect`(): Unit = runBlocking {
        val state = FileDeleteDialogState().apply { open(report) }
        val started = CompletableDeferred<Unit>()
        val fake = FakeDeleteBoundary(deleteResult = {
            started.complete(Unit)
            awaitCancellation()
        })
        var effect: FileDeleteScreenEffect? = null
        val request = state.beginOrFail()

        val job = launch { effect = state.runDelete(request, fake::delete, fake::read) }
        started.await()
        assertTrue(state.running)
        job.cancel()
        job.join()

        assertNull(effect)
        assertEquals(report, state.target)
        assertFalse(state.running)
        assertNull(state.error)
        assertTrue(fake.reads.isEmpty(), "Cancellation is not an unknown result to verify.")
    }

    @Test
    fun `cancelled noncooperative completion cannot change the dialog`(): Unit = runBlocking {
        val state = FileDeleteDialogState().apply { open(report) }
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fake = FakeDeleteBoundary(deleteResult = {
            withContext(NonCancellable) {
                started.complete(Unit)
                release.await()
            }
            NextcloudFileMutationResult(null, null)
        })
        val request = state.beginOrFail()

        val job = launch { state.runDelete(request, fake::delete, fake::read) }
        started.await()
        job.cancel()
        release.complete(Unit)
        job.join()

        assertEquals(report, state.target)
        assertFalse(state.running)
        assertNull(state.error)
    }

    @Test
    fun `completion after the dialog closed reports to the screen without changing a newer dialog`(): Unit =
        runBlocking {
            val other = file("Documents/other.txt", etag = "o1")
            val confirmed: suspend () -> NextcloudFileMutationResult = { NextcloudFileMutationResult(null, null) }
            val locked: suspend () -> NextcloudFileMutationResult = { throw fileOperationException(423) }
            for ((result, expected) in listOf(
                confirmed to FileDeleteScreenEffect("Deleted report.txt", true),
                locked to FileDeleteScreenEffect(
                    "report.txt was not deleted. The file is locked by another operation.",
                    reloadFolder = false,
                ),
            )) {
                val state = FileDeleteDialogState().apply { open(report) }
                val release = CompletableDeferred<Unit>()
                val fake = FakeDeleteBoundary(deleteResult = {
                    release.await()
                    result()
                })
                val request = state.beginOrFail()
                var effect: FileDeleteScreenEffect? = null
                val job = launch(start = CoroutineStart.UNDISPATCHED) {
                    effect = state.runDelete(request, fake::delete, fake::read)
                }

                state.dismiss()
                assertNull(state.target)
                assertFalse(state.running, "A closed dialog no longer shows the running delete.")
                state.open(other)
                release.complete(Unit)
                job.join()

                assertEquals(expected, effect)
                assertEquals(other, state.target)
                assertFalse(state.running)
                assertNull(state.error)
                assertFalse(state.retryBlocked)
            }
        }

    @Test
    fun `delete without a current version asks for a refresh instead of sending`() {
        val state = FileDeleteDialogState().apply { open(report.copy(etag = " ")) }

        assertNull(state.begin())
        assertEquals("Refresh the folder before deleting this item.", state.error)
        assertFalse(state.running)
    }

    @Test
    fun `a running delete cannot be started twice`() {
        val state = FileDeleteDialogState().apply { open(report) }

        assertNotNull(state.begin())
        assertNull(state.begin())
    }

    private fun FileDeleteDialogState.beginOrFail(): FileDeleteRequest = assertNotNull(begin())

    private class FakeDeleteBoundary(
        private val deleteResult: suspend () -> NextcloudFileMutationResult,
        private val listing: suspend () -> NextcloudFileListing = { error("The folder must not be read.") },
    ) {
        val deletes = mutableListOf<NextcloudFileMutation.Delete>()
        val reads = mutableListOf<String>()

        suspend fun delete(mutation: NextcloudFileMutation.Delete): NextcloudFileMutationResult {
            deletes += mutation
            return deleteResult()
        }

        suspend fun read(folder: String): NextcloudFileListing {
            reads += folder
            return listing()
        }
    }

    private fun file(path: String, etag: String? = "e", directory: Boolean = false) = NextcloudFile(
        path = path,
        name = path.substringAfterLast('/'),
        isDirectory = directory,
        mimeType = if (directory) null else "text/plain",
        size = 1,
        lastModified = null,
        fileId = null,
        hasPreview = false,
        etag = etag,
    )
}
