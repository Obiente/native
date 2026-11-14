package dev.obiente.nextcloudnative.app

private const val MAXIMUM_RETAINED_BOARD_WORKSPACES = 4

internal data class DeckWorkspaceMemorySnapshot(
    val state: DeckWorkspaceState,
    val loadedBoards: List<DeckBoard>,
    val requestedBoard: DeckBoard?,
    val requestedBoardId: Long?,
    val requestedCardId: Long?,
)

/** Retains a board workspace per account and board app so returning to it paints immediately. */
internal object DeckWorkspaceMemoryCache {
    private val gate = sharedAccountPrivateMemoryGate
    private val entries = linkedMapOf<String, DeckWorkspaceMemorySnapshot>()

    fun producer(session: NextcloudSession): AccountPrivateMemoryProducer? =
        gate.producer(session.accountId.storageKey)

    fun get(session: NextcloudSession, appId: String): DeckWorkspaceMemorySnapshot? =
        gate.read(session.accountId.storageKey, null) {
            val key = key(session, appId)
            entries.remove(key)?.also { entries[key] = it }
        }

    fun store(
        session: NextcloudSession,
        appId: String,
        value: DeckWorkspaceMemorySnapshot,
        producer: AccountPrivateMemoryProducer?,
    ) {
        gate.mutate(session.accountId.storageKey, producer) {
            val key = key(session, appId)
            entries.remove(key)
            entries[key] = value
            while (entries.size > MAXIMUM_RETAINED_BOARD_WORKSPACES) entries.remove(entries.keys.first())
        }
    }

    internal fun purgeRetiredAccount(accountStorageKey: String) {
        entries.keys.filter { it == accountStorageKey || it.startsWith("$accountStorageKey|") }
            .forEach(entries::remove)
    }

    private fun key(session: NextcloudSession, appId: String): String = "${session.accountId.storageKey}|$appId"
}
