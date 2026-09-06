package dev.obiente.nextcloudnative.app

internal class DesktopFileReadCacheProducer internal constructor(
    internal val accountId: String,
    internal val incarnation: Long,
)

/** Account incarnations for file-cache operations, called while the cache monitor is held. */
internal class DesktopFileReadCacheLifecycle {
    private val retiredAccounts = mutableSetOf<String>()
    private val incarnations = mutableMapOf<String, Long>()

    fun producer(accountId: String): DesktopFileReadCacheProducer? =
        if (accountId in retiredAccounts) null else DesktopFileReadCacheProducer(
            accountId,
            incarnations[accountId] ?: 0L,
        )

    fun accepts(accountId: String, producer: DesktopFileReadCacheProducer?): Boolean {
        val current = producer ?: return false
        return current.accountId == accountId && accountId !in retiredAccounts &&
            current.incarnation == (incarnations[accountId] ?: 0L)
    }

    fun retire(accountId: String) {
        if (retiredAccounts.add(accountId)) incarnations[accountId] = (incarnations[accountId] ?: 0L) + 1L
    }

    fun activate(accountId: String) {
        retiredAccounts.remove(accountId)
    }

    fun isActive(accountId: String): Boolean = accountId !in retiredAccounts
}

internal fun DesktopFileReadCache.producerFor(session: NextcloudSession): Pair<String, DesktopFileReadCacheProducer?> =
    desktopFileCacheAccountId(session).let { accountId -> accountId to producer(accountId) }
