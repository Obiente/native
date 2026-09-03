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
    private val entries = linkedMapOf<String, DeckWorkspaceMemorySnapshot>()

    fun get(session: NextcloudSession): DeckWorkspaceMemorySnapshot? {
        val key = key(session)
        return entries.remove(key)?.also { entries[key] = it }
    }

    fun store(session: NextcloudSession, value: DeckWorkspaceMemorySnapshot) {
        val key = key(session)
        entries.remove(key)
        entries[key] = value
        while (entries.size > MAXIMUM_RETAINED_DECK_ACCOUNTS) entries.remove(entries.keys.first())
    }

    private fun key(session: NextcloudSession): String =
        "${session.serverUrl.trimEnd('/')}\n${session.loginName}"
}
