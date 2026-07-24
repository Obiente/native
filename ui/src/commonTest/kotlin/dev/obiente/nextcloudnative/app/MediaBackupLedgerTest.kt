package dev.obiente.nextcloudnative.app

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class MediaBackupLedgerTest {
    private val accountId = "0123456789abcdef0123456789abcdef"
    private val local = LocalMediaObject(
        key = "external:42",
        displayName = "IMG_0042.jpg",
        size = 4_096,
        revision = "generation:9",
    )
    private val receipt = MediaBackupReceipt(
        localKey = local.key,
        localRevision = local.revision,
        localSize = local.size,
        remotePath = "Photos/Camera/IMG_0042.jpg",
        remoteEtag = "\"remote-42\"",
        verifiedAtEpochMillis = 1_000,
    )

    @Test
    fun exactVerifiedRevisionIsBackedUpAndReclaimable() {
        assertEquals(MediaBackupStatus.BackedUp, resolveMediaBackupStatus(local, receipt))
        assertEquals(local.size, assertIs<MediaReclaimEligibility.Eligible>(
            mediaReclaimEligibility(local, receipt),
        ).bytes)
    }

    @Test
    fun changedLocalBytesCannotBeReclaimedUsingAnOldReceipt() {
        val changed = local.copy(revision = "generation:10")

        assertEquals(MediaBackupStatus.ChangedAfterBackup, resolveMediaBackupStatus(changed, receipt))
        assertEquals(MediaReclaimEligibility.LocalCopyChanged, mediaReclaimEligibility(changed, receipt))
    }

    @Test
    fun removedLocalOriginalRemainsRepresentedAsCloudOnly() {
        assertEquals(MediaBackupStatus.CloudOnly, resolveMediaBackupStatus(null, receipt))
        assertEquals(MediaReclaimEligibility.AlreadyCloudOnly, mediaReclaimEligibility(null, receipt))
    }

    @Test
    fun ledgerRecordsResolveEveryUserFacingBackupState() {
        fun record(
            state: MediaBackupTransferState,
            localObject: LocalMediaObject? = local,
            storedReceipt: MediaBackupReceipt? = null,
            failure: String? = null,
        ) = MediaBackupLedgerRecord(
            accountId = accountId,
            local = localObject,
            receipt = storedReceipt,
            transferState = state,
            attemptCount = 1,
            updatedAtEpochMillis = 2_000,
            failureMessage = failure,
        )

        assertEquals(
            MediaBackupStatus.Pending,
            record(MediaBackupTransferState.Pending).resolveMediaBackupStatus(),
        )
        assertEquals(
            MediaBackupStatus.Uploading,
            record(MediaBackupTransferState.Uploading).resolveMediaBackupStatus(),
        )
        assertEquals(
            MediaBackupStatus.Failed,
            record(MediaBackupTransferState.Failed, failure = "Network unavailable")
                .resolveMediaBackupStatus(),
        )
        assertEquals(
            MediaBackupStatus.BackedUp,
            record(MediaBackupTransferState.Succeeded, storedReceipt = receipt)
                .resolveMediaBackupStatus(),
        )
        assertEquals(
            MediaBackupStatus.ChangedAfterBackup,
            record(
                MediaBackupTransferState.Pending,
                storedReceipt = receipt.copy(localRevision = "generation:8"),
            ).resolveMediaBackupStatus(),
        )
        assertEquals(
            MediaBackupStatus.CloudOnly,
            record(
                MediaBackupTransferState.Succeeded,
                localObject = null,
                storedReceipt = receipt,
            ).resolveMediaBackupStatus(),
        )
        assertEquals(
            listOf("Pending", "Uploading", "Backed up", "Changed", "Failed", "Cloud only"),
            MediaBackupStatus.entries.map { it.presentation().label },
        )
    }

    @Test
    fun sqliteLedgerRecoversInterruptedUploadAsPending() = runBlocking {
        val connection = BundledSQLiteDriver().open(":memory:")
        val firstProcess = MediaBackupLedgerStore(connection)
        firstProcess.upsert(
            MediaBackupLedgerRecord(
                accountId = accountId,
                local = local,
                receipt = null,
                transferState = MediaBackupTransferState.Uploading,
                attemptCount = 1,
                updatedAtEpochMillis = 2_000,
            ),
        )

        val reopened = MediaBackupLedgerStore(connection)
        val recovered = reopened.load(accountId, local.key)

        assertEquals(MediaBackupTransferState.Pending, recovered?.transferState)
        assertEquals(1, recovered?.attemptCount)
        reopened.close()
    }

    @Test
    fun indexedPagesRemainAccountScopedAndStable() = runBlocking {
        val otherAccount = "fedcba9876543210fedcba9876543210"
        val store = MediaBackupLedgerStore(BundledSQLiteDriver().open(":memory:"))
        repeat(5) { index ->
            store.upsert(
                pendingRecord(
                    accountId = accountId,
                    key = "external:$index",
                    updatedAt = 1_000L - index,
                ),
            )
        }
        store.upsert(pendingRecord(otherAccount, "external:other", 2_000))

        val firstPage = store.page(accountId, limit = 2)
        val secondPage = store.page(accountId, after = firstPage.nextCursor, limit = 2)

        assertEquals(listOf("external:0", "external:1"), firstPage.records.map { it.localKey })
        assertEquals(listOf("external:2", "external:3"), secondPage.records.map { it.localKey })
        assertEquals(5, store.summary(accountId).pending)
        assertEquals(1, store.summary(otherAccount).pending)
        store.close()
    }

    @Test
    fun remotePathStatusLookupIsBoundedAccountScopedAndUsesNewestRecord() = runBlocking {
        val otherAccount = "fedcba9876543210fedcba9876543210"
        val store = MediaBackupLedgerStore(BundledSQLiteDriver().open(":memory:"))
        store.upsert(succeededRecord("external:old", 1_000))
        store.upsert(
            MediaBackupLedgerRecord(
                accountId = accountId,
                local = local.copy(key = "external:new"),
                receipt = receipt.copy(localKey = "external:new"),
                transferState = MediaBackupTransferState.Failed,
                attemptCount = 2,
                updatedAtEpochMillis = 2_000,
                failureMessage = "Upload failed",
            ),
        )
        val otherRecord = succeededRecord("external:other", 3_000)
        store.upsert(
            otherRecord.copy(
                accountId = otherAccount,
                receipt = requireNotNull(otherRecord.receipt).copy(remotePath = receipt.remotePath),
            ),
        )

        assertEquals(
            mapOf(receipt.remotePath to MediaBackupStatus.Failed),
            store.statusesForRemotePaths(accountId, listOf(receipt.remotePath, "Photos/Absent.jpg")),
        )
        assertEquals(
            mapOf(receipt.remotePath to MediaBackupStatus.BackedUp),
            store.statusesForRemotePaths(otherAccount, listOf(receipt.remotePath)),
        )
        assertFailsWith<IllegalArgumentException> {
            store.statusesForRemotePaths(
                accountId,
                List(MAX_MEDIA_BACKUP_STATUS_PATHS + 1) { "Photos/$it.jpg" },
            )
        }
        store.close()
    }

    @Test
    fun completedHistoryIsPrunedBeforeUnfinishedWork() = runBlocking {
        val store = MediaBackupLedgerStore(
            connection = BundledSQLiteDriver().open(":memory:"),
            maxRecordsPerAccount = 2,
        )
        store.upsert(succeededRecord("external:old", 1_000))
        store.upsert(succeededRecord("external:new", 2_000))
        store.upsert(pendingRecord(accountId, "external:pending", 3_000))

        assertEquals(null, store.load(accountId, "external:old"))
        assertEquals(2, store.summary(accountId).total)
        store.close()
    }

    @Test
    fun unfinishedOverflowRollsBackAndFutureSchemaIsRejected() = runBlocking {
        val connection = BundledSQLiteDriver().open(":memory:")
        val store = MediaBackupLedgerStore(connection, maxRecordsPerAccount = 2)
        store.upsert(pendingRecord(accountId, "external:1", 1_000))
        store.upsert(pendingRecord(accountId, "external:2", 2_000))

        assertFailsWith<IllegalStateException> {
            store.upsert(pendingRecord(accountId, "external:3", 3_000))
        }
        assertEquals(2, store.summary(accountId).total)
        store.close()

        val future = BundledSQLiteDriver().open(":memory:")
        future.execSQL("PRAGMA user_version = 3")
        assertFailsWith<MediaBackupLedgerStoreException> {
            MediaBackupLedgerStore(future)
        }
        Unit
    }

    @Test
    fun versionOneLedgerMigratesRemotePathIndex() = runBlocking {
        val connection = BundledSQLiteDriver().open(":memory:")
        MediaBackupLedgerStore(connection)
        connection.execSQL("DROP INDEX media_backup_account_remote_path_updated")
        connection.execSQL("PRAGMA user_version = 1")

        val migrated = MediaBackupLedgerStore(connection)

        connection.prepare("PRAGMA user_version").use { statement ->
            check(statement.step())
            assertEquals(2L, statement.getLong(0))
        }
        migrated.close()
    }

    @Test
    fun corruptRowsAreRejectedInsteadOfSilentlyCoerced() = runBlocking {
        val connection = BundledSQLiteDriver().open(":memory:")
        val store = MediaBackupLedgerStore(connection)
        store.upsert(pendingRecord(accountId, local.key, 1_000))
        connection.execSQL("PRAGMA ignore_check_constraints = ON")
        connection.prepare(
            "UPDATE media_backup_ledger SET transfer_state = 'Unknown' WHERE account_id = ?",
        ).use { statement ->
            statement.bindText(1, accountId)
            check(!statement.step())
        }

        assertFailsWith<IllegalArgumentException> {
            store.load(accountId, local.key)
        }
        store.close()
    }

    private fun pendingRecord(
        accountId: String,
        key: String,
        updatedAt: Long,
    ) = MediaBackupLedgerRecord(
        accountId = accountId,
        local = local.copy(key = key, displayName = "$key.jpg"),
        receipt = null,
        transferState = MediaBackupTransferState.Pending,
        attemptCount = 0,
        updatedAtEpochMillis = updatedAt,
    )

    private fun succeededRecord(key: String, updatedAt: Long): MediaBackupLedgerRecord {
        val objectAtKey = local.copy(key = key, displayName = "$key.jpg")
        return MediaBackupLedgerRecord(
            accountId = accountId,
            local = objectAtKey,
            receipt = receipt.copy(
                localKey = key,
                localRevision = objectAtKey.revision,
                localSize = objectAtKey.size,
                remotePath = "Photos/Camera/$key.jpg",
            ),
            transferState = MediaBackupTransferState.Succeeded,
            attemptCount = 1,
            updatedAtEpochMillis = updatedAt,
        )
    }
}
