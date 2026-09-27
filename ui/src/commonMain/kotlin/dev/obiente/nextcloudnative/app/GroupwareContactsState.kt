package dev.obiente.nextcloudnative.app

internal sealed interface ContactsLoadState {
    data object Loading : ContactsLoadState
    data class Ready(
        val addressBooks: List<GroupwareAddressBook>,
        val contacts: List<GroupwareContact>,
    ) : ContactsLoadState
    data class Error(val message: String) : ContactsLoadState
}

internal object ContactsWorkspaceMemoryCache {
    private val gate = sharedAccountPrivateMemoryGate
    private val entries = linkedMapOf<Pair<NextcloudAccountId, String>, ContactsLoadState.Ready>()

    fun producer(session: NextcloudSession): AccountPrivateMemoryProducer? = gate.producer(session.accountId.storageKey)

    fun get(session: NextcloudSession, userId: String): ContactsLoadState.Ready? =
        gate.read(session.accountId.storageKey, null) {
            val key = session.accountId to userId
            entries.remove(key)?.also { entries[key] = it }
        }

    fun store(
        session: NextcloudSession,
        userId: String,
        value: ContactsLoadState.Ready,
        producer: AccountPrivateMemoryProducer?,
    ) {
        gate.mutate(session.accountId.storageKey, producer) {
            val key = session.accountId to userId
            entries.remove(key)
            entries[key] = value
            while (entries.size > MAXIMUM_RETAINED_CONTACT_ACCOUNTS) entries.remove(entries.keys.first())
        }
    }

    internal fun purgeRetiredAccount(accountStorageKey: String) {
        entries.keys.removeAll { (account) -> account.storageKey == accountStorageKey }
    }
}

internal fun contactEditRequiresFullLoad(
    editing: Boolean,
    selectedContactHref: String?,
    loadedContactHref: String?,
): Boolean = editing && selectedContactHref != null && loadedContactHref != selectedContactHref

private const val MAXIMUM_RETAINED_CONTACT_ACCOUNTS = 4
