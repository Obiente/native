package dev.obiente.nextcloudnative

import android.content.Context
import dev.obiente.nextcloudnative.app.MediaBackupLedgerStore
import java.io.File

internal fun createAndroidMediaBackupLedgerStore(
    context: Context,
    recoverInterruptedTransfers: Boolean = true,
): MediaBackupLedgerStore =
    MediaBackupLedgerStore(
        databasePath = File(
            context.applicationContext.noBackupFilesDir,
            "media-backup-ledger.db",
        ).absolutePath,
        recoverInterruptedTransfers = recoverInterruptedTransfers,
    )
