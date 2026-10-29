package dev.obiente.nextcloudnative.app

import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Every desktop file write returns its server result while local bookkeeping is stuck behind the
 * virtual-file provider's monitor. [providerLock] stands in for that monitor: a test thread holds
 * it, and each refresh waits for it exactly like the production refresh.
 */
class DesktopFileWritesTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val providerLock = Any()
    private val entered = Collections.synchronizedList(mutableListOf<String>())
    private val refreshed = Collections.synchronizedList(mutableListOf<String>())
    private val releases = Collections.synchronizedList(mutableListOf<CountDownLatch>())

    @AfterTest
    fun tearDown() {
        releases.forEach(CountDownLatch::countDown)
        scope.cancel()
    }

    @Test
    fun `every routed write returns its server result while the provider lock is held`(): Unit = runBlocking {
        for (write in routedWrites) {
            val followUps = followUps(waitMillis = 50L)
            val writes = writes(followUps, respondingTo(write.status))
            val release = holdProviderLock()

            val followUp = withTimeout(5_000L) { write.run(writes) }

            assertEquals(FileMutationLocalFollowUp.StillRunning, followUp, write.name)
            assertEquals(
                "Done. Local file status is still updating in the background.",
                followUp.reportFor("Done"),
                "${write.name} is not reported as plain success.",
            )
            assertTrue(refreshed.none { it in write.paths }, "${write.name} cannot refresh while the lock is held.")
            release.countDown()
            withTimeout(5_000L) { while (!refreshed.containsAll(write.paths)) delay(10L) }
            refreshed.clear()
        }
    }

    @Test
    fun `an unknown result is reported without waiting for the stuck refresh`(): Unit = runBlocking {
        val followUps = followUps(waitMillis = 50L)
        val writes = writes(followUps) { _, onAcceptedOrUnknownResult ->
            onAcceptedOrUnknownResult()
            throw DesktopFileSyncAmbiguousMutationException(IOException("reset"))
        }
        holdProviderLock()

        val unknown = assertFailsWith<FileMutationOutcomeUnknownException> {
            withTimeout(5_000L) { writes.createDirectoryIfAbsent("Docs/New") }
        }

        assertEquals(FileMutationLocalFollowUp.StillRunning, unknown.localFollowUp)
    }

    @Test
    fun `a write the server did not apply queues no local refresh`(): Unit = runBlocking {
        val followUps = followUps()
        val writes = writes(followUps, respondingTo(412))

        assertEquals(DirectoryCreation(wasCreated = false), writes.createDirectoryIfAbsent("Docs/New"))
        assertEquals(SavedTextFile(etag = null, wasCreated = false), writes.createTextFileIfAbsent("Docs/new.md", ""))
        assertEquals(0, followUps.pendingCount)
        assertTrue(refreshed.isEmpty())
    }

    @Test
    fun `cancelling the caller keeps the queued refresh and is not reported as an error`(): Unit = runBlocking {
        for (write in routedWrites) {
            val followUps = followUps(waitMillis = 60_000L)
            val sent = CountDownLatch(1)
            val writes = writes(followUps) { request, onAcceptedOrUnknownResult ->
                respondingTo(write.status).send(request, onAcceptedOrUnknownResult).also { sent.countDown() }
            }
            val release = holdProviderLock()
            var failure: Throwable? = null
            val caller = scope.launch {
                try {
                    write.run(writes)
                } catch (thrown: Throwable) {
                    failure = thrown
                    throw thrown
                }
            }
            assertTrue(sent.await(5, TimeUnit.SECONDS), write.name)

            withTimeout(5_000L) { caller.cancelAndJoin() }

            assertIs<CancellationException>(failure, "${write.name} reports cancellation, not a failure: $failure")
            release.countDown()
            withTimeout(5_000L) { while (!refreshed.containsAll(write.paths)) delay(10L) }
            refreshed.clear()
        }
    }

    @Test
    fun `account abandonment fails waiting writes and ignores the stale completion`(): Unit = runBlocking {
        val followUps = followUps(waitMillis = 60_000L)
        val writes = writes(followUps, respondingTo(201))
        val release = holdProviderLock()
        val running = async { writes.createDirectoryIfAbsent("Docs/Running") }
        withTimeout(5_000L) { while ("Docs/Running" !in entered) delay(10L) }
        val queued = async { writes.createTextFileIfAbsent("Docs/queued.md", "") }
        withTimeout(5_000L) { while (followUps.pendingCount < 1) delay(10L) }

        followUps.abandonAccount(ACCOUNT)

        assertEquals(FileMutationLocalFollowUp.Failed, withTimeout(5_000L) { running.await() }.localFollowUp)
        assertEquals(FileMutationLocalFollowUp.Failed, withTimeout(5_000L) { queued.await() }.localFollowUp)
        release.countDown()
        withTimeout(5_000L) { while ("Docs/Running" !in refreshed) delay(10L) }
        assertFalse("Docs/queued.md" in refreshed, "An abandoned refresh never runs: $refreshed")
        assertEquals(0, followUps.pendingCount)
    }

    @Test
    fun `a full queue reports that the folder must be refreshed`(): Unit = runBlocking {
        val followUps = followUps(waitMillis = 50L, maximumPending = 1)
        val writes = writes(followUps, respondingTo(201))
        holdProviderLock()
        assertEquals(FileMutationLocalFollowUp.StillRunning, writes.createDirectoryIfAbsent("Docs/Running").localFollowUp)
        withTimeout(5_000L) { while ("Docs/Running" !in entered) delay(10L) }
        assertEquals(FileMutationLocalFollowUp.StillRunning, writes.createDirectoryIfAbsent("Docs/Queued").localFollowUp)

        val dropped = withTimeout(5_000L) { writes.createDirectoryIfAbsent("Docs/Dropped") }

        assertEquals(DirectoryCreation(wasCreated = true, FileMutationLocalFollowUp.Failed), dropped)
        assertEquals(
            "Created Dropped. Local file status could not be updated; refresh the folder if it looks out of date.",
            dropped.localFollowUp.reportFor("Created Dropped"),
        )
        val unknownWrites = writes(followUps) { _, onAcceptedOrUnknownResult ->
            onAcceptedOrUnknownResult()
            throw DesktopFileSyncAmbiguousMutationException(IOException("reset"))
        }
        val unknown = assertFailsWith<DesktopFileMutationReconciliationDroppedException> {
            withTimeout(5_000L) { unknownWrites.createDirectoryIfAbsent("Docs/Unknown") }
        }
        assertEquals(FileMutationLocalFollowUp.Failed, unknown.localFollowUp)
    }

    private class RoutedWrite(
        val name: String,
        val status: Int,
        val paths: List<String>,
        val run: suspend (DesktopFileWrites) -> FileMutationLocalFollowUp,
    )

    private val notes = NextcloudFile(
        path = "Docs/notes.txt",
        name = "notes.txt",
        isDirectory = false,
        mimeType = "text/plain",
        size = 4L,
        lastModified = null,
        fileId = 7L,
        hasPreview = false,
        etag = "\"e1\"",
        permissions = "RGDNVW",
    )

    private val routedWrites = listOf(
        RoutedWrite("create folder", 201, listOf("Docs/New")) { it.createDirectoryIfAbsent("Docs/New").localFollowUp },
        RoutedWrite("create text file", 201, listOf("Docs/new.md")) { it.createTextFileIfAbsent("Docs/new.md", "").localFollowUp },
        RoutedWrite("save text file", 204, listOf(notes.path)) { it.saveTextFile(notes.path, "text", "\"e1\"").localFollowUp },
        RoutedWrite("favorite", 200, listOf(notes.path)) { it.setFileFavorite(notes, favorite = true) },
        RoutedWrite("restore version", 201, listOf(notes.path)) {
            it.restoreFileVersion(notes, NextcloudFileVersion(7L, "1700000000", 1L, "Older", "e0", null, null))
        },
        RoutedWrite("rename", 201, listOf(notes.path, "Docs/renamed.txt")) {
            it.executeFileMutation(NextcloudFileMutation.Rename(notes.path, "renamed.txt", "\"e1\"")).localFollowUp
        },
    )

    private fun respondingTo(status: Int) = DesktopFileWriteTransport { _, onAcceptedOrUnknownResult ->
        if (status in 200..299) onAcceptedOrUnknownResult()
        DesktopFileWriteResponse(status, etag = "\"e2\"", body = byteArrayOf())
    }

    private fun writes(followUps: DesktopFileMutationFollowUps, transport: DesktopFileWriteTransport) = DesktopFileWrites(
        accountId = ACCOUNT,
        serverUrl = "https://cloud.example.test",
        userId = "alice",
        followUps = followUps,
        transport = transport,
        refreshLocalState = { path ->
            entered += path
            synchronized(providerLock) { refreshed += path }
            null
        },
    )

    private fun followUps(waitMillis: Long = 5_000L, maximumPending: Int = MAX_PENDING_FILE_MUTATION_FOLLOW_UPS) =
        DesktopFileMutationFollowUps(scope, completionWaitMillis = waitMillis, maximumPending = maximumPending)

    /** Holds [providerLock] on another thread, as a stalled virtual-file provider call would. */
    private fun holdProviderLock(): CountDownLatch {
        val release = CountDownLatch(1).also(releases::add)
        val held = CountDownLatch(1)
        thread(isDaemon = true, name = "stalled-virtual-file-provider") {
            synchronized(providerLock) {
                held.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        assertTrue(held.await(5, TimeUnit.SECONDS), "The provider lock was not taken.")
        return release
    }

    private companion object {
        const val ACCOUNT = "account-digest"
    }
}
