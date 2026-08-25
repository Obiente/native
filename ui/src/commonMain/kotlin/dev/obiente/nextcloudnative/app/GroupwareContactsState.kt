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
    private val entries = linkedMapOf<String, ContactsLoadState.Ready>()

    fun get(session: NextcloudSession, userId: String): ContactsLoadState.Ready? {
        val key = "${session.serverUrl.trimEnd('/')}\n${session.loginName}\n$userId"
        return entries.remove(key)?.also { entries[key] = it }
    }

    fun store(session: NextcloudSession, userId: String, value: ContactsLoadState.Ready) {
        val key = "${session.serverUrl.trimEnd('/')}\n${session.loginName}\n$userId"
        entries.remove(key)
        entries[key] = value
        while (entries.size > MAXIMUM_RETAINED_CONTACT_ACCOUNTS) entries.remove(entries.keys.first())
    }
}

private const val MAXIMUM_RETAINED_CONTACT_ACCOUNTS = 4
