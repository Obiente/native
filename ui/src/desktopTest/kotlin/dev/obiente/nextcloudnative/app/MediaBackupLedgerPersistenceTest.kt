package dev.obiente.nextcloudnative.app

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class MediaBackupLedgerPersistenceTest {
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
}
