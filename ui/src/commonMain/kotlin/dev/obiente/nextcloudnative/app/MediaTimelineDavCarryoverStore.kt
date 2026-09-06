package dev.obiente.nextcloudnative.app

private const val DEFAULT_MEDIA_TIMELINE_CARRYOVER_ACCOUNT_LIMIT = 4
private const val DEFAULT_MEDIA_TIMELINE_CARRYOVER_CURSOR_LIMIT = 4

/**
 * Retains at most one already-fetched server page per active SearchDAV partition.
 *
 * The store is deliberately runtime-only. Its compact cursor remains sufficient for a stateless
 * retry when the process, account LRU, or refresh generation has discarded buffered records.
 */
class MediaTimelineDavCarryoverStore private constructor(
    private val maximumAccountScopes: Int = DEFAULT_MEDIA_TIMELINE_CARRYOVER_ACCOUNT_LIMIT,
    private val maximumCursorsPerAccount: Int = DEFAULT_MEDIA_TIMELINE_CARRYOVER_CURSOR_LIMIT,
    private val gate: AccountPrivateMemoryGate,
) {
    constructor(
        maximumAccountScopes: Int = DEFAULT_MEDIA_TIMELINE_CARRYOVER_ACCOUNT_LIMIT,
        maximumCursorsPerAccount: Int = DEFAULT_MEDIA_TIMELINE_CARRYOVER_CURSOR_LIMIT,
    ) : this(maximumAccountScopes, maximumCursorsPerAccount, AccountPrivateMemoryGate())

    internal constructor(gate: AccountPrivateMemoryGate) : this(
        DEFAULT_MEDIA_TIMELINE_CARRYOVER_ACCOUNT_LIMIT,
        DEFAULT_MEDIA_TIMELINE_CARRYOVER_CURSOR_LIMIT,
        gate,
    )

    private data class AccountScope(
        val accountId: NextcloudAccountId,
        val value: String,
    )

    private data class AccountState(
        val generation: Long,
        val continuations: LinkedHashMap<String, MediaTimelineDavCarryover>,
    )

    private val lock = DynamicNativeMemoryCacheLock()
    private val accounts = linkedMapOf<AccountScope, AccountState>()
    private var nextGeneration = 0L

    init {
        require(maximumAccountScopes > 0)
        require(maximumCursorsPerAccount > 0)
    }

    internal fun producer(accountId: NextcloudAccountId): AccountPrivateMemoryProducer? =
        gate.producer(accountId.storageKey)

    internal fun beginAccountGeneration(
        accountId: NextcloudAccountId,
        accountScope: String,
        producer: AccountPrivateMemoryProducer?,
    ): Long? = gate.read(accountId.storageKey, producer, null) {
        lock.withLock {
            requireMediaTimelineAccountScope(accountScope)
            val scope = AccountScope(accountId, accountScope)
            nextGeneration = if (nextGeneration == Long.MAX_VALUE) 1L else nextGeneration + 1L
            accounts.remove(scope)
            accounts[scope] = AccountState(nextGeneration, linkedMapOf())
            while (accounts.size > maximumAccountScopes) {
                accounts.remove(accounts.keys.first())
            }
            nextGeneration
        }
    }

    internal fun take(
        accountId: NextcloudAccountId,
        accountScope: String,
        generation: Long,
        cursor: PhotoTimelineCursor,
        producer: AccountPrivateMemoryProducer?,
    ): MediaTimelineDavCarryover? = gate.read(accountId.storageKey, producer, null) {
        lock.withLock {
            requireMediaTimelineAccountScope(accountScope)
            val scope = AccountScope(accountId, accountScope)
            val account = accounts[scope]?.takeIf { it.generation == generation }
                ?: return@withLock null
            val continuation = account.continuations.remove(cursor.value)
            accounts.remove(scope)
            accounts[scope] = account
            continuation
        }
    }

    internal fun put(
        accountId: NextcloudAccountId,
        accountScope: String,
        generation: Long,
        cursor: PhotoTimelineCursor,
        carryover: MediaTimelineDavCarryover,
        producer: AccountPrivateMemoryProducer?,
    ) {
        gate.mutate(accountId.storageKey, producer) {
            lock.withLock {
                requireMediaTimelineAccountScope(accountScope)
                val scope = AccountScope(accountId, accountScope)
                val account = accounts[scope]?.takeIf { it.generation == generation }
                    ?: return@withLock
                account.continuations.remove(cursor.value)
                account.continuations[cursor.value] = carryover
                while (account.continuations.size > maximumCursorsPerAccount) {
                    account.continuations.remove(account.continuations.keys.first())
                }
                accounts.remove(scope)
                accounts[scope] = account
            }
        }
    }

    internal fun purgeRetiredAccount(accountStorageKey: String) = lock.withLock {
        accounts.entries.removeAll { (scope, _) -> scope.accountId.storageKey == accountStorageKey }
    }
}

val sharedMediaTimelineDavCarryoverStore = MediaTimelineDavCarryoverStore(sharedAccountPrivateMemoryGate)

internal fun requireMediaTimelineAccountScope(accountScope: String) {
    require(
        accountScope.isNotBlank() &&
            accountScope.length <= 256 &&
            accountScope.none(Char::isISOControl),
    ) {
        "The photo timeline carryover scope is invalid."
    }
}
