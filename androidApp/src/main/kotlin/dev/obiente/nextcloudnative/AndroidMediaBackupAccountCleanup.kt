package dev.obiente.nextcloudnative

import android.content.Context
import dev.obiente.nextcloudnative.app.MediaBackupLedgerStore

internal class AndroidMediaBackupAccountCleanup(
    private val removeFromLedger: suspend (String) -> Unit,
) {
    constructor(context: Context) : this(
        removeFromLedger = { accountId ->
            val store = createAndroidMediaBackupLedgerStore(
                context = context.applicationContext,
                recoverInterruptedTransfers = false,
            )
            try {
                store.deleteAccount(accountId)
            } finally {
                store.close()
            }
        },
    )

    suspend fun removeForAccount(accountId: String) {
        removeFromLedger(accountId)
    }
}
