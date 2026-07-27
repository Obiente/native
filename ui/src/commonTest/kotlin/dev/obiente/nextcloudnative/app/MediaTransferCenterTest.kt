package dev.obiente.nextcloudnative.app

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaTransferCenterTest {
    private val accountId = "0123456789abcdef0123456789abcdef"

    @Test
    fun sectionsExposeOnlyContextualActions() {
        assertEquals(
            setOf(MediaTransferAction.Details, MediaTransferAction.Cancel),
            record(MediaBackupTransferState.Pending).availableTransferActions(),
        )
        assertEquals(
            setOf(MediaTransferAction.Details, MediaTransferAction.Cancel),
            record(MediaBackupTransferState.Uploading).availableTransferActions(),
        )
        assertEquals(
            setOf(MediaTransferAction.Details, MediaTransferAction.Retry),
            record(MediaBackupTransferState.Failed).availableTransferActions(),
        )
        assertEquals(
            setOf(MediaTransferAction.Details),
            record(MediaBackupTransferState.Succeeded).availableTransferActions(),
        )
    }

    @Test
    fun productionHostAndCapturesExposeDetailsOnly() {
        assertEquals(
            setOf(MediaTransferAction.Details),
            PRODUCTION_MEDIA_TRANSFER_ACTIONS,
        )
        MediaBackupTransferState.entries.forEach { state ->
            assertTrue(
                record(state).availableTransferActions().containsAll(
                    PRODUCTION_MEDIA_TRANSFER_ACTIONS,
                ),
            )
        }
    }

    @Test
    fun transferCaptureFixturesCoverBoundedAndCachedStates() {
        val pending = marketingMediaTransferFixture(
            MarketingCaptureScenario.TransferMobilePending,
        )
        assertEquals(MediaTransferSection.Pending, pending.state.page.section)
        assertEquals(3, pending.state.page.records.size)

        val failed = marketingMediaTransferFixture(
            MarketingCaptureScenario.TransferMobileFailed,
        )
        assertEquals(MediaTransferSection.Failed, failed.state.page.section)
        assertTrue(failed.statusMessageIsError)
        assertTrue(failed.statusMessage?.contains("saved transfer history") == true)
        assertTrue(failed.state.page.records.isNotEmpty())

        val active = marketingMediaTransferFixture(
            MarketingCaptureScenario.TransferDesktopActive,
        )
        assertEquals(MediaTransferSection.Active, active.state.page.section)
        assertNotNull(active.busyLocalKey)

        val completed = marketingMediaTransferFixture(
            MarketingCaptureScenario.TransferDesktopCompleted,
        )
        assertEquals(MediaTransferSection.Completed, completed.state.page.section)
        assertTrue(completed.state.canLoadNewer)
        assertNotNull(completed.state.page.nextCursor)
        assertTrue(completed.state.page.records.size < completed.state.summary.succeeded)
    }

    @Test
    fun presentationWindowRejectsUnboundedOrMismatchedPages() {
        val records = List(MEDIA_TRANSFER_CENTER_PAGE_SIZE + 1) {
            record(MediaBackupTransferState.Pending, "media:$it")
        }
        assertFailsWith<IllegalArgumentException> {
            MediaTransferCenterPage(MediaTransferSection.Pending, records, null)
        }
        assertFailsWith<IllegalArgumentException> {
            MediaTransferCenterPage(
                MediaTransferSection.Completed,
                listOf(record(MediaBackupTransferState.Pending)),
                null,
            )
        }
    }

    @Test
    fun clearHistoryRequiresExplicitConfirmationAndExplainsItsLocalScope() {
        var clearCalls = 0

        val unavailable = requestMediaTransferClearHistory(completedCount = 0)
        assertEquals(MediaTransferClearHistoryConfirmation.Hidden, unavailable)
        assertEquals(
            MediaTransferClearHistoryConfirmation.Hidden,
            confirmMediaTransferClearHistory(unavailable) { clearCalls += 1 },
        )
        assertEquals(0, clearCalls)

        val requested = requestMediaTransferClearHistory(completedCount = 3)
        assertEquals(MediaTransferClearHistoryConfirmation.Requested, requested)
        assertEquals(
            MediaTransferClearHistoryConfirmation.Hidden,
            confirmMediaTransferClearHistory(requested) { clearCalls += 1 },
        )
        assertEquals(1, clearCalls)
        assertTrue(MEDIA_TRANSFER_CLEAR_HISTORY_MESSAGE.contains("local transfer history"))
        assertTrue(MEDIA_TRANSFER_CLEAR_HISTORY_MESSAGE.contains("does not delete media"))
        assertTrue(MEDIA_TRANSFER_CLEAR_HISTORY_MESSAGE.contains("this device or Nextcloud"))
    }

    @Test
    fun retryCancelAndClearAreAtomicStateGuardedAndPreserveBackupProof() = runBlocking {
        val store = MediaBackupLedgerStore(BundledSQLiteDriver().open(":memory:"))
        val failed = record(MediaBackupTransferState.Failed, "media:failed")
        val pending = record(MediaBackupTransferState.Pending, "media:pending")
        val completed = record(MediaBackupTransferState.Succeeded, "media:completed")
        store.upsertAll(listOf(failed, pending, completed))

        assertTrue(store.retryFailed(accountId, failed.localKey, 20_000))
        assertEquals(MediaBackupTransferState.Pending, store.load(accountId, failed.localKey)?.transferState)
        assertFalse(store.retryFailed(accountId, pending.localKey, 20_001))
        assertTrue(store.removePending(accountId, pending.localKey))
        assertNull(store.load(accountId, pending.localKey))
        assertFalse(store.removePending(accountId, completed.localKey))
        assertEquals(1, store.clearCompleted(accountId))
        assertEquals(1, store.summary(accountId).pending)
        assertEquals(1, store.summary(accountId).succeeded)
        assertEquals(
            0,
            store.summary(accountId, includeClearedCompleted = false).succeeded,
        )
        assertTrue(
            store.page(
                accountId = accountId,
                transferState = MediaBackupTransferState.Succeeded,
                includeClearedCompleted = false,
            ).records.isEmpty(),
        )
        val retainedReceipt = assertNotNull(store.load(accountId, completed.localKey))
        assertEquals(MediaBackupStatus.BackedUp, retainedReceipt.resolveMediaBackupStatus())
        assertEquals(
            mapOf(
                requireNotNull(retainedReceipt.receipt).remotePath to MediaBackupStatus.BackedUp,
            ),
            store.statusesForRemotePaths(
                accountId,
                listOf(requireNotNull(retainedReceipt.receipt).remotePath),
            ),
        )
        assertEquals(0, store.clearCompleted(accountId))
        store.close()
    }

    @Test
    fun interruptedTransferRecoveryIsAccountScoped() = runBlocking {
        val otherAccountId = "fedcba9876543210fedcba9876543210"
        val store = MediaBackupLedgerStore(BundledSQLiteDriver().open(":memory:"))
        store.upsertAll(
            listOf(
                record(MediaBackupTransferState.Uploading, "media:active"),
                record(MediaBackupTransferState.Pending, "media:pending"),
                record(MediaBackupTransferState.Uploading, "media:other").copy(
                    accountId = otherAccountId,
                ),
            ),
        )

        assertEquals(1, store.recoverInterruptedTransfers(accountId))
        assertEquals(
            MediaBackupTransferState.Pending,
            store.load(accountId, "media:active")?.transferState,
        )
        assertEquals(
            MediaBackupTransferState.Pending,
            store.load(accountId, "media:pending")?.transferState,
        )
        assertEquals(
            MediaBackupTransferState.Uploading,
            store.load(otherAccountId, "media:other")?.transferState,
        )
        assertEquals(0, store.recoverInterruptedTransfers(accountId))
        store.close()
    }

    @Test
    fun interruptedTransferReconciliationPreservesOnlyAuthoritativeActiveSources() = runBlocking {
        val store = MediaBackupLedgerStore(BundledSQLiteDriver().open(":memory:"))
        store.upsertAll(
            listOf(
                record(
                    MediaBackupTransferState.Uploading,
                    key = "media:active",
                    sourceId = "pair-active",
                ),
                record(
                    MediaBackupTransferState.Uploading,
                    key = "media:interrupted",
                    sourceId = "pair-interrupted",
                ),
                record(
                    MediaBackupTransferState.Uploading,
                    key = "media:legacy",
                ),
            ),
        )

        assertEquals(
            1,
            store.reconcileInterruptedTransfers(accountId, setOf("pair-active")),
        )
        assertEquals(
            MediaBackupTransferState.Uploading,
            store.load(accountId, "media:active")?.transferState,
        )
        assertEquals(
            MediaBackupTransferState.Pending,
            store.load(accountId, "media:interrupted")?.transferState,
        )
        assertEquals(
            MediaBackupTransferState.Uploading,
            store.load(accountId, "media:legacy")?.transferState,
        )

        assertEquals(2, store.reconcileInterruptedTransfers(accountId, emptySet()))
        assertEquals(3, store.summary(accountId).pending)
        store.close()
    }

    @Test
    fun deletingTransferSourcePurgesOnlyThatSyncPairsProjection() = runBlocking {
        val store = MediaBackupLedgerStore(BundledSQLiteDriver().open(":memory:"))
        store.upsertAll(
            listOf(
                record(
                    MediaBackupTransferState.Pending,
                    key = "media:first",
                    sourceId = "pair-first",
                ),
                record(
                    MediaBackupTransferState.Succeeded,
                    key = "media:first-completed",
                    sourceId = "pair-first",
                ),
                record(
                    MediaBackupTransferState.Pending,
                    key = "media:second",
                    sourceId = "pair-second",
                ),
                record(
                    MediaBackupTransferState.Pending,
                    key = "media:legacy-first",
                ),
                record(
                    MediaBackupTransferState.Pending,
                    key = "media:legacy-other",
                ),
            ),
        )

        assertEquals(
            2,
            store.deleteUnfinishedSource(
                accountId,
                "pair-first",
                legacyLocalKeys = listOf("media:legacy-first"),
            ),
        )
        assertNull(store.load(accountId, "media:first"))
        assertNotNull(store.load(accountId, "media:first-completed"))
        assertNull(store.load(accountId, "media:legacy-first"))
        assertNotNull(store.load(accountId, "media:second"))
        assertNotNull(store.load(accountId, "media:legacy-other"))
        assertEquals(0, store.deleteUnfinishedSource(accountId, "pair-first"))
        store.close()
    }

    @Test
    fun savedCursorHistoryIsBoundedAndRejectsInvalidState() {
        val history = listOf<MediaBackupLedgerCursor?>(null) +
            List(80) { index ->
                MediaBackupLedgerCursor(
                    updatedAtEpochMillis = index.toLong(),
                    localKey = "media:$index",
                )
            }

        val bounded = boundedMediaTransferCursorHistory(history)
        assertEquals(64, bounded.size)
        assertNull(bounded.first())
        assertEquals("media:17", bounded[1]?.localKey)
        assertEquals("media:79", bounded.last()?.localKey)
        assertEquals(
            bounded,
            restoreMediaTransferCursorHistory(encodeMediaTransferCursorHistory(history)),
        )
        assertEquals(listOf(null), restoreMediaTransferCursorHistory("not-a-cursor"))
        assertEquals(listOf(null), restoreMediaTransferCursorHistory("-\ninvalid"))
    }

    @Test
    fun tenThousandPersistedCompletionsKeepHistoryAndUiWindowsBounded() = runBlocking {
        val retainedHistory = 1_000
        val store = MediaBackupLedgerStore(
            connection = BundledSQLiteDriver().open(":memory:"),
            maxRecordsPerAccount = retainedHistory,
        )
        repeat(20) { batch ->
            store.upsertAll(
                List(MAX_MEDIA_BACKUP_LEDGER_WRITE_BATCH) { offset ->
                    val index = batch * MAX_MEDIA_BACKUP_LEDGER_WRITE_BATCH + offset
                    record(
                        state = MediaBackupTransferState.Succeeded,
                        key = "media:$index",
                        updatedAt = index.toLong(),
                    )
                },
            )
        }

        val ledgerPage = store.page(
            accountId = accountId,
            transferState = MediaBackupTransferState.Succeeded,
            limit = MEDIA_TRANSFER_CENTER_PAGE_SIZE,
        )
        val state = mediaTransferCenterState(
            summary = store.summary(accountId),
            section = MediaTransferSection.Completed,
            page = ledgerPage,
            canLoadNewer = false,
        )

        assertEquals(retainedHistory, state.summary.succeeded)
        assertEquals(MEDIA_TRANSFER_CENTER_PAGE_SIZE, state.page.records.size)
        assertTrue(state.page.nextCursor != null)
        assertEquals("media:9999", state.page.records.first().localKey)
        store.close()
    }

    private fun record(
        state: MediaBackupTransferState,
        key: String = "media:1",
        updatedAt: Long = 10_000,
        sourceId: String? = null,
    ): MediaBackupLedgerRecord {
        val local = LocalMediaObject(
            key = key,
            displayName = "photo-${key.substringAfter(':')}.jpg",
            size = 4_096,
            revision = "revision-1",
        )
        val receipt = if (state == MediaBackupTransferState.Succeeded) {
            MediaBackupReceipt(
                localKey = key,
                localRevision = local.revision,
                localSize = local.size,
                remotePath = "Photos/Camera/${local.displayName}",
                remoteEtag = "\"fixture-etag\"",
                verifiedAtEpochMillis = updatedAt,
            )
        } else {
            null
        }
        return MediaBackupLedgerRecord(
            accountId = accountId,
            sourceId = sourceId,
            local = local,
            receipt = receipt,
            transferState = state,
            attemptCount = if (state == MediaBackupTransferState.Pending) 0 else 1,
            updatedAtEpochMillis = updatedAt,
            failureMessage = if (state == MediaBackupTransferState.Failed) {
                "Synthetic network interruption"
            } else {
                null
            },
        )
    }
}
