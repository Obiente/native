package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.FileOfflineDescriptor
import dev.obiente.nextcloudnative.app.FileOfflineJob
import dev.obiente.nextcloudnative.app.FileOfflineJobResult
import dev.obiente.nextcloudnative.app.FileOfflineJobStatus
import dev.obiente.nextcloudnative.app.FileOfflineKey
import dev.obiente.nextcloudnative.app.FileOfflinePinRecord
import dev.obiente.nextcloudnative.app.FileOfflineQueueState
import dev.obiente.nextcloudnative.app.FileOfflineRequest
import dev.obiente.nextcloudnative.app.markFileOfflineJobRunning
import dev.obiente.nextcloudnative.app.planFileOfflineRequest
import dev.obiente.nextcloudnative.app.recordFileOfflineJobResult
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidFileOfflineDownloadCompletionTest {
    @Test
    fun supersededDownloadKeepsGenerationOwnedByNewerRefresh() {
        val started = startedRefresh(SECOND_ETAG)
        val newerRefresh = planFileOfflineRequest(
            started.state,
            pin(THIRD_ETAG, OLD_REVISION),
            nowEpochMillis = 40L,
        )

        val commit = commitAndroidFileOfflineDownload(
            current = AndroidFileOfflinePersistedState(queue = newerRefresh),
            startedJob = started.job,
            startedRecord = started.record,
            downloadedLocalRevision = SUPERSEDED_REVISION,
            remoteEtag = SECOND_ETAG,
            nowEpochMillis = 50L,
        )

        assertFalse(commit.committed)
        assertEquals(newerRefresh, commit.state.queue)
        assertEquals(setOf(SUPERSEDED_REVISION), commit.removableLocalRevisions)
        assertEquals(OLD_REVISION, newerRefresh.records.single().localRevision)
        assertEquals(OLD_REVISION, newerRefresh.jobs.single().expectedLocalRevision)
    }

    @Test
    fun failedNewerRefreshStillRetainsPreviousGeneration() {
        val started = startedRefresh(SECOND_ETAG)
        val newerRefresh = planFileOfflineRequest(
            started.state,
            pin(THIRD_ETAG, OLD_REVISION),
            nowEpochMillis = 40L,
        )
        val newerRunning = markFileOfflineJobRunning(
            newerRefresh,
            newerRefresh.jobs.single().id,
            nowEpochMillis = 41L,
        )
        val newerFailed = recordFileOfflineJobResult(
            newerRunning,
            newerRunning.jobs.single().id,
            FileOfflineJobResult.RetryableFailure("Network unavailable"),
            nowEpochMillis = 42L,
        )

        val commit = commitAndroidFileOfflineDownload(
            current = AndroidFileOfflinePersistedState(queue = newerFailed),
            startedJob = started.job,
            startedRecord = started.record,
            downloadedLocalRevision = SUPERSEDED_REVISION,
            remoteEtag = SECOND_ETAG,
            nowEpochMillis = 50L,
        )

        assertFalse(commit.committed)
        assertEquals(setOf(SUPERSEDED_REVISION), commit.removableLocalRevisions)
        assertEquals(OLD_REVISION, commit.state.queue.records.single().localRevision)
        assertEquals(FileOfflineJobStatus.WaitingForNetwork, commit.state.queue.jobs.single().status)
        assertEquals(OLD_REVISION, commit.state.queue.jobs.single().expectedLocalRevision)
    }

    @Test
    fun currentCompletionCleansOnlyTheSupersededGeneration() {
        val started = startedRefresh(SECOND_ETAG)

        val commit = commitAndroidFileOfflineDownload(
            current = AndroidFileOfflinePersistedState(queue = started.state),
            startedJob = started.job,
            startedRecord = started.record,
            downloadedLocalRevision = CURRENT_REVISION,
            remoteEtag = SECOND_ETAG,
            nowEpochMillis = 40L,
        )

        assertTrue(commit.committed)
        assertEquals(setOf(OLD_REVISION), commit.removableLocalRevisions)
        assertEquals(CURRENT_REVISION, commit.state.queue.records.single().localRevision)
        assertTrue(commit.state.queue.jobs.isEmpty())
    }

    @Test
    fun persistedNewerRefreshRetainsItsGenerationAfterRestart() = withStore { store ->
        val started = startedRefresh(SECOND_ETAG)
        val newerRefresh = planFileOfflineRequest(
            started.state,
            pin(THIRD_ETAG, OLD_REVISION),
            nowEpochMillis = 40L,
        )
        store.save(AndroidFileOfflinePersistedState(queue = newerRefresh))

        val commit = commitAndroidFileOfflineDownload(
            current = store.load(),
            startedJob = started.job,
            startedRecord = started.record,
            downloadedLocalRevision = SUPERSEDED_REVISION,
            remoteEtag = SECOND_ETAG,
            nowEpochMillis = 50L,
        )

        assertFalse(commit.committed)
        assertEquals(setOf(SUPERSEDED_REVISION), commit.removableLocalRevisions)
        assertEquals(OLD_REVISION, commit.state.queue.records.single().localRevision)
        assertEquals(OLD_REVISION, commit.state.queue.jobs.single().expectedLocalRevision)
    }

    private fun startedRefresh(etag: String): StartedRefresh {
        val available = availableState()
        val refresh = planFileOfflineRequest(
            available,
            pin(etag, OLD_REVISION),
            nowEpochMillis = 30L,
        )
        val running = markFileOfflineJobRunning(refresh, refresh.jobs.single().id, nowEpochMillis = 31L)
        return StartedRefresh(running, running.jobs.single(), running.records.single())
    }

    private fun availableState(): FileOfflineQueueState {
        val queued = planFileOfflineRequest(
            FileOfflineQueueState(),
            pin(FIRST_ETAG, observedLocalRevision = null),
            nowEpochMillis = 10L,
        )
        return recordFileOfflineJobResult(
            queued,
            queued.jobs.single().id,
            FileOfflineJobResult.Downloaded(OLD_REVISION, FIRST_ETAG),
            nowEpochMillis = 20L,
        )
    }

    private fun pin(etag: String, observedLocalRevision: String?) = FileOfflineRequest.Pin(
        descriptor = FileOfflineDescriptor(
            key = KEY,
            displayName = "vault.md",
            remoteEtag = etag,
            size = 42L,
            mimeType = "text/markdown",
        ),
        observedLocalRevision = observedLocalRevision,
    )

    private fun withStore(block: (AndroidFileOfflineQueueStore) -> Unit) {
        val directory = Files.createTempDirectory("ncn-offline-download-completion-").toFile()
        try {
            block(AndroidFileOfflineQueueStore.forTesting(File(directory, "queue.bin")))
        } finally {
            directory.deleteRecursively()
        }
    }

    private data class StartedRefresh(
        val state: FileOfflineQueueState,
        val job: FileOfflineJob,
        val record: FileOfflinePinRecord,
    )

    private companion object {
        val KEY = FileOfflineKey("account-a", "Notes/vault.md")
        const val FIRST_ETAG = "\"remote-1\""
        const val SECOND_ETAG = "\"remote-2\""
        const val THIRD_ETAG = "\"remote-3\""
        const val OLD_REVISION = "sha256:old-generation"
        const val SUPERSEDED_REVISION = "sha256:superseded-generation"
        const val CURRENT_REVISION = "sha256:current-generation"
    }
}
