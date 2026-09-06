package dev.obiente.nextcloudnative.app

internal class MemoriesMainTimelineIndexCache(
    private val gate: AccountPrivateMemoryGate,
) {
    private data class AccountScope(
        val accountId: NextcloudAccountId,
        val value: String,
    )

    private data class AccountState(
        val index: MemoriesMainTimelineDayIndex,
        val sourceGeneration: Long,
        var memoriesActive: Boolean,
    )

    private val lock = DynamicNativeMemoryCacheLock()
    private val accounts = linkedMapOf<AccountScope, AccountState>()
    private val pendingLoads = mutableMapOf<AccountScope, Long>()
    private var sourceGeneration = 0L
    private var nextLoadId = 0L

    internal fun producer(accountId: NextcloudAccountId): AccountPrivateMemoryProducer? =
        gate.producer(accountId.storageKey)

    suspend fun load(
        accountId: NextcloudAccountId,
        accountScope: String,
        forceRefresh: Boolean,
        producer: AccountPrivateMemoryProducer?,
        fetch: suspend () -> MemoriesMainTimelineLoadResult<MemoriesMainTimelineDayIndex>,
    ): MemoriesMainTimelineLoadResult<MemoriesMainTimelineCachedIndex> {
        val scope = AccountScope(accountId, accountScope)
        var cached: MemoriesMainTimelineCachedIndex? = null
        var loadId: Long? = null
        val started = gate.mutate(accountId.storageKey, producer) {
            lock.withLock {
                if (!forceRefresh) {
                    accounts[scope]?.let { state ->
                        cached = MemoriesMainTimelineCachedIndex(
                            state.index,
                            state.sourceGeneration,
                        )
                    }
                }
                if (cached == null) {
                    nextLoadId = nextMemoriesTimelineSourceGeneration(nextLoadId)
                    loadId = nextLoadId
                    pendingLoads[scope] = nextLoadId
                }
            }
        }
        check(started) { "The Memories timeline account is no longer available." }
        cached?.let {
            return MemoriesMainTimelineLoadResult.Loaded(it)
        }
        val loaded = try {
            fetch()
        } catch (failure: Throwable) {
            gate.mutate(accountId.storageKey, producer) {
                lock.withLock {
                    if (pendingLoads[scope] == loadId) pendingLoads.remove(scope)
                }
            }
            throw failure
        }
        var published: MemoriesMainTimelineLoadResult<MemoriesMainTimelineCachedIndex>? = null
        val accepted = gate.mutate(accountId.storageKey, producer) {
            lock.withLock {
                if (pendingLoads[scope] != loadId) return@withLock
                pendingLoads.remove(scope)
                sourceGeneration = nextMemoriesTimelineSourceGeneration(sourceGeneration)
                when (loaded) {
                    is MemoriesMainTimelineLoadResult.Loaded -> {
                        val state = AccountState(
                            index = loaded.value,
                            sourceGeneration = sourceGeneration,
                            memoriesActive = false,
                        )
                        accounts.remove(scope)
                        accounts[scope] = state
                        published = MemoriesMainTimelineLoadResult.Loaded(
                            MemoriesMainTimelineCachedIndex(state.index, state.sourceGeneration),
                        )
                    }

                    is MemoriesMainTimelineLoadResult.UseFallback -> {
                        accounts.remove(scope)
                        published = loaded
                    }
                }
            }
        }
        check(accepted) {
            "The Memories timeline account changed while loading the index; refresh the timeline."
        }
        return checkNotNull(published) {
            "The Memories timeline index changed while loading; refresh the timeline."
        }
    }

    fun markMemoriesActive(
        accountId: NextcloudAccountId,
        accountScope: String,
        sourceGeneration: Long,
        producer: AccountPrivateMemoryProducer?,
    ): Boolean = gate.read(accountId.storageKey, producer, false) {
        lock.withLock {
            val state = accounts[AccountScope(accountId, accountScope)]
                ?.takeIf { it.sourceGeneration == sourceGeneration }
                ?: return@withLock false
            state.memoriesActive = true
            true
        }
    }

    fun markFallbackActive(
        accountId: NextcloudAccountId,
        accountScope: String,
        producer: AccountPrivateMemoryProducer?,
    ) = gate.read(accountId.storageKey, producer, Unit) {
        lock.withLock {
            accounts[AccountScope(accountId, accountScope)]?.memoriesActive = false
        }
    }

    fun activeMemoriesIndex(
        accountId: NextcloudAccountId,
        accountScope: String,
        sourceGeneration: Long? = null,
        producer: AccountPrivateMemoryProducer? = producer(accountId),
    ): MemoriesMainTimelineCachedIndex? = gate.read(accountId.storageKey, producer, null) {
        lock.withLock {
            val state = accounts[AccountScope(accountId, accountScope)]
                ?.takeIf { current ->
                    current.memoriesActive &&
                        (sourceGeneration == null || sourceGeneration == current.sourceGeneration)
                }
                ?: return@withLock null
            MemoriesMainTimelineCachedIndex(state.index, state.sourceGeneration)
        }
    }

    fun matchesActiveMemoriesIndex(
        accountId: NextcloudAccountId,
        accountScope: String,
        sourceGeneration: Long,
        producer: AccountPrivateMemoryProducer?,
    ): Boolean = activeMemoriesIndex(
        accountId = accountId,
        accountScope = accountScope,
        sourceGeneration = sourceGeneration,
        producer = producer,
    ) != null

    internal fun purgeRetiredAccount(accountStorageKey: String) = lock.withLock {
        accounts.entries.removeAll { (scope, _) -> scope.accountId.storageKey == accountStorageKey }
        pendingLoads.keys.removeAll { scope -> scope.accountId.storageKey == accountStorageKey }
    }
}

internal val sharedMemoriesMainTimelineIndexCache =
    MemoriesMainTimelineIndexCache(sharedAccountPrivateMemoryGate)

internal data class MemoriesMainTimelineCachedIndex(
    val index: MemoriesMainTimelineDayIndex,
    val sourceGeneration: Long,
)

private fun nextMemoriesTimelineSourceGeneration(current: Long): Long =
    if (current == Long.MAX_VALUE) 1L else current + 1L
