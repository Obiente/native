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
    val parameterValues: Map<String, String>,
)

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
): DynamicScreenCacheKey = DynamicScreenCacheKey(
    account = session.dynamicAccountKey(),
    appId = appId,
    viewId = viewId,
    selectedRecordId = selectedRecordId,
    parameterValues = parameterValues.toSortedMap(),
)

private fun NextcloudSession.dynamicAccountKey(): String =
    serverUrl.trim().trimEnd('/').lowercase() + '\u0000' + loginName

internal val sharedDynamicNativeMemoryCache = DynamicNativeMemoryCache()
