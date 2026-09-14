package dev.obiente.nextcloudnative.app

private const val MAXIMUM_RETAINED_DECK_ACCOUNTS = 4

internal data class DeckWorkspaceMemorySnapshot(
    val state: DeckWorkspaceState,
    val loadedBoards: List<DeckBoard>,
    val capabilities: DeckCapabilities?,
    val activeRoute: DeckReadRoutePlan?,
    val requestedBoard: DeckBoard?,
    val requestedBoardId: Long?,
    val requestedCardId: Long?,
)

internal object DeckWorkspaceMemoryCache {
    private val gate = sharedAccountPrivateMemoryGate
    private val entries = linkedMapOf<String, DeckWorkspaceMemorySnapshot>()

    fun producer(session: NextcloudSession): AccountPrivateMemoryProducer? = gate.producer(key(session))

    fun get(session: NextcloudSession): DeckWorkspaceMemorySnapshot? = gate.read(key(session), null) {
        val key = key(session)
        entries.remove(key)?.also { entries[key] = it }
    }

    fun store(
        session: NextcloudSession,
        value: DeckWorkspaceMemorySnapshot,
        producer: AccountPrivateMemoryProducer?,
    ) {
        gate.mutate(key(session), producer) {
            val key = key(session)
            entries.remove(key)
            entries[key] = value
            while (entries.size > MAXIMUM_RETAINED_DECK_ACCOUNTS) entries.remove(entries.keys.first())
        }
    }

    internal fun purgeRetiredAccount(accountStorageKey: String) {
        entries.remove(accountStorageKey)
    }

    private fun key(session: NextcloudSession): String = session.accountId.storageKey
}
