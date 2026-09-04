package dev.obiente.nextcloudnative.app

internal fun DesktopFileSyncStore.removeDesktopFileSyncAccountPairs(accountId: String) {
    require(accountId.isNotBlank() && accountId.length <= 256)
    withExclusiveAccess {
        val current = load()
        val removed = current.coordinator.pairs.filter { pair -> pair.accountId == accountId }
        val retainedRootIds = current.coordinator.pairs.asSequence()
            .filterNot { pair -> pair.accountId == accountId }
            .mapTo(mutableSetOf(), FileSyncPair::localRootId)
        removed.forEach { pair ->
            deletePair(
                pairId = pair.id,
                rootId = pair.localRootId,
                deleteRoot = pair.localRootId !in retainedRootIds,
            )
        }
    }
}
