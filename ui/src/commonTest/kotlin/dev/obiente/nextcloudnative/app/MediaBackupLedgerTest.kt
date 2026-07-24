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
        future.execSQL("PRAGMA user_version = 2")
        assertFailsWith<MediaBackupLedgerStoreException> {
            MediaBackupLedgerStore(future)
        }
        Unit
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
