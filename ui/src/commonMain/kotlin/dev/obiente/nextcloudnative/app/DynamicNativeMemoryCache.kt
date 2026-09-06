package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.runtime.NativeRecord
import dev.obiente.nextcloudnative.nativeui.model.NativeComponent
import dev.obiente.nextcloudnative.nativeui.model.sameDynamicResourceAs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Bounded process-local cache for adaptive app contracts and screen results.
 *
 * It intentionally excludes app passwords and request bodies. Its short freshness window survives
 * Android activity recreation and desktop recomposition without turning this into offline storage.
 */
internal class DynamicNativeMemoryCache(
    private val maximumScreens: Int = 48,
    private val freshFor: Duration = 5.minutes,
    private val discoveryFreshFor: Duration = 5.minutes,
    private val discoveryFailureCooldown: Duration = 45.seconds,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private data class ScreenEntry(
        val snapshot: DynamicScreenSnapshot,
        val storedAt: TimeMark,
    )
    private data class DiscoveryEntry(
        val discovery: DynamicDescriptorDiscovery,
        val storedAt: TimeMark,
    )

    private val lock = DynamicNativeMemoryCacheLock()
    private val discoveryMetadata = linkedMapOf<DynamicDiscoveryCacheKey, DiscoveryEntry>()
    private val discoveryFailures = linkedMapOf<DynamicDiscoveryCacheKey, TimeMark>()
    private val screens = linkedMapOf<DynamicScreenCacheKey, ScreenEntry>()
    private val closedAccounts = mutableSetOf<String>()
    private val accountIncarnations = mutableMapOf<String, Long>()

    fun producer(session: NextcloudSession): DynamicNativeMemoryCacheProducer? =
        producer(session.dynamicAccountKey())

    fun producer(key: DynamicScreenCacheKey): DynamicNativeMemoryCacheProducer? = producer(key.account)

    fun discovery(
        session: NextcloudSession,
        appId: String,
        freshOnly: Boolean = false,
        allowStaleDiscovery: Boolean = true,
    ): DynamicDescriptorDiscovery? = lock.withLock {
        val key = DynamicDiscoveryCacheKey(session.dynamicAccountKey(), appId)
        if (key.account in closedAccounts) return@withLock null
        val entry = discoveryMetadata.touch(key) ?: return@withLock null
        if (!allowStaleDiscovery && freshOnly && entry.storedAt.elapsedNow() > discoveryFreshFor) {
            return@withLock null
        }
        if (freshOnly && entry.storedAt.elapsedNow() > discoveryFreshFor) return@withLock null
        entry.discovery
    }

    fun isDiscoveryFresh(
        session: NextcloudSession,
        appId: String,
    ): Boolean = lock.withLock {
        val key = DynamicDiscoveryCacheKey(session.dynamicAccountKey(), appId)
        if (key.account in closedAccounts) return@withLock false
        discoveryMetadata[key]?.let { entry ->
            entry.discovery.versionStatus == DynamicContractVersionStatus.VerifiedCurrent &&
                entry.storedAt.elapsedNow() <= discoveryFreshFor
        } == true
    }

    fun shouldRetryDiscovery(session: NextcloudSession, appId: String): Boolean = lock.withLock {
        val key = DynamicDiscoveryCacheKey(session.dynamicAccountKey(), appId)
        if (key.account in closedAccounts) return@withLock false
        val failure = discoveryFailures[key] ?: return@withLock true
        if (failure.elapsedNow() >= discoveryFailureCooldown) {
            discoveryFailures.remove(key)
            true
        } else {
            false
        }
    }

    fun storeDiscovery(
        session: NextcloudSession,
        appId: String,
        discovery: DynamicDescriptorDiscovery,
        producer: DynamicNativeMemoryCacheProducer?,
    ) = lock.withLock {
        val key = DynamicDiscoveryCacheKey(session.dynamicAccountKey(), appId)
        val currentProducer = producer ?: return@withLock
        require(currentProducer.accountStorageKey == key.account) { "The dynamic cache producer belongs to another account." }
        if (!accepts(currentProducer)) return@withLock
        discoveryMetadata.remove(key)
        discoveryMetadata[key] = DiscoveryEntry(discovery = discovery, storedAt = timeSource.markNow())
        while (discoveryMetadata.size > MAXIMUM_DISCOVERIES) discoveryMetadata.remove(discoveryMetadata.keys.first())
        discoveryFailures.remove(key)
    }

    fun screen(key: DynamicScreenCacheKey, freshOnly: Boolean = false): DynamicScreenSnapshot? = lock.withLock {
        if (!key.cacheable || key.account in closedAccounts) return@withLock null
        val entry = screens.touch(key) ?: return@withLock null
        if (freshOnly && entry.storedAt.elapsedNow() > freshFor) return@withLock null
        entry.snapshot
    }

    fun markDiscoveryFailure(
        session: NextcloudSession,
        appId: String,
        producer: DynamicNativeMemoryCacheProducer?,
    ) = lock.withLock {
        val key = DynamicDiscoveryCacheKey(session.dynamicAccountKey(), appId)
        val currentProducer = producer ?: return@withLock
        require(currentProducer.accountStorageKey == key.account) { "The dynamic cache producer belongs to another account." }
        if (!accepts(currentProducer)) return@withLock
        discoveryFailures.remove(key)
        discoveryFailures[key] = timeSource.markNow()
        while (discoveryFailures.size > MAXIMUM_DISCOVERIES) discoveryFailures.remove(discoveryFailures.keys.first())
    }

    fun storeScreen(
        key: DynamicScreenCacheKey,
        snapshot: DynamicScreenSnapshot,
        producer: DynamicNativeMemoryCacheProducer?,
    ) = lock.withLock {
        val currentProducer = producer ?: return@withLock
        require(currentProducer.accountStorageKey == key.account) { "The dynamic cache producer belongs to another account." }
        if (!key.cacheable || !accepts(currentProducer)) return@withLock
        screens.remove(key)
        screens[key] = ScreenEntry(snapshot.bounded(), timeSource.markNow())
        while (screens.size > maximumScreens) screens.remove(screens.keys.first())
    }

    fun invalidateScreens(session: NextcloudSession, appId: String) = lock.withLock {
        val account = session.dynamicAccountKey()
        screens.keys.removeAll { key ->
            key.account == account && key.appId == appId
        }
    }

    /** Purges process-local state and rejects stale completions until exact credential activation. */
    fun retireAccount(accountStorageKey: String) = lock.withLock {
        if (closedAccounts.add(accountStorageKey)) {
            accountIncarnations[accountStorageKey] = (accountIncarnations[accountStorageKey] ?: 0L) + 1L
        }
        discoveryMetadata.keys.removeAll { key -> key.account == accountStorageKey }
        discoveryFailures.keys.removeAll { key -> key.account == accountStorageKey }
        screens.keys.removeAll { key -> key.account == accountStorageKey }
    }

    /** Reopens an empty account cache only after the platform has persisted its exact credentials. */
    fun activateAccount(accountStorageKey: String) = lock.withLock {
        closedAccounts.remove(accountStorageKey)
    }

    private fun producer(accountStorageKey: String): DynamicNativeMemoryCacheProducer? = lock.withLock {
        if (accountStorageKey in closedAccounts) return@withLock null
        DynamicNativeMemoryCacheProducer(accountStorageKey, accountIncarnations[accountStorageKey] ?: 0L)
    }

    private fun accepts(producer: DynamicNativeMemoryCacheProducer): Boolean =
        producer.accountStorageKey !in closedAccounts &&
            (accountIncarnations[producer.accountStorageKey] ?: 0L) == producer.incarnation
    private fun DynamicScreenSnapshot.bounded(): DynamicScreenSnapshot {
        val boundedRelated = relatedRecords.entries
            .take(MAXIMUM_RELATED_RESOURCES)
            .associate { (resourceId, records) -> resourceId to records.take(MAXIMUM_RECORDS_PER_RESOURCE) }
        return copy(
            records = records.take(MAXIMUM_RECORDS_PER_RESOURCE),
            relatedRecords = boundedRelated,
        )
    }

    private fun <K, V> MutableMap<K, V>.touch(key: K): V? {
        val value = remove(key) ?: return null
        put(key, value)
        return value
    }

    private companion object {
        const val MAXIMUM_DISCOVERIES = 32
        const val MAXIMUM_RELATED_RESOURCES = 8
        const val MAXIMUM_RECORDS_PER_RESOURCE = 500
    }
}

data class DynamicNativeMemoryCacheProducer(
    val accountStorageKey: String,
    val incarnation: Long,
)

internal data class DynamicDiscoveryCacheKey(
    val account: String,
    val appId: String,
)

internal data class DynamicScreenCacheKey(
    val account: String,
    val appId: String,
    val viewId: String,
    val selectedRecordId: String?,
    val selectedRecordResourceId: String?,
    /** Only non-secret relationship identifiers needed to distinguish a nested collection. */
    val selectedRecordScope: Map<String, String>,
    val parameterValues: Map<String, String>,
    /** Some sparse semantic records do not safely identify their account scope. */
    val cacheable: Boolean = true,
)

/**
 * Compose loader identity for a selected dynamic record. This deliberately includes the resource
 * and relation scope because server IDs such as Inbox may overlap between Mail accounts.
 */
internal data class DynamicScreenSelectionIdentity(
    val resourceId: String?,
    val recordId: String?,
    val recordScope: Map<String, String>,
)

/**
 * Immutable identity for a pagination request. A late page may only update the screen, cache, or
 * error state while this still matches the active dynamic selection.
 */
internal data class DynamicPaginationRequestIdentity(
    val account: String,
    val appId: String,
    val viewId: String,
    val resourceId: String,
    val selection: DynamicScreenSelectionIdentity,
    val pathParameters: Map<String, String>,
    val cacheKey: DynamicScreenCacheKey,
)

internal fun dynamicScreenSelectionIdentity(
    resourceId: String?,
    recordId: String?,
    recordScope: Map<String, String>,
): DynamicScreenSelectionIdentity = DynamicScreenSelectionIdentity(
    resourceId = resourceId,
    recordId = recordId,
    recordScope = recordScope.toSortedMap(),
)

internal fun dynamicPaginationRequestIdentity(
    session: NextcloudSession,
    appId: String,
    viewId: String,
    resourceId: String,
    selection: DynamicScreenSelectionIdentity,
    pathParameters: Map<String, String>,
    cacheable: Boolean,
): DynamicPaginationRequestIdentity {
    val cacheKey = dynamicScreenCacheKey(
        session = session,
        appId = appId,
        viewId = viewId,
        selectedRecordId = selection.recordId,
        parameterValues = pathParameters,
        selectedRecordResourceId = selection.resourceId,
        selectedRecordScope = selection.recordScope,
        cacheable = cacheable,
    )
    return DynamicPaginationRequestIdentity(
        account = cacheKey.account,
        appId = appId,
        viewId = viewId,
        resourceId = resourceId,
        selection = selection,
        pathParameters = pathParameters.toSortedMap(),
        cacheKey = cacheKey,
    )
}

internal fun DynamicPaginationRequestIdentity.isCurrentDynamicPaginationRequest(
    active: DynamicPaginationRequestIdentity?,
): Boolean = this == active

internal data class DynamicScreenSnapshot(
    val records: List<NativeRecord>,
    val relatedRecords: Map<String, List<NativeRecord>>,
    val pagination: DynamicPaginationCheckpoint? = null,
)

internal data class DynamicPaginationCheckpoint(
    val nextPageNumber: Int,
    val nextRequestValue: String,
)

internal fun dynamicScreenCacheKey(
    session: NextcloudSession,
    appId: String,
    viewId: String,
    selectedRecordId: String?,
    parameterValues: Map<String, String>,
    selectedRecordResourceId: String? = null,
    selectedRecordScope: Map<String, String> = emptyMap(),
    cacheable: Boolean = true,
): DynamicScreenCacheKey = DynamicScreenCacheKey(
    account = session.dynamicAccountKey(),
    appId = appId,
    viewId = viewId,
    selectedRecordId = selectedRecordId,
    selectedRecordResourceId = selectedRecordResourceId,
    selectedRecordScope = selectedRecordScope.toSortedMap(),
    parameterValues = parameterValues.toSortedMap(),
    cacheable = cacheable,
)

/**
 * A screen cache key needs the active parent relation as well as its display record ID. Mailbox
 * IDs may overlap between accounts, so omitting accountId can surface a prior mailbox snapshot.
 * Keep only relation identifiers; server content, email addresses, and credentials never enter a
 * cache key.
 */
internal fun NativeRecord.dynamicScreenCacheScope(): Map<String, String> = buildMap {
    put("recordId", id)
    fun addScopeFields(fields: Map<String, String?>) {
        fields.forEach { (key, value) ->
            val semantic = key.lowercase().filter(Char::isLetterOrDigit)
            if (semantic in DYNAMIC_SCREEN_SCOPE_RELATIONS) {
                value?.takeIf(String::isNotBlank)?.let { scopedValue -> put(semantic, scopedValue) }
            }
        }
    }
    addScopeFields(this@dynamicScreenCacheScope.values)
    addScopeFields(this@dynamicScreenCacheScope.bindingContext)
}

/**
 * A pagination merge must not collapse equal server IDs from different Mail accounts or folders.
 * The same non-secret relation scope used for screen caching keeps a generic collection stable.
 */
internal fun NativeRecord.dynamicPaginationRecordIdentity(resourceId: String): String = buildString {
    append(resourceId)
    append('\u0000')
    append(id)
    dynamicScreenCacheScope().toSortedMap().forEach { (key, value) ->
        append('\u0000')
        append(key)
        append('=')
        append(value)
    }
}

internal fun shouldShowDynamicRecordFallbackDetail(
    viewResourceId: String,
    viewComponent: NativeComponent,
    selectedRecord: NativeRecord?,
    selectedRecordResourceId: String?,
): Boolean = selectedRecord != null &&
    viewComponent != NativeComponent.detail &&
    viewComponent != NativeComponent.form &&
    selectedRecordResourceId?.sameDynamicResourceAs(viewResourceId) == true

private val DYNAMIC_SCREEN_SCOPE_RELATIONS = setOf(
    "accountid",
    "mailaccountid",
    "mailboxid",
    "folderid",
    "mailfolderid",
    "parentid",
    "parentmailboxid",
    "parentfolderid",
    "databaseid",
    "messageid",
    "threadid",
)

private fun NextcloudSession.dynamicAccountKey(): String = accountId.storageKey

internal val sharedDynamicNativeMemoryCache = DynamicNativeMemoryCache()

/** Compatibility entry point for callers that previously retired only the dynamic UI cache. */
object DynamicNativeMemoryAccountLifecycle {
    fun retireAccount(accountStorageKey: String) = AccountPrivateMemoryLifecycle.retireAccount(accountStorageKey)

    fun activateAccount(accountStorageKey: String) = AccountPrivateMemoryLifecycle.activateAccount(accountStorageKey)
}
