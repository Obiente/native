package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** Captures the owner before asynchronous UI work can outlive account removal. */
internal class AccountHomeWorkspaceStorage(
    private val delegate: HomeWorkspaceLayoutStorage,
    private val accountStorageKey: String,
    legacyAccountScopeDigest: String?,
    private val gate: AccountPrivateMemoryGate = sharedAccountPrivateMemoryGate,
) : HomeWorkspaceLayoutStorage {
    private val producer = gate.producer(accountStorageKey)
    private val keys = homeWorkspaceAccountPersistenceKeys(accountStorageKey, legacyAccountScopeDigest)

    override fun read(persistenceKey: String): String? = access(persistenceKey) { delegate.read(persistenceKey) }

    override fun write(persistenceKey: String, encodedSnapshot: String) = access(persistenceKey) {
        delegate.write(persistenceKey, encodedSnapshot)
    }

    override fun writeIfAbsent(persistenceKey: String, encodedSnapshot: String): Boolean = access(persistenceKey) {
        delegate.writeIfAbsent(persistenceKey, encodedSnapshot)
    }

    private fun <T> access(key: String, action: () -> T): T {
        require(key in keys) { "The workspace key belongs to another account." }
        var result: Result<T>? = null
        check(gate.mutate(accountStorageKey, producer) { result = Result.success(action()) }) {
            "The workspace account is no longer active."
        }
        return checkNotNull(result).getOrThrow()
    }
}

@Composable
internal fun rememberAccountHomeWorkspaceStorage(session: NextcloudSession): HomeWorkspaceLayoutStorage {
    val storage = rememberHomeWorkspaceLayoutStorage()
    return remember(storage, session) {
        AccountHomeWorkspaceStorage(storage, session.accountId.storageKey, accountPersistenceScopeDigests(session).legacy)
    }
}
