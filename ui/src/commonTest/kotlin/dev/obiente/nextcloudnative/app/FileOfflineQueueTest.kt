package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class FileOfflineQueueTest {
    @Test
    fun pinQueuesOnlyAGuardedDownloadAndCoalescesRepeatedIntent() {
        val request = pin(etag = "\"remote-1\"")
        val first = planFileOfflineRequest(FileOfflineQueueState(), request, nowEpochMillis = 10)
        val job = first.jobs.single()

        assertEquals(FileOfflineJobOperation.Download, job.operation)
        assertEquals("\"remote-1\"", job.expectedRemoteEtag)
        assertNull(job.expectedLocalRevision)
        assertEquals(FileOfflineAvailability.Queued, first.availability(request.key))

        val repeated = planFileOfflineRequest(first, request, nowEpochMillis = 20)
        assertEquals(job, repeated.jobs.single())
        assertEquals(first.nextJobId, repeated.nextJobId)
    }

    @Test
    fun verifiedDownloadCreatesBaselineAndRemoteChangeQueuesRefresh() {
        val request = pin(etag = "\"remote-1\"")
        val queued = planFileOfflineRequest(FileOfflineQueueState(), request, 10)
        val available = recordFileOfflineJobResult(
            markFileOfflineJobRunning(queued, queued.jobs.single().id, nowEpochMillis = 11),
            queued.jobs.single().id,
            FileOfflineJobResult.Downloaded("sha256:local-1", "\"remote-1\""),
            nowEpochMillis = 30,
        )

        assertEquals(FileOfflineAvailability.Available, available.availability(request.key))
        assertEquals("sha256:local-1", available.record(request.key)?.localRevision)
        assertEquals(emptyList(), available.jobs)

        val refresh = planFileOfflineRequest(
            available,
            pin(etag = "\"remote-2\"", observedLocalRevision = "sha256:local-1"),
            nowEpochMillis = 40,
        )
        assertEquals("sha256:local-1", refresh.jobs.single().expectedLocalRevision)
        assertEquals("\"remote-2\"", refresh.jobs.single().expectedRemoteEtag)
    }

    @Test
    fun mismatchedDownloadGenerationCannotCommit() {
        val queued = planFileOfflineRequest(FileOfflineQueueState(), pin(), 10)
        assertFailsWith<IllegalArgumentException> {
            recordFileOfflineJobResult(
                queued,
                queued.jobs.single().id,
                FileOfflineJobResult.Downloaded("local", "\"different\""),
                20,
            )
        }
    }

    @Test
    fun simultaneousLocalAndRemoteChangeNeedsAttentionInsteadOfOverwriting() {
        val queued = planFileOfflineRequest(FileOfflineQueueState(), pin(etag = "\"r1\""), 10)
        val available = recordFileOfflineJobResult(
            queued,
            queued.jobs.single().id,
            FileOfflineJobResult.Downloaded("l1", "\"r1\""),
            20,
        )
        val conflicted = planFileOfflineRequest(
            available,
            pin(etag = "\"r2\"", observedLocalRevision = "l2"),
            30,
        )

        assertEquals(emptyList(), conflicted.jobs)
        assertEquals(FileOfflineAvailability.NeedsAttention, conflicted.availability(key))
        assertEquals(FileSyncDecisionReason.SimultaneousEdit, conflicted.record(key)?.attentionReason)
    }

    @Test
    fun unpinCancelsPendingDownloadWhenNoLocalGenerationExists() {
        val queued = planFileOfflineRequest(FileOfflineQueueState(), pin(), 10)
        val removed = planFileOfflineRequest(
            queued,
            FileOfflineRequest.Unpin(key, observedLocalRevision = null),
            20,
        )

        assertEquals(FileOfflineQueueState(nextJobId = queued.nextJobId), removed)
    }

    @Test
    fun unpinQueuesGenerationGuardedLocalRemoval() {
        val queued = planFileOfflineRequest(FileOfflineQueueState(), pin(), 10)
        val available = recordFileOfflineJobResult(
            queued,
            queued.jobs.single().id,
            FileOfflineJobResult.Downloaded("l1", "\"r1\""),
            20,
        )
        val removing = planFileOfflineRequest(
            available,
            FileOfflineRequest.Unpin(key, observedLocalRevision = "l1"),
            30,
        )
        val job = removing.jobs.single()

        assertEquals(FileOfflineJobOperation.RemoveLocal, job.operation)
        assertEquals("l1", job.expectedLocalRevision)
        assertEquals(FileOfflineAvailability.Removing, removing.availability(key))

        val removed = recordFileOfflineJobResult(removing, job.id, FileOfflineJobResult.LocalRemoved, 40)
        assertNull(removed.record(key))
        assertEquals(emptyList(), removed.jobs)
    }

    @Test
    fun retryStateAndAttemptsAreExplicitAndPersistable() {
        val queued = planFileOfflineRequest(FileOfflineQueueState(), pin(), 10)
        val jobId = queued.jobs.single().id
        val running = markFileOfflineJobRunning(queued, jobId, nowEpochMillis = 11)
        val waiting = recordFileOfflineJobResult(
            running,
            jobId,
            FileOfflineJobResult.RetryableFailure("Network unavailable"),
            20,
        )

        assertEquals(1, waiting.jobs.single().attemptCount)
        assertEquals(FileOfflineJobStatus.WaitingForNetwork, waiting.jobs.single().status)
        assertEquals(FileOfflineAvailability.WaitingForNetwork, waiting.availability(key))
        assertIs<FileOfflinePinRecord>(waiting.record(key))
    }

    @Test
    fun serverRetryDeadlineBlocksEarlyOfflineExecution() {
        val queued = planFileOfflineRequest(FileOfflineQueueState(), pin(), 10)
        val jobId = queued.jobs.single().id
        val running = markFileOfflineJobRunning(queued, jobId, nowEpochMillis = 11)
        val waiting = recordFileOfflineJobResult(
            running,
            jobId,
            FileOfflineJobResult.RetryableFailure(
                "Nextcloud asked this download to wait.",
                retryNotBeforeEpochMillis = 120_000L,
            ),
            nowEpochMillis = 20,
        )

        assertEquals(120_000L, waiting.jobs.single().retryNotBeforeEpochMillis)
        assertFailsWith<IllegalArgumentException> {
            markFileOfflineJobRunning(waiting, jobId, nowEpochMillis = 119_999L)
        }
        assertNull(
            markFileOfflineJobRunning(waiting, jobId, nowEpochMillis = 120_000L)
                .jobs.single().retryNotBeforeEpochMillis,
        )
    }

    @Test
    fun restartingAnInterruptedRunningJobIsIdempotent() {
        val queued = planFileOfflineRequest(FileOfflineQueueState(), pin(), 10)
        val jobId = queued.jobs.single().id
        val running = markFileOfflineJobRunning(queued, jobId, nowEpochMillis = 11)

        assertEquals(running, markFileOfflineJobRunning(running, jobId, nowEpochMillis = 12))
        assertEquals(1, running.jobs.single().attemptCount)
    }

    private val key = FileOfflineKey("account-a", "Notes/vault.md")

    private fun pin(
        etag: String = "\"r1\"",
        observedLocalRevision: String? = null,
    ) = FileOfflineRequest.Pin(
        descriptor = FileOfflineDescriptor(
            key = key,
            displayName = "vault.md",
            remoteEtag = etag,
            size = 42,
            mimeType = "text/markdown",
        ),
        observedLocalRevision = observedLocalRevision,
    )
}
