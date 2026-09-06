package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.runtime.NativeRecord
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

    private val discoveries = linkedMapOf<DynamicDiscoveryCacheKey, DynamicDescriptorDiscovery>()
    private val discoveryMetadata = linkedMapOf<DynamicDiscoveryCacheKey, DiscoveryEntry>()
    private val discoveryFailures = linkedMapOf<DynamicDiscoveryCacheKey, TimeMark>()
    private val screens = linkedMapOf<DynamicScreenCacheKey, ScreenEntry>()

    fun discovery(
        session: NextcloudSession,
        appId: String,
        freshOnly: Boolean = false,
        allowStaleDiscovery: Boolean = true,
    ): DynamicDescriptorDiscovery? {
        val key = DynamicDiscoveryCacheKey(session.dynamicAccountKey(), appId)
        val entry = discoveryMetadata.touch(key) ?: return null
        if (!allowStaleDiscovery && freshOnly && entry.storedAt.elapsedNow() > discoveryFreshFor) return null
        if (freshOnly && entry.storedAt.elapsedNow() > discoveryFreshFor) return null
        return entry.discovery
    }

    fun isDiscoveryFresh(
        session: NextcloudSession,
        appId: String,
    ): Boolean = discoveryMetadata[DynamicDiscoveryCacheKey(session.dynamicAccountKey(), appId)]
        ?.takeIf { entry ->
            entry.discovery.versionStatus == DynamicContractVersionStatus.VerifiedCurrent &&
                entry.storedAt.elapsedNow() <= discoveryFreshFor
        } != null

    fun shouldRetryDiscovery(session: NextcloudSession, appId: String): Boolean {
        val key = DynamicDiscoveryCacheKey(session.dynamicAccountKey(), appId)
        val failure = discoveryFailures[key] ?: return true
        return if (failure.elapsedNow() >= discoveryFailureCooldown) {
            discoveryFailures.remove(key)
            true
        } else {
            false
        }
    }

    fun storeDiscovery(session: NextcloudSession, appId: String, discovery: DynamicDescriptorDiscovery) {
        val key = DynamicDiscoveryCacheKey(session.dynamicAccountKey(), appId)
        discoveryMetadata.remove(key)
        discoveryMetadata[key] = DiscoveryEntry(discovery = discovery, storedAt = timeSource.markNow())
        discoveries[key] = discovery
        while (discoveryMetadata.size > MAXIMUM_DISCOVERIES) discoveryMetadata.remove(discoveryMetadata.keys.first())
        while (discoveries.size > MAXIMUM_DISCOVERIES) discoveries.remove(discoveries.keys.first())
        discoveryFailures.remove(key)
    }

    fun screen(key: DynamicScreenCacheKey, freshOnly: Boolean = false): DynamicScreenSnapshot? {
        if (!key.cacheable) return null
        val entry = screens.touch(key) ?: return null
        if (freshOnly && entry.storedAt.elapsedNow() > freshFor) return null
        return entry.snapshot
    }

    fun markDiscoveryFailure(session: NextcloudSession, appId: String) {
        val key = DynamicDiscoveryCacheKey(session.dynamicAccountKey(), appId)
        discoveryFailures.remove(key)
        discoveryFailures[key] = timeSource.markNow()
        while (discoveries.size > MAXIMUM_DISCOVERIES) discoveries.remove(discoveries.keys.first())
        while (discoveryFailures.size > MAXIMUM_DISCOVERIES) discoveryFailures.remove(discoveryFailures.keys.first())
    }

    fun storeScreen(key: DynamicScreenCacheKey, snapshot: DynamicScreenSnapshot) {
        if (!key.cacheable) return
        screens.remove(key)
        screens[key] = ScreenEntry(snapshot.bounded(), timeSource.markNow())
        while (screens.size > maximumScreens) screens.remove(screens.keys.first())
    }

    fun invalidateScreens(session: NextcloudSession, appId: String) {
        val account = session.dynamicAccountKey()
        screens.keys.removeAll { key ->
            key.account == account && key.appId == appId
        }
    }

    fun removeAccount(accountStorageKey: String) {
        discoveries.keys.removeAll { key -> key.account == accountStorageKey }
        discoveryMetadata.keys.removeAll { key -> key.account == accountStorageKey }
        discoveryFailures.keys.removeAll { key -> key.account == accountStorageKey }
        screens.keys.removeAll { key -> key.account == accountStorageKey }
    }

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
