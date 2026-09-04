package dev.obiente.nextcloudnative

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex

internal class AndroidAccountOperationGuard {
    private val monitor = Any()
    private val accountLeases = mutableMapOf<String, AccountLease>()

    suspend fun <Result> withAccount(accountId: String, action: suspend () -> Result): Result {
        val lease = acquire(accountId)
        return try {
            action()
        } finally {
            lease.close()
        }
    }

    suspend fun <Result> withAccounts(accountIds: Collection<String>, action: suspend () -> Result): Result {
        val leases = mutableListOf<AndroidAccountOperationLease>()
        try {
            accountIds.distinct().sorted().forEach { accountId -> leases += acquire(accountId) }
            return action()
        } finally {
            leases.asReversed().forEach(AndroidAccountOperationLease::close)
        }
    }

    fun acquireBlocking(accountId: String): AndroidAccountOperationLease = runBlocking { acquire(accountId) }

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

    suspend fun <Result> withExactAccountSession(
        expectedSession: dev.obiente.nextcloudnative.app.NextcloudSession,
        resolveSession: suspend () -> dev.obiente.nextcloudnative.app.NextcloudSession?,
        unavailable: suspend () -> Result,
        action: suspend (dev.obiente.nextcloudnative.app.NextcloudSession) -> Result,
    ): Result = withAccount(NextcloudDocumentIds.accountKey(expectedSession)) {
        val current = resolveSession()
        if (current == expectedSession) action(current) else unavailable()
    }

    private suspend fun acquire(accountId: String): AndroidAccountOperationLease {
        require(accountId.isNotBlank())
        val lease = synchronized(monitor) {
            accountLeases.getOrPut(accountId) { AccountLease() }.also { it.references += 1 }
        }
        try {
            lease.mutex.lock()
        } catch (failure: Throwable) {
            releaseReference(accountId, lease)
            throw failure
        }
        return AndroidAccountOperationLease {
            lease.mutex.unlock()
            releaseReference(accountId, lease)
        }
    }

    private fun releaseReference(accountId: String, lease: AccountLease) {
        synchronized(monitor) {
            lease.references -= 1
            if (lease.references == 0) accountLeases.remove(accountId, lease)
        }
    }

    private class AccountLease(
        val mutex: Mutex = Mutex(),
        var references: Int = 0,
    )
}

internal class AndroidAccountOperationLease(
    private val release: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) release()
    }
}

internal val ANDROID_ACCOUNT_OPERATION_GUARD = AndroidAccountOperationGuard()

internal fun androidAccountOperationSessionIsCurrent(
    expectedAccountId: String,
    currentSession: dev.obiente.nextcloudnative.app.NextcloudSession?,
): Boolean = currentSession != null && NextcloudDocumentIds.accountKey(currentSession) == expectedAccountId

internal fun androidDocumentWritebackSessionIsCurrent(
    expectedSession: dev.obiente.nextcloudnative.app.NextcloudSession,
    currentSession: dev.obiente.nextcloudnative.app.NextcloudSession?,
): Boolean = currentSession == expectedSession

internal suspend fun <Result> AndroidAccountOperationGuard.withAuthenticatedMutationSession(
    expectedSession: dev.obiente.nextcloudnative.app.NextcloudSession,
    resolveSession: suspend () -> dev.obiente.nextcloudnative.app.NextcloudSession?,
    action: suspend (dev.obiente.nextcloudnative.app.NextcloudSession) -> Result,
): Result = withExactAccountSession(
    expectedSession = expectedSession,
    resolveSession = resolveSession,
    unavailable = { error("The account changed before the authenticated change could be sent.") },
    action = action,
)
