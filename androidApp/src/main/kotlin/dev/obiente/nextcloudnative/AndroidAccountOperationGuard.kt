package dev.obiente.nextcloudnative

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class AndroidAccountOperationGuard {
    private val monitor = Any()
    private val accountLeases = mutableMapOf<String, AccountLease>()

    suspend fun <Result> withAccount(accountId: String, action: suspend () -> Result): Result {
        val lease = synchronized(monitor) {
            accountLeases.getOrPut(accountId) { AccountLease() }.also { it.references += 1 }
        }
        return try {
            lease.mutex.withLock { action() }
        } finally {
            synchronized(monitor) {
                lease.references -= 1
                if (lease.references == 0) accountLeases.remove(accountId, lease)
            }
        }
    }

    suspend fun <Result> withAccountSession(
        accountId: String,
        resolveSession: suspend () -> dev.obiente.nextcloudnative.app.NextcloudSession?,
        unavailable: suspend () -> Result,
        action: suspend (dev.obiente.nextcloudnative.app.NextcloudSession) -> Result,
    ): Result = withAccount(accountId) {
        val session = resolveSession()
        if (androidAccountOperationSessionIsCurrent(accountId, session)) {
            action(requireNotNull(session))
        } else {
            unavailable()
        }
    }

    private class AccountLease(
        val mutex: Mutex = Mutex(),
        var references: Int = 0,
    )
}

internal val ANDROID_ACCOUNT_OPERATION_GUARD = AndroidAccountOperationGuard()

internal fun androidAccountOperationSessionIsCurrent(
    expectedAccountId: String,
    currentSession: dev.obiente.nextcloudnative.app.NextcloudSession?,
): Boolean = currentSession != null && NextcloudDocumentIds.accountKey(currentSession) == expectedAccountId
