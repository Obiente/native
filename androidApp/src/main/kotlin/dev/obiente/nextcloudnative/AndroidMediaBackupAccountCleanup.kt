package dev.obiente.nextcloudnative

import android.content.Context
import dev.obiente.nextcloudnative.app.MediaBackupLedgerStore

internal class AndroidMediaBackupAccountCleanup(
    private val openStore: () -> MediaBackupLedgerStore,
) {
    constructor(context: Context) : this(
        openStore = {
            createAndroidMediaBackupLedgerStore(
                context = context.applicationContext,
                recoverInterruptedTransfers = false,
            )
        },
    )

    suspend fun removeForAccount(accountId: String) {
        val store = openStore()
        try {
            store.deleteAccount(accountId)
        } finally {
            store.close()
        }
    }
}
