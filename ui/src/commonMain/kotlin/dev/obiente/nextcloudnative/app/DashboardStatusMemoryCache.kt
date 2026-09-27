package dev.obiente.nextcloudnative.app

data class CachedDashboardStatus(
    val dashboard: NativeDashboardSnapshot,
    val status: NativeUserStatus?,
    val storedAtEpochSeconds: Long,
)

/** Account-private process cache. It stores no password and expires quickly. */
internal class DashboardStatusMemoryCache(
    private val ttlSeconds: Long = DASHBOARD_STATUS_CACHE_TTL_SECONDS,
    private val gate: AccountPrivateMemoryGate = AccountPrivateMemoryGate(),
) {
    private val entries = mutableMapOf<NextcloudAccountId, CachedDashboardStatus>()

    fun producer(session: NextcloudSession): AccountPrivateMemoryProducer? =
        gate.producer(session.accountId.storageKey)

    fun get(session: NextcloudSession, nowEpochSeconds: Long): CachedDashboardStatus? =
        gate.read(session.accountId.storageKey, null) {
            val entry = entries[session.accountId] ?: return@read null
            entry.takeIf { cached ->
                nowEpochSeconds >= cached.storedAtEpochSeconds &&
                    nowEpochSeconds - cached.storedAtEpochSeconds <= ttlSeconds
            } ?: run {
                entries.remove(session.accountId)
                null
            }
        }

    fun store(
        session: NextcloudSession,
        dashboard: NativeDashboardSnapshot,
        status: NativeUserStatus?,
        nowEpochSeconds: Long,
        producer: AccountPrivateMemoryProducer?,
    ) {
        require(nowEpochSeconds >= 0L) { "The dashboard cache timestamp is invalid." }
        gate.mutate(session.accountId.storageKey, producer) {
            entries[session.accountId] = CachedDashboardStatus(dashboard, status, nowEpochSeconds)
        }
    }

    fun invalidate(session: NextcloudSession, producer: AccountPrivateMemoryProducer?) {
        gate.mutate(session.accountId.storageKey, producer) { entries.remove(session.accountId) }
    }

    internal fun purgeRetiredAccount(accountStorageKey: String) {
        entries.keys.removeAll { account -> account.storageKey == accountStorageKey }
    }
}

internal val sharedDashboardStatusMemoryCache =
    DashboardStatusMemoryCache(gate = sharedAccountPrivateMemoryGate)
