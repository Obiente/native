package dev.obiente.nextcloudnative

import androidx.work.ExistingWorkPolicy
import dev.obiente.nextcloudnative.app.DurableUploadEnqueueResult
import dev.obiente.nextcloudnative.app.DurableUploadScope
import dev.obiente.nextcloudnative.app.DurableUploadState
import dev.obiente.nextcloudnative.app.NextcloudApiMethod
import dev.obiente.nextcloudnative.app.NextcloudMultipartUploadRequest
import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.accountRecord
import dev.obiente.nextcloudnative.app.afterProcessRecovery
import dev.obiente.nextcloudnative.app.localUploadFile
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.json.JSONArray

class AndroidDurableMultipartUploadPolicyTest {
    @Test
    fun `successful worker execution does not request scheduling recovery`() = runBlocking {
        var recoveryRequests = 0

        val result = runDurableUploadWorkerWithRecoverySignal(
            requestRecovery = { recoveryRequests += 1 },
            work = { "completed" },
        )

        assertEquals("completed", result)
        assertEquals(0, recoveryRequests)
    }

    @Test
    fun `worker failure requests scheduling recovery before preserving the failure`() = runBlocking {
        var recoveryRequests = 0
        val expected = IOException("journal read failed")

        val actual = assertFailsWith<IOException> {
            runDurableUploadWorkerWithRecoverySignal<Unit>(
                requestRecovery = { recoveryRequests += 1 },
                work = { throw expected },
            )
        }

        assertTrue(actual === expected)
        assertEquals(1, recoveryRequests)
    }

    @Test
    fun `worker cancellation requests scheduling recovery before preserving cancellation`() = runBlocking {
        var recoveryRequests = 0
        val expected = CancellationException("worker stopped")

        val actual = assertFailsWith<CancellationException> {
            runDurableUploadWorkerWithRecoverySignal<Unit>(
                requestRecovery = { recoveryRequests += 1 },
                work = { throw expected },
            )
        }

        assertTrue(actual === expected)
        assertEquals(1, recoveryRequests)
    }

    @Test
    fun `worker cancellation does not become a terminal upload outcome`() = runBlocking {
        assertFailsWith<CancellationException> {
            captureDurableUploadRequestOutcome<Unit> {
                throw CancellationException("worker stopped")
            }
        }
        assertTrue(
            captureDurableUploadRequestOutcome<Unit> {
                throw IOException("transport failed")
            }.isFailure,
        )
    }

    @Test
    fun `account cleanup removes a row only after its source capability is released`() = runBlocking {
        val first = fixtureJob(index = 1, account = ACCOUNT_A, cardId = 42)
        val second = fixtureJob(index = 2, account = ACCOUNT_A, cardId = 43)
        val events = mutableListOf<String>()

        assertFailsWith<IllegalStateException> {
            removeAndroidDurableUploadJobs(
                jobs = listOf(first, second),
                cancelWork = { job -> events += "cancel:${job.id}" },
                releaseCapability = { job ->
                    events += "release:${job.id}"
                    job == first
                },
                removeJob = { jobId -> events += "remove:$jobId" },
            )
        }

        assertEquals(
            listOf(
                "cancel:${first.id}",
                "cancel:${second.id}",
                "release:${first.id}",
                "remove:${first.id}",
                "release:${second.id}",
            ),
            events,
        )
    }

    @Test
    fun `removing an account deletes only its queued upload recovery rows`() {
        val storage = FakeDurableUploadEncryptedStorage()
        val store = AndroidDurableMultipartUploadStore(storage, FakeDurableUploadCipher())
        val first = fixtureJob(index = 1, account = ACCOUNT_A, cardId = 42)
        val second = fixtureJob(index = 2, account = ACCOUNT_B, cardId = 43)
        store.add(first)
        store.add(second)

        assertEquals(listOf(first), store.removeForAccount(ACCOUNT_A))
        assertEquals(listOf(second), store.list())
    }

    @Test
    fun `encrypted queue read and decryption failures preserve recoverable jobs`() {
        listOf("read", "read-type", "decrypt").forEach { failureMode ->
            val storage = FakeDurableUploadEncryptedStorage()
            val cipher = FakeDurableUploadCipher()
            val recoverable = fixtureJob(index = 1, account = ACCOUNT_A, cardId = 42)
            AndroidDurableMultipartUploadStore(storage, cipher).add(recoverable)
            val encryptedBeforeFailure = storage.value
            if (failureMode == "read") storage.readFailure = IOException("synthetic read failure")
            if (failureMode == "read-type") storage.readFailure = ClassCastException("synthetic stored type")
            if (failureMode == "decrypt") cipher.decryptFailure = IOException("synthetic decrypt failure")

            val restarted = AndroidDurableMultipartUploadStore(storage, cipher)
            val failure = assertFailsWith<AndroidDurableMultipartUploadRecoveryException> { restarted.list() }
            assertEquals(
                if (failureMode == "read") {
                    DurableUploadQueueRecoveryDisposition.Retry
                } else {
                    DurableUploadQueueRecoveryDisposition.Quarantine
                },
                failure.disposition,
            )
            assertFailsWith<AndroidDurableMultipartUploadRecoveryException> {
                restarted.add(fixtureJob(index = 2, account = ACCOUNT_A, cardId = 43))
            }
            assertEquals(encryptedBeforeFailure, storage.value)

            storage.readFailure = null
            cipher.decryptFailure = null
            assertEquals(listOf(recoverable), AndroidDurableMultipartUploadStore(storage, cipher).list())
        }
    }

    @Test
    fun `corrupt encrypted queue is never replaced by a later write`() {
        val storage = FakeDurableUploadEncryptedStorage(value = "not-json")
        val cipher = FakeDurableUploadCipher()
        val restarted = AndroidDurableMultipartUploadStore(storage, cipher)

        assertFailsWith<AndroidDurableMultipartUploadRecoveryException> { restarted.list() }
        assertFailsWith<AndroidDurableMultipartUploadRecoveryException> {
            restarted.add(fixtureJob(index = 1, account = ACCOUNT_A, cardId = 42))
        }

        assertEquals("not-json", storage.value)
    }

    @Test
    fun `one malformed row blocks queue rewrites without dropping valid rows`() {
        val storage = FakeDurableUploadEncryptedStorage()
        val cipher = FakeDurableUploadCipher()
        val first = fixtureJob(index = 1, account = ACCOUNT_A, cardId = 42)
        val second = fixtureJob(index = 2, account = ACCOUNT_A, cardId = 43)
        AndroidDurableMultipartUploadStore(storage, cipher).apply {
            add(first)
            add(second)
        }
        val validSnapshot = requireNotNull(storage.value)
        val malformed = JSONArray(validSnapshot).also { array ->
            array.getJSONObject(1).remove("relativePath")
        }.toString()
        storage.value = malformed
        val restarted = AndroidDurableMultipartUploadStore(storage, cipher)

        assertFailsWith<AndroidDurableMultipartUploadRecoveryException> { restarted.list() }
        assertFailsWith<AndroidDurableMultipartUploadRecoveryException> { restarted.remove(first.id) }
        assertFailsWith<AndroidDurableMultipartUploadRecoveryException> {
            restarted.transition(
                first.id,
                DurableUploadState.Queued,
                DurableUploadState.Uploading,
                null,
            )
        }
        assertEquals(malformed, storage.value)

        storage.value = validSnapshot
        assertEquals(listOf(first, second), AndroidDurableMultipartUploadStore(storage, cipher).list())
    }

    @Test
    fun `failed queue write leaves the previous restart snapshot readable`() {
        val storage = FakeDurableUploadEncryptedStorage()
        val cipher = FakeDurableUploadCipher()
        val first = fixtureJob(index = 1, account = ACCOUNT_A, cardId = 42)
        val second = fixtureJob(index = 2, account = ACCOUNT_A, cardId = 43)
        AndroidDurableMultipartUploadStore(storage, cipher).add(first)
        val snapshotBeforeWrite = storage.value
        storage.failWrites = true

        assertFailsWith<IllegalStateException> {
            AndroidDurableMultipartUploadStore(storage, cipher).add(second)
        }

        assertEquals(snapshotBeforeWrite, storage.value)
        storage.failWrites = false
        assertEquals(listOf(first), AndroidDurableMultipartUploadStore(storage, cipher).list())
    }

    @Test
    fun `duplicate and oversized queue snapshots block rewrites`() {
        val storage = FakeDurableUploadEncryptedStorage()
        val cipher = FakeDurableUploadCipher()
        AndroidDurableMultipartUploadStore(storage, cipher).add(
            fixtureJob(index = 1, account = ACCOUNT_A, cardId = 42),
        )
        val storedRow = JSONArray(requireNotNull(storage.value)).getJSONObject(0)
        val invalidSnapshots = listOf(
            JSONArray().put(storedRow).put(storedRow).toString(),
            JSONArray().also { array ->
                repeat(AndroidDurableMultipartUploadStore.MAX_STORED_UPLOADS + 1) {
                    array.put(storedRow)
                }
            }.toString(),
        )

        invalidSnapshots.forEach { invalidSnapshot ->
            storage.value = invalidSnapshot
            val restarted = AndroidDurableMultipartUploadStore(storage, cipher)

            assertFailsWith<AndroidDurableMultipartUploadRecoveryException> { restarted.list() }
            assertFailsWith<AndroidDurableMultipartUploadRecoveryException> {
                restarted.add(fixtureJob(index = 2, account = ACCOUNT_A, cardId = 43))
            }
            assertEquals(invalidSnapshot, storage.value)
        }
    }

    @Test
    fun `deck attachment resource binds board stack card and request path`() {
        val scope = DurableUploadScope("deck-attachment", "42")
        val resource = resolveDurableUploadResource(scope, fixtureRequest(cardId = 42))

        assertEquals("deck-attachment", resource.feature)
        assertEquals("7", resource.boardId)
        assertEquals("11", resource.stackId)
        assertEquals("42", resource.itemId)

        assertFailsWith<IllegalArgumentException> {
            resolveDurableUploadResource(scope, fixtureRequest(cardId = 43))
        }
        assertFailsWith<IllegalArgumentException> {
            resolveDurableUploadResource(
                scope,
                fixtureRequest(cardId = 42).copy(
                    relativePath = "/index.php/apps/deck/api/v1.1/boards/7/cards/42/attachments",
                ),
            )
        }
    }

    @Test
    fun `queue rejects duplicates and bounds account and card ownership`() {
        val fourForCard = (1..4).map { index ->
            fixtureJob(index = index, account = ACCOUNT_A, cardId = 42)
        }
        assertFailsWith<IllegalArgumentException> {
            requireCanAddDurableUpload(
                fourForCard,
                fixtureJob(index = 5, account = ACCOUNT_A, cardId = 42),
            )
        }

        requireCanAddDurableUpload(
            fourForCard,
            fixtureJob(index = 5, account = ACCOUNT_A, cardId = 43),
        )
        requireCanAddDurableUpload(
            fourForCard,
            fixtureJob(index = 5, account = ACCOUNT_B, cardId = 42),
        )

        val duplicateSelection = fixtureJob(index = 20, account = ACCOUNT_B, cardId = 99).copy(
            request = fixtureRequest(cardId = 99, selectionId = fourForCard.first().request.file.selectionId),
        )
        assertFailsWith<IllegalArgumentException> {
            requireCanAddDurableUpload(fourForCard, duplicateSelection)
        }

        val twelveForAccount = (1..12).map { index ->
            fixtureJob(index = index, account = ACCOUNT_A, cardId = index.toLong())
        }
        assertFailsWith<IllegalArgumentException> {
            requireCanAddDurableUpload(
                twelveForAccount,
                fixtureJob(index = 13, account = ACCOUNT_A, cardId = 13),
            )
        }
    }

    @Test
    fun `queue pruning retains active work and only the newest terminal history`() {
        val active = listOf(
            fixtureJob(index = 1, account = ACCOUNT_A, cardId = 1),
            fixtureJob(index = 2, account = ACCOUNT_B, cardId = 2),
        )
        val terminal = (1..70).map { index ->
            fixtureJob(
                index = index + 100,
                account = if (index % 2 == 0) ACCOUNT_A else ACCOUNT_B,
                cardId = (index + 100).toLong(),
                state = DurableUploadState.Completed,
                updatedAt = index.toLong(),
            )
        }

        val pruned = pruneDurableUploadJobs(active + terminal)

        assertEquals(AndroidDurableMultipartUploadStore.MAX_STORED_UPLOADS, pruned.size)
        assertTrue(pruned.containsAll(active))
        assertFalse(pruned.any { it.state == DurableUploadState.Completed && it.updatedAtEpochMillis <= 8L })
        assertTrue(pruned.any { it.state == DurableUploadState.Completed && it.updatedAtEpochMillis == 70L })
    }

    @Test
    fun `only queued work can start and recovered in flight work becomes unknown`() {
        assertTrue(
            isAllowedDurableUploadTransition(
                DurableUploadState.Queued,
                DurableUploadState.Uploading,
            ),
        )
        assertTrue(
            isAllowedDurableUploadTransition(
                DurableUploadState.Uploading,
                DurableUploadState.OutcomeUnknown,
            ),
        )
        assertFalse(
            isAllowedDurableUploadTransition(
                DurableUploadState.OutcomeUnknown,
                DurableUploadState.Uploading,
            ),
        )
        assertFalse(
            isAllowedDurableUploadTransition(
                DurableUploadState.Failed,
                DurableUploadState.Uploading,
            ),
        )
        assertEquals(
            DurableUploadState.OutcomeUnknown,
            DurableUploadState.Uploading.afterProcessRecovery(),
        )
        assertEquals(
            DurableUploadState.Queued,
            DurableUploadState.Queued.afterProcessRecovery(),
        )
    }

    @Test
    fun `only definite client rejection avoids an unknown outcome`() {
        assertEquals(DurableUploadState.Completed, durableUploadStateForHttpResponse(201))
        assertEquals(DurableUploadState.Failed, durableUploadStateForHttpResponse(400))
        assertEquals(DurableUploadState.Failed, durableUploadStateForHttpResponse(409))
        assertEquals(DurableUploadState.OutcomeUnknown, durableUploadStateForHttpResponse(302))
        assertEquals(DurableUploadState.OutcomeUnknown, durableUploadStateForHttpResponse(408))
        assertEquals(DurableUploadState.OutcomeUnknown, durableUploadStateForHttpResponse(425))
        assertEquals(DurableUploadState.OutcomeUnknown, durableUploadStateForHttpResponse(429))
        assertEquals(DurableUploadState.OutcomeUnknown, durableUploadStateForHttpResponse(500))
    }

    @Test
    fun `removed account terminally fails and releases its queued upload exactly once`() {
        val events = mutableListOf<String>()

        val result = failQueuedDurableUploadForUnavailableAccount(
            transitionToFailed = { events += "fail" },
            releaseSelection = {
                events += "release"
                true
            },
            recordFailure = { events += "diagnose" },
            failureResult = "worker-failure",
            retryResult = "worker-retry",
        )

        assertEquals("worker-failure", result)
        assertEquals(listOf("fail", "release", "diagnose"), events)
    }

    @Test
    fun `account recovery uses replacement only for deferred worker backoff`() {
        assertEquals(ExistingWorkPolicy.REPLACE, DURABLE_UPLOAD_ACCOUNT_RECOVERY_WORK_POLICY)
    }

    @Test
    fun `account recovery never replaces a worker after it starts its upload`() = runBlocking {
        val queued = fixtureJob(index = 1, account = ACCOUNT_A, cardId = 42)
        var current = queued
        val claimEntered = CompletableDeferred<Unit>()
        val allowClaim = CompletableDeferred<Unit>()
        var replacementScheduled = false

        val claim = async {
            claimQueuedDurableUploadForExecution(queued.id) {
                claimEntered.complete(Unit)
                allowClaim.await()
                current = queued.copy(state = DurableUploadState.Uploading)
                current
            }
        }
        claimEntered.await()
        val recovery = async {
            replaceDeferredDurableUploadWork(
                expected = queued,
                load = { current },
                replace = { replacementScheduled = true },
            )
        }
        yield()

        assertFalse(recovery.isCompleted)
        allowClaim.complete(Unit)
        assertEquals(DurableUploadState.Uploading, claim.await()?.state)
        assertFalse(recovery.await())
        assertFalse(replacementScheduled)
    }

    @Test
    fun `slow account recovery does not block an unrelated upload claim`() = runBlocking {
        val recovering = fixtureJob(index = 1, account = ACCOUNT_A, cardId = 42)
        val unrelated = fixtureJob(index = 2, account = ACCOUNT_B, cardId = 43)
        val coordinator = AndroidDurableUploadStartCoordinator()
        val replacementEntered = CompletableDeferred<Unit>()
        val releaseReplacement = CompletableDeferred<Unit>()
        val recovery = async {
            replaceDeferredDurableUploadWork(
                expected = recovering,
                load = { recovering },
                replace = {
                    replacementEntered.complete(Unit)
                    releaseReplacement.await()
                },
                coordinator = coordinator,
            )
        }
        replacementEntered.await()

        val claimed = claimQueuedDurableUploadForExecution(
            jobId = unrelated.id,
            coordinator = coordinator,
        ) {
            unrelated.copy(state = DurableUploadState.Uploading)
        }

        assertEquals(DurableUploadState.Uploading, claimed?.state)
        assertFalse(recovery.isCompleted)
        releaseReplacement.complete(Unit)
        assertTrue(recovery.await())
    }

    @Test
    fun `account activation resumes only its queued uploads`() {
        val queuedForA = fixtureJob(index = 1, account = ACCOUNT_A, cardId = 42)
        val queuedForB = fixtureJob(index = 2, account = ACCOUNT_B, cardId = 43)
        val completedForA = fixtureJob(
            index = 3,
            account = ACCOUNT_A,
            cardId = 44,
            state = DurableUploadState.Completed,
        )

        assertEquals(
            listOf(queuedForA),
            queuedDurableUploadsForAccount(listOf(queuedForA, queuedForB, completedForA), ACCOUNT_A),
        )
    }

    @Test
    fun `ambiguous scheduling keeps the durable job queued across restart`() = runBlocking {
        val job = fixtureJob(index = 1, account = ACCOUNT_A, cardId = 42)
        val persisted = mutableListOf<AndroidDurableMultipartUploadJob>()
        val acceptedWork = mutableSetOf<String>()
        var recoveryRequests = 0

        val result = persistAndScheduleDurableUpload(
            job = job,
            persist = persisted::add,
            schedule = { queued ->
                acceptedWork += queued.id
                throw IOException("The scheduler completion signal was lost")
            },
            requestRecovery = { recoveryRequests += 1 },
        )

        assertIs<DurableUploadEnqueueResult.Queued>(result)
        assertEquals(listOf(job), persisted)
        assertEquals(setOf(job.id), acceptedWork)
        assertEquals(1, recoveryRequests)

        val workRecoveredAfterRestart = persisted
            .filter { queued -> queued.state == DurableUploadState.Queued }
            .map(AndroidDurableMultipartUploadJob::id)
        assertEquals(listOf(job.id), workRecoveredAfterRestart)
    }

    @Test
    fun `startup reconciliation skips queued uploads already owned by WorkManager`() = runBlocking {
        val owned = fixtureJob(index = 1, account = ACCOUNT_A, cardId = 42)
        val missing = fixtureJob(index = 2, account = ACCOUNT_B, cardId = 43)
        val attempted = mutableListOf<String>()

        val allScheduled = reconcileQueuedDurableUploads(
            jobs = listOf(owned, missing),
            schedulerOwns = { job -> job == owned },
            cleanupCapability = { error("Queued uploads must not enter local cleanup.") },
            schedule = { job -> attempted += job.id },
        )

        assertTrue(allScheduled)
        assertEquals(listOf(missing.id), attempted)
    }

    @Test
    fun `a recovery request wakes the idle scheduling monitor without polling`() = runBlocking {
        val recoverySignal = AndroidDurableUploadSchedulingRecoverySignal()
        var recoveryRuns = 0
        recoverySignal.request()

        assertFailsWith<CancellationException> {
            monitorQueuedDurableUploadScheduling(
                recover = {
                    recoveryRuns += 1
                    if (recoveryRuns == 2) throw CancellationException("Lifecycle stopped")
                },
                wait = { error("an immediate wake must not wait") },
                recoverySignal = recoverySignal,
            )
        }

        assertEquals(2, recoveryRuns)
    }

    @Test
    fun `coalesced immediate recovery preempts worker ownership and follow up waits`() = runBlocking {
        val recoverySignal = AndroidDurableUploadSchedulingRecoverySignal()
        val jobId = "job-1"
        val workId = UUID.randomUUID()
        val expectedCancellation = CancellationException("recovery owner stopped")
        var recoveryRuns = 0
        var ownershipWaits = 0
        var delayRuns = 0
        recoverySignal.request()
        recoverySignal.requestAfterWorkStopsRunning(jobId, workId)

        val actual = assertFailsWith<CancellationException> {
            monitorQueuedDurableUploadScheduling(
                recover = {
                    recoveryRuns += 1
                    if (recoveryRuns == 2) throw expectedCancellation
                },
                awaitWorkStopsRunning = { requestedWorkId ->
                    assertEquals(workId, requestedWorkId)
                    ownershipWaits += 1
                },
                wait = { delayMillis ->
                    assertEquals(ANDROID_DURABLE_UPLOAD_SCHEDULING_FOLLOW_UP_DELAY_MILLIS, delayMillis)
                    delayRuns += 1
                },
                recoverySignal = recoverySignal,
            )
        }

        assertTrue(actual === expectedCancellation)
        assertEquals(2, recoveryRuns)
        assertEquals(0, ownershipWaits)
        assertEquals(0, delayRuns)
    }

    @Test
    fun `recovery signal conflates immediate requests and replacement workers per durable job`() = runBlocking {
        val recoverySignal = AndroidDurableUploadSchedulingRecoverySignal()
        val replacedWorkId = UUID.randomUUID()
        val replacementWorkId = UUID.randomUUID()
        val otherWorkId = UUID.randomUUID()

        recoverySignal.request()
        recoverySignal.request()
        recoverySignal.requestAfterWorkStopsRunning("job-1", replacedWorkId)
        repeat(100) { recoverySignal.requestAfterWorkStopsRunning("job-1", replacementWorkId) }
        recoverySignal.requestAfterWorkStopsRunning("job-2", otherWorkId)

        assertEquals(
            AndroidDurableUploadSchedulingRecoveryBatch(
                immediate = true,
                workIdsToAwait = mapOf("job-1" to replacementWorkId, "job-2" to otherWorkId),
            ),
            recoverySignal.await(),
        )
    }

    @Test
    fun `worker readiness retries a transient state query for the same work id`() = runBlocking {
        val workId = UUID.randomUUID()
        val requestedWorkIds = mutableListOf<UUID>()
        val waits = mutableListOf<Long>()

        awaitDurableUploadWorkToStopRunning(
            workId = workId,
            retryDelayMillis = 25L,
            awaitWorkStopsRunning = { requestedWorkId ->
                requestedWorkIds += requestedWorkId
                if (requestedWorkIds.size == 1) {
                    throw IOException("Synthetic WorkManager database failure")
                }
            },
            wait = waits::add,
        )

        assertEquals(listOf(workId, workId), requestedWorkIds)
        assertEquals(listOf(25L), waits)
    }

    @Test
    fun `worker readiness cancellation propagates without retrying`() = runBlocking {
        val workId = UUID.randomUUID()
        val expected = CancellationException("Recovery owner stopped")
        val waits = mutableListOf<Long>()

        val actual = assertFailsWith<CancellationException> {
            awaitDurableUploadWorkToStopRunning(
                workId = workId,
                awaitWorkStopsRunning = { requestedWorkId ->
                    assertEquals(workId, requestedWorkId)
                    throw expected
                },
                wait = waits::add,
            )
        }

        assertTrue(actual === expected)
        assertTrue(waits.isEmpty())
    }

    @Test
    fun `queued status snapshot requests one scheduling recovery`() {
        val first = fixtureJob(index = 1, account = ACCOUNT_A, cardId = 42)
        val second = fixtureJob(index = 2, account = ACCOUNT_A, cardId = 43)
        var recoveryRequests = 0

        requestDurableUploadSchedulingRecoveryForQueuedStatuses(
            jobs = listOf(first, second),
            requestRecovery = { recoveryRequests += 1 },
        )

        assertEquals(1, recoveryRequests)
    }

    @Test
    fun `terminal status snapshot does not request scheduling recovery`() {
        val completed = fixtureJob(
            index = 1,
            account = ACCOUNT_A,
            cardId = 42,
            state = DurableUploadState.Completed,
        )
        val failed = fixtureJob(
            index = 2,
            account = ACCOUNT_A,
            cardId = 43,
            state = DurableUploadState.Failed,
        )
        var recoveryRequests = 0

        requestDurableUploadSchedulingRecoveryForQueuedStatuses(
            jobs = listOf(completed, failed),
            requestRecovery = { recoveryRequests += 1 },
        )

        assertEquals(0, recoveryRequests)
    }

    @Test
    fun `startup scheduling retries until a transient journal read failure clears`() = runBlocking {
        var attempts = 0
        val waits = mutableListOf<Long>()
        var diagnostics = 0

        keepRetryingQueuedDurableUploadScheduling(
            followUpDelayMillis = 100L,
            reconcile = {
                attempts += 1
                when (attempts) {
                    1, 2 -> throw AndroidDurableMultipartUploadRecoveryException(
                        IOException("Synthetic unreadable journal"),
                    )
                    else -> true
                }
            },
            wait = waits::add,
            recordRecoveryFailure = { diagnostics += 1 },
        )

        assertEquals(3, attempts)
        assertEquals(listOf(100L, 100L), waits)
        assertEquals(1, diagnostics)
    }

    @Test
    fun `startup scheduling retries when uploader construction is temporarily unavailable`() = runBlocking {
        var constructions = 0
        val waits = mutableListOf<Long>()
        var diagnostics = 0

        keepRetryingQueuedDurableUploadScheduling(
            followUpDelayMillis = 100L,
            reconcile = {
                constructAndReconcileQueuedDurableUploads {
                    constructions += 1
                    when (constructions) {
                        1 -> throw IOException("Synthetic keystore initialization failure")
                        else -> suspend { true }
                    }
                }
            },
            wait = waits::add,
            recordRecoveryFailure = { diagnostics += 1 },
        )

        assertEquals(2, constructions)
        assertEquals(listOf(100L), waits)
        assertEquals(1, diagnostics)
    }

    @Test
    fun `cancellation after persistence propagates without discarding restart state`() {
        val job = fixtureJob(index = 1, account = ACCOUNT_A, cardId = 42)
        val persisted = mutableListOf<AndroidDurableMultipartUploadJob>()
        var recoveryRequests = 0

        assertFailsWith<CancellationException> {
            runBlocking {
                persistAndScheduleDurableUpload(
                    job = job,
                    persist = persisted::add,
                    schedule = { throw CancellationException("Owner stopped") },
                    requestRecovery = { recoveryRequests += 1 },
                )
            }
        }

        assertEquals(listOf(job), persisted)
        assertEquals(1, recoveryRequests)
    }

    @Test
    fun `persistence failure never reaches the scheduler`() {
        val job = fixtureJob(index = 1, account = ACCOUNT_A, cardId = 42)
        var scheduled = false

        assertFailsWith<IOException> {
            runBlocking {
                persistAndScheduleDurableUpload(
                    job = job,
                    persist = { throw IOException("Queue storage is unavailable") },
                    schedule = { scheduled = true },
                )
            }
        }

        assertFalse(scheduled)
    }

    @Test
    fun `background upload resolves the queued account instead of the active account`() {
        val queuedSession = fixtureSession("alice")
        val activeSession = fixtureSession("bob")
        val loadedAccountIds = mutableListOf<String>()

        val resolved = resolveDurableUploadSession(
            expectedAccountId = NextcloudDocumentIds.accountKey(queuedSession),
            registry = DurableUploadAccountRegistry.Available(
                listOf(activeSession.accountRecord(), queuedSession.accountRecord()),
            ),
            loadSession = { accountId ->
                loadedAccountIds += accountId.storageKey
                when (accountId) {
                    queuedSession.accountId -> queuedSession
                    activeSession.accountId -> activeSession
                    else -> null
                }
            },
        )

        assertEquals(DurableUploadAccountResolution.Available(queuedSession), resolved)
        assertEquals(listOf(queuedSession.accountId.storageKey), loadedAccountIds)
    }

    @Test
    fun `background upload recovers missing account metadata before rejecting the account`() {
        val queuedSession = fixtureSession("alice")
        var registry: DurableUploadAccountRegistry = DurableUploadAccountRegistry.Unavailable
        val events = mutableListOf<String>()

        val resolved = resolveDurableUploadSessionWithRegistryRecovery(
            expectedAccountId = NextcloudDocumentIds.accountKey(queuedSession),
            readRegistry = {
                events += "registry"
                registry
            },
            recoverRegistry = {
                events += "recover"
                registry = DurableUploadAccountRegistry.Available(listOf(queuedSession.accountRecord()))
                null
            },
            loadSession = {
                events += "load:${it.storageKey}"
                queuedSession
            },
        )

        assertEquals(DurableUploadAccountResolution.Available(queuedSession), resolved)
        assertEquals(
            listOf("registry", "recover", "registry", "load:${queuedSession.accountId.storageKey}"),
            events,
        )
    }

    @Test
    fun `background upload defers when the credential-free registry remains unreadable`() {
        val queuedSession = fixtureSession("alice")

        val resolved = resolveDurableUploadSessionWithRegistryRecovery(
            expectedAccountId = NextcloudDocumentIds.accountKey(queuedSession),
            readRegistry = { DurableUploadAccountRegistry.Unavailable },
            recoverRegistry = { null },
            loadSession = { error("an unreadable registry must not select a credential") },
        )

        assertEquals(DurableUploadAccountResolution.RegistryUnavailable, resolved)
    }

    @Test
    fun `background upload retains a matching recovered session when registry repair cannot persist`() {
        val queuedSession = fixtureSession("alice")
        var accountReads = 0

        val resolved = resolveDurableUploadSessionWithRegistryRecovery(
            expectedAccountId = NextcloudDocumentIds.accountKey(queuedSession),
            readRegistry = {
                accountReads += 1
                DurableUploadAccountRegistry.Unavailable
            },
            recoverRegistry = { queuedSession },
            loadSession = { error("the uncommitted registry must not hide the recovered session") },
        )

        assertEquals(DurableUploadAccountResolution.Available(queuedSession), resolved)
        assertEquals(1, accountReads)
    }

    @Test
    fun `background upload skips registry recovery when account metadata is healthy`() {
        val queuedSession = fixtureSession("alice")
        var registryRecoveryAttempted = false

        val resolved = resolveDurableUploadSessionWithRegistryRecovery(
            expectedAccountId = NextcloudDocumentIds.accountKey(queuedSession),
            readRegistry = {
                DurableUploadAccountRegistry.Available(listOf(queuedSession.accountRecord()))
            },
            recoverRegistry = {
                registryRecoveryAttempted = true
                null
            },
            loadSession = { queuedSession },
        )

        assertEquals(DurableUploadAccountResolution.Available(queuedSession), resolved)
        assertFalse(registryRecoveryAttempted)
    }

    @Test
    fun `startup reconciliation schedules every queued upload across accounts`() = runBlocking {
        val first = fixtureJob(index = 1, account = ACCOUNT_A, cardId = 42)
        val second = fixtureJob(index = 2, account = ACCOUNT_B, cardId = 43)
        val completed = fixtureJob(
            index = 3,
            account = ACCOUNT_A,
            cardId = 44,
            state = DurableUploadState.Completed,
        )
        val attempted = mutableListOf<String>()

        val allScheduled = reconcileQueuedDurableUploads(
            jobs = listOf(first, completed, second),
            cleanupCapability = { error("Completed history must not enter cleanup.") },
            schedule = { job ->
                attempted += job.id
                if (job == first) throw IOException("Synthetic scheduler rejection")
            },
        )

        assertEquals(listOf(first.id, second.id), attempted)
        assertFalse(allScheduled)
    }

    @Test
    fun `startup scheduling retries an observed asynchronous failure`() = runBlocking {
        var attempts = 0
        val waits = mutableListOf<Long>()

        val recovered = retryQueuedDurableUploadScheduling(
            retryDelaysMillis = listOf(10L, 20L),
            reconcile = {
                attempts += 1
                attempts >= 2
            },
            wait = { delayMillis -> waits += delayMillis },
        )

        assertTrue(recovered)
        assertEquals(2, attempts)
        assertEquals(listOf(10L), waits)
    }

    @Test
    fun `exhausted startup scheduling is reported before the next recovery cycle`() {
        var attempts = 0
        var diagnostics = 0
        var recoveryCycles = 0
        val waits = mutableListOf<Long>()

        assertFailsWith<CancellationException> {
            runBlocking {
                keepRetryingQueuedDurableUploadScheduling(
                    retryDelaysMillis = listOf(10L),
                    followUpDelayMillis = 20L,
                    reconcile = {
                        attempts += 1
                        false
                    },
                    wait = { delayMillis ->
                        waits += delayMillis
                        if (delayMillis == 20L && ++recoveryCycles == 2) {
                            throw CancellationException("stop after two cycles")
                        }
                    },
                    recordRecoveryFailure = { diagnostics += 1 },
                )
            }
        }

        assertEquals(4, attempts)
        assertEquals(1, diagnostics)
        assertEquals(listOf(10L, 20L, 10L, 20L), waits)
    }

    @Test
    fun `successful startup reconciliation stops background polling`() = runBlocking {
        var attempts = 0

        keepRetryingQueuedDurableUploadScheduling(
            reconcile = {
                attempts += 1
                true
            },
            wait = { error("a successful reconciliation must not schedule another poll") },
        )

        assertEquals(1, attempts)
    }

    @Test
    fun `permanently unreadable queue is quarantined without background polling`() = runBlocking {
        var attempts = 0
        var diagnostics = 0

        keepRetryingQueuedDurableUploadScheduling(
            reconcile = {
                attempts += 1
                throw AndroidDurableMultipartUploadRecoveryException(
                    IOException("synthetic invalid ciphertext"),
                    DurableUploadQueueRecoveryDisposition.Quarantine,
                )
            },
            wait = { error("a quarantined queue must not schedule another poll") },
            recordRecoveryFailure = { diagnostics += 1 },
        )

        assertEquals(1, attempts)
        assertEquals(1, diagnostics)
    }

    @Test
    fun `startup recovery contains uploader construction failures`() = runBlocking {
        val failure = assertFailsWith<AndroidDurableMultipartUploadRecoveryException> {
            constructAndReconcileQueuedDurableUploads {
                throw IOException("synthetic keystore failure")
            }
        }
        val malformedPreference = assertFailsWith<AndroidDurableMultipartUploadRecoveryException> {
            constructAndReconcileQueuedDurableUploads {
                throw ClassCastException("synthetic non-string account registry")
            }
        }

        assertTrue(failure.cause is IOException)
        assertTrue(malformedPreference.cause is ClassCastException)
    }

    @Test
    fun `startup recovery contains an unreadable queue and records one bounded diagnostic`() = runBlocking {
        val events = mutableListOf<String>()

        runAndroidDurableUploadStartupRecovery(
            recover = {
                events += "recover"
                throw AndroidDurableMultipartUploadRecoveryException(IOException("sensitive storage detail"))
            },
            recordRecoveryFailure = { events += "diagnose" },
        )

        assertEquals(listOf("recover", "diagnose"), events)
    }

    @Test
    fun `startup recovery preserves cancellation`() {
        val events = mutableListOf<String>()

        assertFailsWith<CancellationException> {
            runBlocking {
                runAndroidDurableUploadStartupRecovery(
                    recover = { throw CancellationException("application stopped") },
                    recordRecoveryFailure = { events += "diagnose" },
                )
            }
        }

        assertTrue(events.isEmpty())
    }

    @Test
    fun `unsupported registry remains unavailable after credential recovery attempt`() {
        var recoveryAttempts = 0
        var credentialReads = 0

        val resolved = resolveDurableUploadSessionWithRegistryRecovery(
            expectedAccountId = NextcloudDocumentIds.accountKey(fixtureSession("alice")),
            readRegistry = { DurableUploadAccountRegistry.Unavailable },
            recoverRegistry = {
                recoveryAttempts += 1
                null
            },
            loadSession = {
                credentialReads += 1
                fixtureSession("alice")
            },
        )

        assertEquals(DurableUploadAccountResolution.RegistryUnavailable, resolved)
        assertEquals(1, recoveryAttempts)
        assertEquals(0, credentialReads)
    }

    @Test
    fun `background upload never substitutes another account on the same server path`() {
        val queuedSession = fixtureSession("alice")
        val otherSession = fixtureSession("bob")
        var credentialRead = false

        val missing = resolveDurableUploadSession(
            expectedAccountId = NextcloudDocumentIds.accountKey(queuedSession),
            registry = DurableUploadAccountRegistry.Available(listOf(otherSession.accountRecord())),
            loadSession = {
                credentialRead = true
                otherSession
            },
        )

        assertEquals(DurableUploadAccountResolution.AccountUnavailable, missing)
        assertFalse(credentialRead)
    }

    @Test
    fun `background upload rejects a credential that does not match its registry owner`() {
        val queuedSession = fixtureSession("alice")
        val otherSession = fixtureSession("bob")

        val resolved = resolveDurableUploadSession(
            expectedAccountId = NextcloudDocumentIds.accountKey(queuedSession),
            registry = DurableUploadAccountRegistry.Available(
                accounts = listOf(queuedSession.accountRecord(), otherSession.accountRecord()),
                activeAccountId = queuedSession.accountId,
            ),
            loadSession = { otherSession },
        )

        assertEquals(DurableUploadAccountResolution.CredentialUnavailable, resolved)
    }

    private fun fixtureJob(
        index: Int,
        account: String,
        cardId: Long,
        state: DurableUploadState = DurableUploadState.Queued,
        updatedAt: Long = index.toLong(),
    ): AndroidDurableMultipartUploadJob {
        val scope = DurableUploadScope("deck-attachment", cardId.toString())
        val request = fixtureRequest(cardId, selectionId = selectionId(index))
        return AndroidDurableMultipartUploadJob(
            id = "upload-${index.toString().padStart(16, '0')}",
            accountId = account,
            scope = scope,
            resource = resolveDurableUploadResource(scope, request),
            request = request,
            state = state,
            message = null,
            updatedAtEpochMillis = updatedAt,
        )
    }

    private fun fixtureRequest(
        cardId: Long,
        selectionId: String = selectionId(cardId.toInt()),
    ): NextcloudMultipartUploadRequest = NextcloudMultipartUploadRequest(
        method = NextcloudApiMethod.POST,
        relativePath = "/index.php/apps/deck/api/v1.1/boards/7/stacks/11/cards/$cardId/attachments",
        file = localUploadFile(
            selectionId = selectionId,
            displayName = "fixture-$cardId.txt",
            mimeType = "text/plain",
            sizeBytes = 16L,
        ),
        maximumFileBytes = 1024L,
    )

    private fun selectionId(index: Int): String = "selection-${index.toString().padStart(16, '0')}"

    private fun fixtureSession(loginName: String): NextcloudSession = NextcloudSession(
        serverUrl = "https://cloud.example.test/nextcloud",
        loginName = loginName,
        appPassword = "fixture-password",
    )

    private companion object {
        const val ACCOUNT_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val ACCOUNT_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}

private class FakeDurableUploadEncryptedStorage(
    var value: String? = null,
) : AndroidDurableMultipartUploadEncryptedStorage {
    var readFailure: Exception? = null
    var failWrites: Boolean = false

    override fun read(): String? {
        readFailure?.let { throw it }
        return value
    }

    override fun write(value: String): Boolean {
        if (failWrites) return false
        this.value = value
        return true
    }
}

private class FakeDurableUploadCipher : AndroidDurableMultipartUploadCipher {
    var decryptFailure: IOException? = null

    override fun encrypt(value: String): String = value

    override fun decrypt(value: String): String {
        decryptFailure?.let { throw it }
        return value
    }
}
