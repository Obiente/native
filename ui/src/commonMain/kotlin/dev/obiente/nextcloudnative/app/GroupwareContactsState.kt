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
    private val entries = linkedMapOf<Pair<NextcloudAccountId, String>, ContactsLoadState.Ready>()

    fun get(session: NextcloudSession, userId: String): ContactsLoadState.Ready? {
        val key = session.accountId to userId
        return entries.remove(key)?.also { entries[key] = it }
    }

    fun store(session: NextcloudSession, userId: String, value: ContactsLoadState.Ready) {
        val key = session.accountId to userId
        entries.remove(key)
        entries[key] = value
        while (entries.size > MAXIMUM_RETAINED_CONTACT_ACCOUNTS) entries.remove(entries.keys.first())
    }

    fun removeAccount(accountStorageKey: String) {
        entries.keys.removeAll { (account) -> account.storageKey == accountStorageKey }
    }
}

internal fun contactEditRequiresFullLoad(
    editing: Boolean,
    selectedContactHref: String?,
    loadedContactHref: String?,
): Boolean = editing && selectedContactHref != null && loadedContactHref != selectedContactHref

private const val MAXIMUM_RETAINED_CONTACT_ACCOUNTS = 4
