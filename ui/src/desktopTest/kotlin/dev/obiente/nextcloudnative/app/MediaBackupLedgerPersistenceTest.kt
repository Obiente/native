package dev.obiente.nextcloudnative.app

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class MediaBackupLedgerPersistenceTest {
    @Test
    fun concurrentFirstOpenSerializesSchemaMigration() = runBlocking {
        repeat(20) { iteration ->
            val directory = Files.createTempDirectory("media-ledger-concurrent-$iteration-")
            val databasePath = directory.resolve("ledger.db").toString()

            val stores = coroutineScope {
                List(8) {
                    async(Dispatchers.IO) {
                        MediaBackupLedgerStore(
                            databasePath = databasePath,
                            recoverInterruptedTransfers = false,
                        )
                    }
                }.awaitAll()
            }
            stores.forEach { store -> store.close() }

            val reopened = MediaBackupLedgerStore(
                databasePath = databasePath,
                recoverInterruptedTransfers = false,
            )
            assertEquals(0, reopened.summary("0123456789abcdef0123456789abcdef").total)
            reopened.close()
            directory.toFile().deleteRecursively()
        }
        Unit
    }

    @Test
    fun concurrentVersionThreeUpgradeAddsTransferSourceOnce() = runBlocking {
        val directory = Files.createTempDirectory("media-ledger-upgrade-")
        val databasePath = directory.resolve("ledger.db").toString()
        MediaBackupLedgerStore(databasePath, recoverInterruptedTransfers = false).close()
        BundledSQLiteDriver().open(databasePath).use { connection ->
            connection.execSQL("DROP INDEX media_backup_account_source")
            connection.execSQL("ALTER TABLE media_backup_ledger DROP COLUMN source_id")
            connection.execSQL("PRAGMA user_version = 3")
        }

        val stores = coroutineScope {
            List(4) {
                async(Dispatchers.IO) {
                    MediaBackupLedgerStore(
                        databasePath = databasePath,
                        recoverInterruptedTransfers = false,
                    )
                }
            }.awaitAll()
        }
        stores.forEach { store -> store.close() }
        BundledSQLiteDriver().open(databasePath).use { connection ->
            connection.prepare("PRAGMA user_version").use { statement ->
                check(statement.step())
                assertEquals(4, statement.getLong(0))
            }
        }

        directory.toFile().deleteRecursively()
        Unit
    }

    @Test
    fun readOnlyUiConnectionDoesNotRecoverAnActiveUpload() = runBlocking {
        val directory = Files.createTempDirectory("media-ledger-reader-")
        val databasePath = directory.resolve("ledger.db").toString()
        val accountId = "0123456789abcdef0123456789abcdef"
        val local = LocalMediaObject(
            key = "external:active",
            displayName = "active.jpg",
            size = 2_048,
            revision = "generation:5",
        )
        val writer = MediaBackupLedgerStore(databasePath)
        writer.upsert(
            MediaBackupLedgerRecord(
                accountId = accountId,
                local = local,
                receipt = null,
                transferState = MediaBackupTransferState.Uploading,
                attemptCount = 1,
                updatedAtEpochMillis = 2_000,
            ),
        )

        val reader = MediaBackupLedgerStore(
            databasePath = databasePath,
            recoverInterruptedTransfers = false,
        )
        assertEquals(MediaBackupTransferState.Uploading, reader.load(accountId, local.key)?.transferState)
        reader.close()
        assertEquals(MediaBackupTransferState.Uploading, writer.load(accountId, local.key)?.transferState)

        writer.close()
        directory.toFile().deleteRecursively()
        Unit
    }

    @Test
    fun fileBackedLedgerSurvivesCloseAndRecoversActiveWork() = runBlocking {
        val directory = Files.createTempDirectory("media-ledger-")
        val databasePath = directory.resolve("ledger.db").toString()
        val accountId = "0123456789abcdef0123456789abcdef"
        val local = LocalMediaObject(
            key = "external:42",
            displayName = "IMG_0042.jpg",
            size = 4_096,
            revision = "generation:9",
        )
        val completedLocal = local.copy(
            key = "external:43",
            displayName = "IMG_0043.jpg",
            revision = "generation:10",
        )
        val completedReceipt = MediaBackupReceipt(
            localKey = completedLocal.key,
            localRevision = completedLocal.revision,
            localSize = completedLocal.size,
            remotePath = "Photos/Camera/IMG_0043.jpg",
            remoteEtag = "\"remote-43\"",
            verifiedAtEpochMillis = 1_500,
        )
        MediaBackupLedgerStore(databasePath).also { store ->
            store.upsert(
                MediaBackupLedgerRecord(
                    accountId = accountId,
                    local = local,
                    receipt = null,
                    transferState = MediaBackupTransferState.Uploading,
                    attemptCount = 1,
                    updatedAtEpochMillis = 1_000,
                ),
            )
            store.upsert(
                MediaBackupLedgerRecord(
                    accountId = accountId,
                    local = completedLocal,
                    receipt = completedReceipt,
                    transferState = MediaBackupTransferState.Succeeded,
                    attemptCount = 1,
                    updatedAtEpochMillis = 1_500,
                ),
            )
            store.close()
        }

        BundledSQLiteDriver().open(databasePath).use { connection ->
            connection.prepare("PRAGMA journal_mode").use { statement ->
                check(statement.step())
                assertEquals("wal", statement.getText(0).lowercase())
            }
        }
        val reopened = MediaBackupLedgerStore(databasePath)

        assertEquals(MediaBackupTransferState.Pending, reopened.load(accountId, local.key)?.transferState)
        assertEquals(completedReceipt, reopened.load(accountId, completedLocal.key)?.receipt)
        reopened.close()
        directory.toFile().deleteRecursively()
        Unit
    }

    @Test
    fun clearedCompletedHistoryStaysHiddenAfterReopenAndIdenticalProjection() = runBlocking {
        val directory = Files.createTempDirectory("media-ledger-cleared-")
        val databasePath = directory.resolve("ledger.db").toString()
        val accountId = "0123456789abcdef0123456789abcdef"
        val local = LocalMediaObject(
            key = "fixture:completed",
            displayName = "completed.jpg",
            size = 4_096,
            revision = "generation:10",
        )
        val record = MediaBackupLedgerRecord(
            accountId = accountId,
            sourceId = "pair-fixture",
            local = local,
            receipt = MediaBackupReceipt(
                localKey = local.key,
                localRevision = local.revision,
                localSize = local.size,
                remotePath = "Photos/Fixture/completed.jpg",
                remoteEtag = "\"fixture-etag\"",
                verifiedAtEpochMillis = 1_500,
            ),
            transferState = MediaBackupTransferState.Succeeded,
            attemptCount = 1,
            updatedAtEpochMillis = 1_500,
        )

        MediaBackupLedgerStore(databasePath, recoverInterruptedTransfers = false).also { store ->
            store.upsert(record)
            assertEquals(1, store.clearCompleted(accountId))
            store.close()
        }
        MediaBackupLedgerStore(databasePath, recoverInterruptedTransfers = false).also { reopened ->
            assertEquals(0, reopened.summary(accountId, includeClearedCompleted = false).succeeded)
            reopened.upsert(record.copy(updatedAtEpochMillis = 2_000))
            assertEquals(0, reopened.summary(accountId, includeClearedCompleted = false).succeeded)
            assertEquals(1, reopened.summary(accountId).succeeded)
            reopened.close()
        }

        directory.toFile().deleteRecursively()
        Unit
    }
}
