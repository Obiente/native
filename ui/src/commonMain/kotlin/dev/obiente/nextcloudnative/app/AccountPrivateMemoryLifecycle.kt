package dev.obiente.nextcloudnative.app

/** One account incarnation allowed to publish into process-local private-memory stores. */
internal class AccountPrivateMemoryProducer internal constructor(
    val accountStorageKey: String,
    internal val incarnation: Long,
)

/** Serializes private-memory access with retirement and rejects stale async producers. */
internal class AccountPrivateMemoryGate {
    private val lock = DynamicNativeMemoryCacheLock()
    private val closedAccounts = mutableSetOf<String>()
    private val accountIncarnations = mutableMapOf<String, Long>()

    fun producer(accountStorageKey: String): AccountPrivateMemoryProducer? = lock.withLock {
        if (accountStorageKey in closedAccounts) return@withLock null
        AccountPrivateMemoryProducer(accountStorageKey, accountIncarnations[accountStorageKey] ?: 0L)
    }

    fun <T> read(accountStorageKey: String, unavailable: T, action: () -> T): T = lock.withLock {
        if (accountStorageKey in closedAccounts) unavailable else action()
    }

    fun mutate(
        accountStorageKey: String,
        producer: AccountPrivateMemoryProducer?,
        action: () -> Unit,
    ): Boolean = lock.withLock {
        val current = producer ?: return@withLock false
        require(current.accountStorageKey == accountStorageKey) {
            "The private-memory producer belongs to another account."
        }
        if (!accepts(current)) return@withLock false
        action()
        true
    }

    fun retireAccount(accountStorageKey: String, purge: () -> Unit) = lock.withLock {
        if (closedAccounts.add(accountStorageKey)) {
            accountIncarnations[accountStorageKey] = (accountIncarnations[accountStorageKey] ?: 0L) + 1L
        }
        purge()
    }

    fun activateAccount(accountStorageKey: String, prepare: () -> Unit = {}) = lock.withLock {
        prepare()
        closedAccounts.remove(accountStorageKey)
    }

    private fun accepts(producer: AccountPrivateMemoryProducer): Boolean =
        producer.accountStorageKey !in closedAccounts &&
            (accountIncarnations[producer.accountStorageKey] ?: 0L) == producer.incarnation
}

internal val sharedAccountPrivateMemoryGate = AccountPrivateMemoryGate()

/** Cross-platform lifecycle boundary for account-private process memory. */
object AccountPrivateMemoryLifecycle {
    fun retireAccount(accountStorageKey: String) = sharedAccountPrivateMemoryGate.retireAccount(accountStorageKey) {
        AccountPrivateMemoryCleanup.purgeRetiredAccount(accountStorageKey)
    }

    fun activateAccount(accountStorageKey: String) = sharedAccountPrivateMemoryGate.activateAccount(
        accountStorageKey,
        prepare = { sharedDynamicNativeMemoryCache.activateAccount(accountStorageKey) },
    )
}
