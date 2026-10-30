package dev.obiente.nextcloudnative.app

internal data class CachedNotificationsInbox(
    val notifications: List<NextcloudNotification>,
    val truncated: Boolean,
)

/**
 * Account-private process cache for the last notifications read from the server. It lets the
 * inbox and its badge paint immediately when an account shell is recreated, and is purged when
 * the account is removed. It never stores credentials.
 */
internal class NotificationsInboxMemoryCache(
    private val gate: AccountPrivateMemoryGate = AccountPrivateMemoryGate(),
) {
    private val entries = mutableMapOf<NextcloudAccountId, CachedNotificationsInbox>()

    fun producer(accountId: NextcloudAccountId): AccountPrivateMemoryProducer? = gate.producer(accountId.storageKey)

    fun get(accountId: NextcloudAccountId, producer: AccountPrivateMemoryProducer?): CachedNotificationsInbox? =
        gate.read(accountId.storageKey, producer, null) { entries[accountId] }

    fun store(
        accountId: NextcloudAccountId,
        inbox: CachedNotificationsInbox,
        producer: AccountPrivateMemoryProducer?,
    ) {
        gate.mutate(accountId.storageKey, producer) {
            entries[accountId] = inbox.copy(notifications = inbox.notifications.take(MAX_NOTIFICATION_ENTRIES))
        }
    }

    internal fun purgeRetiredAccount(accountStorageKey: String) {
        entries.keys.removeAll { account -> account.storageKey == accountStorageKey }
    }
}

internal val sharedNotificationsInboxMemoryCache =
    NotificationsInboxMemoryCache(gate = sharedAccountPrivateMemoryGate)
