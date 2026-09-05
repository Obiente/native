package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.LocalMediaObject
import dev.obiente.nextcloudnative.app.MediaBackupLedgerRecord
import dev.obiente.nextcloudnative.app.MediaBackupLedgerStore
import dev.obiente.nextcloudnative.app.MediaBackupTransferState
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AndroidMediaBackupAccountCleanupTest {
    @Test
    fun cleanupDeletesOnlyTheRemovedAccountsLedgerRowsAndIsIdempotent() = runBlocking {
        val database = Files.createTempDirectory("android-media-cleanup-").resolve("ledger.db").toFile()
        val removed = "a".repeat(64)
        val retained = "b".repeat(64)
        try {
            MediaBackupLedgerStore(database.absolutePath).also { store ->
                store.upsert(record(removed, "removed"))
                store.upsert(record(retained, "retained"))
                store.close()
            }
            val cleanup = AndroidMediaBackupAccountCleanup {
                MediaBackupLedgerStore(database.absolutePath, recoverInterruptedTransfers = false)
            }

            repeat(2) { cleanup.removeForAccount(removed) }

            MediaBackupLedgerStore(database.absolutePath, recoverInterruptedTransfers = false).also { store ->
                assertNull(store.load(removed, "removed"))
                assertNotNull(store.load(retained, "retained"))
                store.close()
            }
        } finally {
            database.parentFile.deleteRecursively()
        }
    }

    @Test
    fun failedOpenLeavesRowsForJournaledRetry() = runBlocking {
        val database = Files.createTempDirectory("android-media-cleanup-retry-").resolve("ledger.db").toFile()
        val removed = "c".repeat(64)
        try {
            MediaBackupLedgerStore(database.absolutePath).also { store ->
                store.upsert(record(removed, "pending"))
                store.close()
            }
            var failOpen = true
            val cleanup = AndroidMediaBackupAccountCleanup {
                if (failOpen) error("synthetic ledger open failure")
                MediaBackupLedgerStore(database.absolutePath, recoverInterruptedTransfers = false)
            }

            assertFailsWith<IllegalStateException> { cleanup.removeForAccount(removed) }
            failOpen = false
            MediaBackupLedgerStore(database.absolutePath, recoverInterruptedTransfers = false).also { store ->
                assertNotNull(store.load(removed, "pending"))
                store.close()
            }

            cleanup.removeForAccount(removed)

            MediaBackupLedgerStore(database.absolutePath, recoverInterruptedTransfers = false).also { store ->
                assertNull(store.load(removed, "pending"))
                store.close()
            }
        } finally {
            database.parentFile.deleteRecursively()
        }
    }

    @Test
    fun accountRemovalWaitsForAStartedLedgerWriterAndDeletesItsResult() = runBlocking {
        val database = Files.createTempDirectory("android-media-cleanup-race-").resolve("ledger.db").toFile()
        val accountId = "d".repeat(64)
        val guard = AndroidAccountOperationGuard()
        val writerStarted = CompletableDeferred<Unit>()
        val finishWriter = CompletableDeferred<Unit>()
        var removalFinished = false
        try {
            val cleanup = AndroidMediaBackupAccountCleanup {
                MediaBackupLedgerStore(database.absolutePath, recoverInterruptedTransfers = false)
            }
            val writer = async {
                guard.withAccount(accountId) {
                    writerStarted.complete(Unit)
                    finishWriter.await()
                    MediaBackupLedgerStore(database.absolutePath).also { store ->
                        store.upsert(record(accountId, "late"))
                        store.close()
                    }
                }
            }
            writerStarted.await()
            val removal = async(start = CoroutineStart.UNDISPATCHED) {
                guard.withAccount(accountId) {
                    cleanup.removeForAccount(accountId)
                    removalFinished = true
                }
            }

            assertFalse(removalFinished)
            finishWriter.complete(Unit)
            writer.await()
            removal.await()
            MediaBackupLedgerStore(database.absolutePath, recoverInterruptedTransfers = false).also { store ->
                assertNull(store.load(accountId, "late"))
                store.close()
            }
        } finally {
            database.parentFile.deleteRecursively()
        }
    }

    private fun record(accountId: String, key: String) = MediaBackupLedgerRecord(
        accountId = accountId,
        local = LocalMediaObject(
            key = key,
            displayName = "$key.jpg",
            size = 4,
            revision = "generation-1",
        ),
        receipt = null,
        transferState = MediaBackupTransferState.Pending,
        attemptCount = 0,
        updatedAtEpochMillis = 1,
    )
}
