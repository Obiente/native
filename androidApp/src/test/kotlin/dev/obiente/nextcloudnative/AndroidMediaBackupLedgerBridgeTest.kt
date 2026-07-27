package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.FileSyncBaseline
import dev.obiente.nextcloudnative.app.FileSyncConfiguration
import dev.obiente.nextcloudnative.app.FileSyncDirection
import dev.obiente.nextcloudnative.app.FileSyncExecutionState
import dev.obiente.nextcloudnative.app.FileSyncOperation
import dev.obiente.nextcloudnative.app.FileSyncPair
import dev.obiente.nextcloudnative.app.FileSyncWorkItem
import dev.obiente.nextcloudnative.app.LocalSyncEntry
import dev.obiente.nextcloudnative.app.MediaBackupStatus
import dev.obiente.nextcloudnative.app.RemoteSyncEntry
import dev.obiente.nextcloudnative.app.SyncEntryKind
import dev.obiente.nextcloudnative.app.resolveMediaBackupStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

class AndroidMediaBackupLedgerBridgeTest {
    @Test
    fun projectionFailureCannotFailAuthoritativeSync() {
        runBlocking {
            assertTrue(runMediaBackupProjection { })
            assertFalse(runMediaBackupProjection { error("projection unavailable") })
            assertFailsWith<CancellationException> {
                runMediaBackupProjection { throw CancellationException("cancelled") }
            }
        }
    }

    private val accountId = "0123456789abcdef0123456789abcdef"
    private val localRoot = "${MEDIA_STORE_SYNC_ROOT_PREFIX}DCIM/Camera"
    private val pair = FileSyncPair(
        id = "pair-1",
        accountId = accountId,
        localRootId = localRoot,
        remoteRootPath = "Photos/Camera",
        configuration = FileSyncConfiguration(
            direction = FileSyncDirection.UploadOnly,
            deviceLabel = "Test device",
        ),
    )
    private val local = LocalSyncEntry(
        relativePath = "IMG_0042.jpg",
        kind = SyncEntryKind.File,
        revision = "generation:9",
        size = 4_096,
    )

    @Test
    fun localIdentityIsStableNonPersonalSha256() {
        val key = mediaBackupLocalKey(pair.id, localRoot, local.relativePath)

        assertEquals(64, key.length)
        assertTrue(key.all { it in "0123456789abcdef" })
        assertEquals(key, mediaBackupLocalKey(pair.id, localRoot, local.relativePath))
        assertNotEquals(key, mediaBackupLocalKey(pair.id, localRoot, "IMG_0043.jpg"))
        assertNotEquals(key, mediaBackupLocalKey("pair-2", localRoot, local.relativePath))
        assertNotEquals(key, legacyMediaBackupLocalKey(localRoot, local.relativePath))
    }

    @Test
    fun sameSourceFileInTwoPairsProducesIndependentLedgerRecords() {
        val work = uploadWork(FileSyncExecutionState.Ready)
        val secondPair = pair.copy(
            id = "pair-2",
            remoteRootPath = "Archive/Camera",
        )

        val primary = mediaBackupLedgerRecordForWork(pair, work, null, 1_000)
        val secondary = mediaBackupLedgerRecordForWork(secondPair, work, null, 1_000)

        assertNotEquals(primary.localKey, secondary.localKey)
        assertEquals(pair.id, primary.sourceId)
        assertEquals(secondPair.id, secondary.sourceId)
    }

    @Test
    fun uploadLifecycleAndChangedRevisionRemainVisible() {
        val ready = uploadWork(FileSyncExecutionState.Ready)

        var record = mediaBackupLedgerRecordForWork(pair, ready, existing = null, nowEpochMillis = 1_000)
        assertEquals(MediaBackupStatus.Pending, record.resolveMediaBackupStatus())
        assertEquals(pair.id, record.sourceId)

        val running = ready.copy(state = FileSyncExecutionState.Running, attemptCount = 1)
        record = mediaBackupLedgerRecordForWork(
            pair,
            running,
            existing = record,
            nowEpochMillis = 2_000,
        )
        assertEquals(MediaBackupStatus.Uploading, record.resolveMediaBackupStatus())

        record = mediaBackupLedgerRecordForWork(
            pair,
            running.copy(
                state = FileSyncExecutionState.Failed,
                failureMessage = "Network unavailable",
            ),
            existing = record,
            nowEpochMillis = 3_000,
        )
        assertEquals(MediaBackupStatus.Failed, record.resolveMediaBackupStatus())

        val baseline = FileSyncBaseline(
            relativePath = local.relativePath,
            kind = SyncEntryKind.File,
            localRevision = local.revision,
            remoteEtag = "\"remote-42\"",
        )
        record = mediaBackupSucceededRecord(
            pair = pair,
            work = running.copy(attemptCount = 2),
            local = local,
            baseline = baseline,
            nowEpochMillis = 4_000,
        )
        assertEquals(MediaBackupStatus.BackedUp, record.resolveMediaBackupStatus())
        assertEquals(pair.id, record.sourceId)
        assertEquals("Photos/Camera/IMG_0042.jpg", record.receipt?.remotePath)

        val changed = local.copy(revision = "generation:10")
        record = mediaBackupLedgerRecordForWork(
            pair = pair,
            work = ready.copy(
                observedLocal = changed,
                observedRemote = dev.obiente.nextcloudnative.app.RemoteSyncEntry(
                    relativePath = local.relativePath,
                    kind = SyncEntryKind.File,
                    etag = requireNotNull(baseline.remoteEtag),
                    size = local.size,
                ),
                observedBaseline = baseline,
            ),
            existing = record,
            nowEpochMillis = 5_000,
        )
        assertEquals(MediaBackupStatus.ChangedAfterBackup, record.resolveMediaBackupStatus())
    }

    @Test
    fun verifiedCoordinatorBaselineSeedsAPreviouslyEmptyLedger() {
        val baseline = FileSyncBaseline(
            relativePath = local.relativePath,
            kind = SyncEntryKind.File,
            localRevision = local.revision,
            remoteEtag = "\"remote-42\"",
        )

        val record = requireNotNull(
            mediaBackupVerifiedRecord(pair, local, baseline, existing = null, nowEpochMillis = 1_000),
        )

        assertEquals(MediaBackupStatus.BackedUp, record.resolveMediaBackupStatus())
        assertNull(mediaBackupVerifiedRecord(pair, local, baseline, record, nowEpochMillis = 2_000))
    }

    @Test
    fun missingLocalFileBecomesCloudOnlyOnlyWhileRemoteReceiptStillMatches() {
        val baseline = FileSyncBaseline(
            relativePath = local.relativePath,
            kind = SyncEntryKind.File,
            localRevision = local.revision,
            remoteEtag = "\"remote-42\"",
        )
        val backedUp = requireNotNull(
            mediaBackupVerifiedRecord(pair, local, baseline, existing = null, nowEpochMillis = 1_000),
        )
        val remote = RemoteSyncEntry(
            relativePath = local.relativePath,
            kind = SyncEntryKind.File,
            etag = requireNotNull(baseline.remoteEtag),
            size = local.size,
        )

        val cloudOnly = requireNotNull(
            mediaBackupCloudOnlyRecord(pair, baseline, remote, backedUp, nowEpochMillis = 2_000),
        )
        assertEquals(MediaBackupStatus.CloudOnly, cloudOnly.resolveMediaBackupStatus())
        assertNull(
            mediaBackupCloudOnlyRecord(
                pair,
                baseline,
                remote.copy(etag = "\"changed\""),
                backedUp,
                nowEpochMillis = 3_000,
            ),
        )
        val restored = requireNotNull(
            mediaBackupVerifiedRecord(pair, local, baseline, cloudOnly, nowEpochMillis = 4_000),
        )
        assertEquals(MediaBackupStatus.BackedUp, restored.resolveMediaBackupStatus())
    }

    private fun uploadWork(state: FileSyncExecutionState) = FileSyncWorkItem(
        id = 1,
        relativePath = local.relativePath,
        observedLocal = local,
        observedRemote = null,
        observedBaseline = null,
        operation = FileSyncOperation.Upload(local.relativePath, expectedRemoteEtag = null),
        state = state,
    )
}
