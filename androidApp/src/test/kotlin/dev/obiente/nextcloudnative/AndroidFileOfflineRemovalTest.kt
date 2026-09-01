package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.FileOfflineDescriptor
import dev.obiente.nextcloudnative.app.FileOfflineIntent
import dev.obiente.nextcloudnative.app.FileOfflineJobResult
import dev.obiente.nextcloudnative.app.FileOfflineJobStatus
import dev.obiente.nextcloudnative.app.FileOfflineKey
import dev.obiente.nextcloudnative.app.FileOfflineQueueState
import dev.obiente.nextcloudnative.app.FileOfflineRequest
import dev.obiente.nextcloudnative.app.markFileOfflineJobRunning
import dev.obiente.nextcloudnative.app.planFileOfflineRequest
import dev.obiente.nextcloudnative.app.recordFileOfflineJobResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidFileOfflineRemovalTest {
    @Test
    fun staleRemovalCannotDeleteGenerationAfterUserPinsFileAgain() {
        val available = availableState()
        val removal = planFileOfflineRequest(
            available,
            FileOfflineRequest.Unpin(KEY, observedLocalRevision = LOCAL_REVISION),
            nowEpochMillis = 30L,
        )
        val running = markFileOfflineJobRunning(removal, removal.jobs.single().id, nowEpochMillis = 31L)
        val startedJob = running.jobs.single()
        val repinned = planFileOfflineRequest(
            running,
            pin(observedLocalRevision = LOCAL_REVISION),
            nowEpochMillis = 32L,
        )
        var generationDeleted = false

        val commit = commitAndroidFileOfflineRemoval(
            current = AndroidFileOfflinePersistedState(queue = repinned),
            startedJob = startedJob,
            nowEpochMillis = 33L,
            removeLocalGeneration = {
                generationDeleted = true
                true
            },
        )

        assertNull(commit)
        assertFalse(generationDeleted)
        assertEquals(FileOfflineIntent.Pinned, repinned.records.single().intent)
        assertEquals(LOCAL_REVISION, repinned.records.single().localRevision)
        assertTrue(repinned.jobs.isEmpty())
    }

    @Test
    fun currentRemovalDeletesGenerationAndCommitsQueueStateTogether() {
        val available = availableState()
        val removal = planFileOfflineRequest(
            available,
            FileOfflineRequest.Unpin(KEY, observedLocalRevision = LOCAL_REVISION),
            nowEpochMillis = 30L,
        )
        val running = markFileOfflineJobRunning(removal, removal.jobs.single().id, nowEpochMillis = 31L)
        var generationDeleted = false

        val commit = requireNotNull(
            commitAndroidFileOfflineRemoval(
                current = AndroidFileOfflinePersistedState(queue = running),
                startedJob = running.jobs.single(),
                nowEpochMillis = 32L,
                removeLocalGeneration = {
                    generationDeleted = true
                    true
                },
            ),
        )

        assertTrue(generationDeleted)
        assertTrue(commit.completedRemoval)
        assertEquals(AndroidOfflineExecutionOutcome.Complete, commit.outcome)
        assertTrue(commit.state.queue.records.isEmpty())
        assertTrue(commit.state.queue.jobs.isEmpty())
    }

    @Test
    fun failedDeletionKeepsGenerationAndLeavesRemovalRetryable() {
        val available = availableState()
        val removal = planFileOfflineRequest(
            available,
            FileOfflineRequest.Unpin(KEY, observedLocalRevision = LOCAL_REVISION),
            nowEpochMillis = 30L,
        )
        val running = markFileOfflineJobRunning(removal, removal.jobs.single().id, nowEpochMillis = 31L)

        val commit = requireNotNull(
            commitAndroidFileOfflineRemoval(
                current = AndroidFileOfflinePersistedState(queue = running),
                startedJob = running.jobs.single(),
                nowEpochMillis = 32L,
                removeLocalGeneration = { false },
            ),
        )

        assertFalse(commit.completedRemoval)
        assertEquals(AndroidOfflineExecutionOutcome.Retry, commit.outcome)
        assertEquals(FileOfflineIntent.OnlineOnly, commit.state.queue.records.single().intent)
        assertEquals(LOCAL_REVISION, commit.state.queue.records.single().localRevision)
        assertEquals(FileOfflineJobStatus.WaitingForNetwork, commit.state.queue.jobs.single().status)
        assertEquals(LOCAL_REVISION, commit.state.queue.jobs.single().expectedLocalRevision)
    }

    private fun availableState(): FileOfflineQueueState {
        val queued = planFileOfflineRequest(FileOfflineQueueState(), pin(), nowEpochMillis = 10L)
        return recordFileOfflineJobResult(
            queued,
            queued.jobs.single().id,
            FileOfflineJobResult.Downloaded(LOCAL_REVISION, REMOTE_ETAG),
            nowEpochMillis = 20L,
        )
    }

    private fun pin(observedLocalRevision: String? = null) = FileOfflineRequest.Pin(
        descriptor = FileOfflineDescriptor(
            key = KEY,
            displayName = "vault.md",
            remoteEtag = REMOTE_ETAG,
            size = 42L,
            mimeType = "text/markdown",
        ),
        observedLocalRevision = observedLocalRevision,
    )

    private companion object {
        val KEY = FileOfflineKey("account-a", "Notes/vault.md")
        const val LOCAL_REVISION = "sha256:local-generation"
        const val REMOTE_ETAG = "\"remote-1\""
    }
}
