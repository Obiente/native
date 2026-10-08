package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileDeleteFlowTest {
    private val account = NextcloudSession("https://example.invalid", "alice", "x").accountId
    private val report = file("Documents/report.txt", etag = "v1", fileId = 7L)

    @Test
    fun `confirmed delete closes the dialog and refreshes the folder`(): Unit = runBlocking {
        val deletes = coordinator()
        val state = FileDeleteDialogState(deletes).apply { open(report) }
        val fake = FakeDeleteBoundary(deleteResult = { NextcloudFileMutationResult(null, null) })

        val effect = deletes.finish(state, fake)

        assertEquals(FileDeleteScreenEffect("Deleted report.txt", reloadFolder = true), effect)
        assertNull(state.target)
        assertFalse(state.running)
        assertEquals(listOf(NextcloudFileMutation.Delete("Documents/report.txt", "v1")), fake.deletes)
        assertTrue(fake.reads.isEmpty())
    }

    @Test
    fun `directory delete keeps the collection precondition and verifies its own parent`(): Unit = runBlocking {
        val folder = file("Documents/Archive", etag = "d1", directory = true)
        val deletes = coordinator()
        val state = FileDeleteDialogState(deletes).apply { open(folder) }
        val fake = FakeDeleteBoundary(
            deleteResult = { throw IllegalStateException("connection reset") },
            listing = { NextcloudFileListing(emptyList(), NextcloudFileListingSource.Network) },
        )

        deletes.finish(state, fake)

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
                val deletes = coordinator()
                val state = FileDeleteDialogState(deletes).apply { open(report) }
                val fake = FakeDeleteBoundary(deleteResult = { NextcloudFileMutationResult(null, null, followUp) })

                assertEquals(FileDeleteScreenEffect(notice, reloadFolder = true), deletes.finish(state, fake))
                assertNull(state.target, "The dialog must close after the server confirmed the delete.")
                assertTrue(fake.reads.isEmpty(), "A confirmed result must not be verified by another request.")
            }
        }

    @Test
    fun `definitive server rejection stays in the dialog and allows a safe retry`(): Unit = runBlocking {
        val deletes = coordinator()
        val state = FileDeleteDialogState(deletes).apply { open(report) }
        val fake = FakeDeleteBoundary(deleteResult = { throw fileOperationException(423) })

        assertEquals(FileDeleteScreenEffect(null, reloadFolder = false), deletes.finish(state, fake))
        assertEquals(report, state.target)
        assertFalse(state.running)
        assertEquals("The file is locked by another operation.", state.error)
        assertFalse(state.retryBlocked)
        assertEquals(1, fake.deletes.size)
        assertTrue(fake.reads.isEmpty())
        assertNotNull(state.begin(), "A definitive rejection did not change the server, so retry is safe.")
    }

    @Test
    fun `stale version precondition blocks retry until the folder is refreshed`(): Unit = runBlocking {
        for (directory in listOf(false, true)) {
            val item = report.copy(isDirectory = directory)
            val deletes = coordinator()
            val state = FileDeleteDialogState(deletes).apply { open(item) }
            val fake = FakeDeleteBoundary(deleteResult = { throw fileOperationException(412, directory) })

            assertEquals(FileDeleteScreenEffect(null, reloadFolder = true), deletes.finish(state, fake))
            assertEquals(item, state.target)
            assertTrue(state.retryBlocked)
            assertEquals(fileOperationException(412, directory).message, state.error)
            assertNull(state.begin(), "The same ETag precondition cannot succeed again.")
            assertEquals(1, fake.deletes.size)
            assertTrue(fake.reads.isEmpty(), "A 412 is a definitive answer, not an unknown result.")
        }
    }

    @Test
    fun `throttled delete is not treated as unknown and does not allow an immediate retry`(): Unit = runBlocking {
        val deletes = coordinator()
        val state = FileDeleteDialogState(deletes).apply { open(report) }
        val fake = FakeDeleteBoundary(deleteResult = { throw fileOperationException(429) })

        val effect = deletes.finish(state, fake)

        assertEquals(FileDeleteScreenEffect(null, reloadFolder = false), effect, "A reload is another request.")
        assertEquals("The server is limiting requests. Wait a while, then refresh and try again.", state.error)
        assertTrue(state.retryBlocked)
        assertNull(state.begin())
        assertEquals(1, fake.deletes.size)
        assertTrue(fake.reads.isEmpty(), "Throttling must not start ambiguity verification reads.")
    }

    @Test
    fun `missing item is reported as no longer in the folder, not as deleted`(): Unit = runBlocking {
        val deletes = coordinator()
        val state = FileDeleteDialogState(deletes).apply { open(report) }
        val fake = FakeDeleteBoundary(deleteResult = { throw fileOperationException(404) })

        assertEquals(FileDeleteScreenEffect("report.txt is no longer in this folder.", true), deletes.finish(state, fake))
        assertNull(state.target)
    }

    @Test
    fun `path absence after an unknown result never claims deletion and never resends`(): Unit = runBlocking {
        val deletes = coordinator()
        val state = FileDeleteDialogState(deletes).apply { open(report) }
        val fake = FakeDeleteBoundary(
            deleteResult = { throw IllegalStateException("response interrupted") },
            listing = { NextcloudFileListing(listOf(file("Documents/other.txt", fileId = 8L)), NextcloudFileListingSource.Network) },
        )

        val effect = deletes.finish(state, fake)

        assertEquals(
            FileDeleteScreenEffect(
                "report.txt is no longer in this folder. The delete response was interrupted, so it may " +
                    "have been deleted, moved, or renamed.",
                reloadFolder = true,
            ),
            effect,
        )
        assertNull(state.target)
        assertEquals(1, fake.deletes.size)
        assertEquals(listOf("Documents"), fake.reads)
    }

    @Test
    fun `same file ID under another name after an unknown result is a rename, not a delete`(): Unit = runBlocking {
        val deletes = coordinator()
        val state = FileDeleteDialogState(deletes).apply { open(report) }
        val renamed = file("Documents/final.txt", fileId = 7L)
        val fake = FakeDeleteBoundary(
            deleteResult = { throw IllegalStateException("reset") },
            listing = { NextcloudFileListing(listOf(renamed), NextcloudFileListingSource.Network) },
        )

        assertEquals(FileDeleteScreenEffect(null, reloadFolder = true), deletes.finish(state, fake))
        assertEquals("report.txt was renamed on the server. Refresh the folder.", state.error)
        assertTrue(state.retryBlocked)
    }

    @Test
    fun `server failure is verified because a partial delete is possible`(): Unit = runBlocking {
        val deletes = coordinator()
        val state = FileDeleteDialogState(deletes).apply { open(report) }
        val fake = FakeDeleteBoundary(
            deleteResult = { throw fileOperationException(503) },
            listing = { NextcloudFileListing(listOf(report), NextcloudFileListingSource.Network) },
        )

        deletes.finish(state, fake)

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
            val deletes = coordinator()
            val state = FileDeleteDialogState(deletes).apply { open(report) }
            val fake = FakeDeleteBoundary(deleteResult = { throw IllegalStateException("reset") }, listing = listing)

            assertEquals(FileDeleteScreenEffect(null, reloadFolder = true), deletes.finish(state, fake))
            assertTrue(state.retryBlocked)
            assertNotNull(state.error)
            assertNull(state.begin(), "An unknown delete result must not be retried blindly.")
            assertEquals(1, fake.deletes.size)
        }
    }

    @Test
    fun `leaving the Files screen during verification keeps the delete owned and blocked`(): Unit = runBlocking {
        val deletes = coordinator()
        val firstScreen = FileDeleteDialogState(deletes).apply { open(report) }
        val readStarted = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        val fake = FakeDeleteBoundary(
            deleteResult = { throw IllegalStateException("response interrupted") },
            listing = {
                readStarted.complete(Unit)
                releaseRead.await()
                NextcloudFileListing(emptyList(), NextcloudFileListingSource.Network)
            },
        )
        val screenScope = CoroutineScope(coroutineContext + Job())
        deletes.start(assertNotNull(firstScreen.begin()), fake::delete, fake::read)
        readStarted.await()

        screenScope.cancel()
        val returnedScreen = FileDeleteDialogState(deletes).apply { open(report) }

        assertTrue(returnedScreen.running, "The unverified delete still owns the item.")
        assertTrue(deletes.blocksWrites(report))
        assertNull(returnedScreen.begin())
        releaseRead.complete(Unit)
        val effect = deletes.awaitCompletion(returnedScreen)

        assertEquals(true, effect.reloadFolder)
        assertNull(returnedScreen.target, "The same version shown again receives the verified outcome.")
        assertFalse(deletes.blocksWrites(report))
        assertEquals(1, fake.deletes.size)
    }

    @Test
    fun `leaving the Files screen while local follow-up runs still reports the server delete`(): Unit =
        runBlocking {
            val deletes = coordinator()
            val state = FileDeleteDialogState(deletes).apply { open(report) }
            val serverConfirmed = CompletableDeferred<Unit>()
            val followUpDone = CompletableDeferred<Unit>()
            val fake = FakeDeleteBoundary(deleteResult = {
                serverConfirmed.complete(Unit)
                followUpDone.await()
                NextcloudFileMutationResult(null, null, FileMutationLocalFollowUp.StillRunning)
            })
            deletes.start(assertNotNull(state.begin()), fake::delete, fake::read)
            serverConfirmed.await()

            state.dismiss()
            followUpDone.complete(Unit)
            val effect = deletes.awaitCompletion(FileDeleteDialogState(deletes))

            assertEquals(
                FileDeleteScreenEffect("Deleted report.txt. Local file status is still updating in the background.", true),
                effect,
            )
            assertFalse(deletes.blocksWrites(report))
        }

    @Test
    fun `account session teardown cancels the delete and releases the item without an outcome`(): Unit =
        runBlocking {
            val deletes = coordinator()
            val state = FileDeleteDialogState(deletes).apply { open(report) }
            val started = CompletableDeferred<Unit>()
            val fake = FakeDeleteBoundary(deleteResult = {
                started.complete(Unit)
                awaitCancellation()
            })
            val job = deletes.start(assertNotNull(state.begin()), fake::delete, fake::read)
            started.await()

            deletes.close()
            job.join()

            assertTrue(job.isCancelled)
            assertNull(deletes.nextCompletion)
            assertFalse(state.running)
            assertNull(state.error)
            assertTrue(fake.reads.isEmpty(), "Cancellation is not an unknown result to verify.")
        }

    @Test
    fun `noncooperative completion after teardown is not published`(): Unit = runBlocking {
        val deletes = coordinator()
        val state = FileDeleteDialogState(deletes).apply { open(report) }
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fake = FakeDeleteBoundary(deleteResult = {
            withContext(NonCancellable) {
                started.complete(Unit)
                release.await()
            }
            NextcloudFileMutationResult(null, null)
        })
        val job = deletes.start(assertNotNull(state.begin()), fake::delete, fake::read)
        started.await()

        deletes.close()
        release.complete(Unit)
        job.join()

        assertNull(deletes.nextCompletion)
        assertFalse(state.running)
        assertEquals(report, state.target)
    }

    @Test
    fun `closing a running dialog keeps the item blocked until the delete is verified`(): Unit = runBlocking {
        val deletes = coordinator()
        val state = FileDeleteDialogState(deletes).apply { open(report) }
        val release = CompletableDeferred<Unit>()
        val fake = FakeDeleteBoundary(deleteResult = {
            release.await()
            NextcloudFileMutationResult(null, null)
        })
        deletes.start(assertNotNull(state.begin()), fake::delete, fake::read)

        state.dismiss()
        state.open(report)
        assertTrue(state.running, "The reopened dialog shows the unresolved delete.")
        assertNull(state.begin(), "A second DELETE must not be sent while the first is unresolved.")
        state.open(report.copy(etag = "v2"))
        assertNull(state.begin(), "Another version of the same item is blocked too.")
        state.open(report)
        release.complete(Unit)

        assertEquals(FileDeleteScreenEffect("Deleted report.txt", true), deletes.awaitCompletion(state))
        assertNull(state.target, "The reopened dialog for the same version receives the verified result.")
        assertEquals(1, fake.deletes.size)
    }

    @Test
    fun `verified rejection releases the item for a reopened dialog of another version`(): Unit = runBlocking {
        val deletes = coordinator()
        val state = FileDeleteDialogState(deletes).apply { open(report) }
        val release = CompletableDeferred<Unit>()
        val fake = FakeDeleteBoundary(deleteResult = {
            release.await()
            throw fileOperationException(423)
        })
        deletes.start(assertNotNull(state.begin()), fake::delete, fake::read)

        state.dismiss()
        val refreshed = report.copy(etag = "v2")
        state.open(refreshed)
        assertTrue(state.running)
        release.complete(Unit)
        deletes.awaitCompletion(state)

        assertEquals(refreshed, state.target)
        assertNull(state.error, "The result belongs to the earlier version.")
        assertFalse(state.running)
        assertEquals("v2", assertNotNull(state.begin()).expectedEtag)
    }

    @Test
    fun `unverified delete blocks writes to the item, its folders, and its children in that account only`() =
        runBlocking {
            val folder = file("Documents", directory = true)
            val deletes = coordinator()
            val otherAccount = coordinator(NextcloudSession("https://example.invalid", "bob", "x").accountId)
            val state = FileDeleteDialogState(deletes).apply { open(folder.copy(etag = "f1")) }
            val release = CompletableDeferred<Unit>()
            val fake = FakeDeleteBoundary(deleteResult = {
                release.await()
                NextcloudFileMutationResult(null, null)
            })
            deletes.start(assertNotNull(state.begin()), fake::delete, fake::read)

            assertTrue(deletes.blocksWrites(folder))
            assertTrue(deletes.blocksWrites(report), "A child of a folder being deleted must wait.")
            assertFalse(deletes.blocksWrites(file("Music/song.mp3")))
            assertFalse(deletes.blocksWrites(file("Documents2/a.txt")), "A name prefix is not a parent.")
            assertFalse(otherAccount.blocksWrites(report))
            val child = FileDeleteDialogState(deletes).apply { open(report) }
            assertNull(child.begin())
            assertEquals("Another delete in this location is still finishing. Try again when it completes.", child.error)

            release.complete(Unit)
            deletes.awaitCompletion(state)
            assertFalse(deletes.blocksWrites(report))
            otherAccount.close()
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
                val deletes = coordinator()
                val state = FileDeleteDialogState(deletes).apply { open(report) }
                val release = CompletableDeferred<Unit>()
                val fake = FakeDeleteBoundary(deleteResult = {
                    release.await()
                    result()
                })
                deletes.start(assertNotNull(state.begin()), fake::delete, fake::read)

                state.dismiss()
                assertNull(state.target)
                assertFalse(state.running, "A closed dialog no longer shows the running delete.")
                state.open(other)
                release.complete(Unit)

                assertEquals(expected, deletes.awaitCompletion(state))
                assertEquals(other, state.target)
                assertFalse(state.running)
                assertNull(state.error)
                assertFalse(state.retryBlocked)
            }
        }

    @Test
    fun `delete without a current version asks for a refresh instead of sending`(): Unit = runBlocking {
        val state = FileDeleteDialogState(coordinator()).apply { open(report.copy(etag = " ")) }

        assertNull(state.begin())
        assertEquals("Refresh the folder before deleting this item.", state.error)
        assertFalse(state.running)
    }

    @Test
    fun `a claimed delete cannot be started twice`(): Unit = runBlocking {
        val state = FileDeleteDialogState(coordinator()).apply { open(report) }

        assertNotNull(state.begin())
        assertNull(state.begin())
    }

    /** The event loop stays the test's, but its job does not keep the test running. */
    private fun CoroutineScope.coordinator(accountId: NextcloudAccountId = account) =
        FileDeleteCoordinator(accountId, CoroutineScope(coroutineContext.minusKey(Job)))

    /** Starts the open dialog's delete and applies its outcome as a Files screen would. */
    private suspend fun FileDeleteCoordinator.finish(
        state: FileDeleteDialogState,
        fake: FakeDeleteBoundary,
    ): FileDeleteScreenEffect {
        start(assertNotNull(state.begin()), fake::delete, fake::read).join()
        return awaitCompletion(state)
    }

    private suspend fun FileDeleteCoordinator.awaitCompletion(state: FileDeleteDialogState): FileDeleteScreenEffect {
        while (true) {
            val completion = nextCompletion
            if (completion != null && consume(completion)) return state.complete(completion)
            yield()
        }
    }

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

    private fun file(path: String, etag: String? = "e", directory: Boolean = false, fileId: Long? = null) =
        NextcloudFile(
            path = path,
            name = path.substringAfterLast('/'),
            isDirectory = directory,
            mimeType = if (directory) null else "text/plain",
            size = 1,
            lastModified = null,
            fileId = fileId,
            hasPreview = false,
            etag = etag,
        )
}
