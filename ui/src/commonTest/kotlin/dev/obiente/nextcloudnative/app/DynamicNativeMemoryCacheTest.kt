package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.AppIdentity
import dev.obiente.nextcloudnative.nativeui.model.DYNAMIC_APP_DESCRIPTOR_VERSION
import dev.obiente.nextcloudnative.nativeui.model.DynamicAppDescriptor
import dev.obiente.nextcloudnative.nativeui.model.EndpointPolicy
import dev.obiente.nextcloudnative.nativeui.runtime.NativeRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TestTimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

class DynamicNativeMemoryCacheTest {
    private val session = NextcloudSession("https://cloud.example.test", "alice", "never-cache-this")

    @Test
    fun `screen key is stable across app password rotation and parameter order`() {
        val first = dynamicScreenCacheKey(
            session,
            appId = "mail",
            viewId = "messages.list",
            selectedRecordId = "inbox",
            parameterValues = mapOf("mailboxId" to "8", "filter" to "unread"),
        )
        val second = dynamicScreenCacheKey(
            session.copy(appPassword = "new-password"),
            appId = "mail",
            viewId = "messages.list",
            selectedRecordId = "inbox",
            parameterValues = mapOf("filter" to "unread", "mailboxId" to "8"),
        )

        assertEquals(first, second)
        assertNull(first.account.takeIf { it.contains("never-cache-this") })
    }

    @Test
    fun `screen cache distinguishes mailbox snapshots with overlapping mailbox ids`() {
        val personal = NativeRecord(
            id = "inbox",
            values = mapOf("accountId" to "personal", "mailboxId" to "inbox"),
        )
        val work = personal.copy(values = personal.values + ("accountId" to "work"))

        val personalKey = dynamicScreenCacheKey(
            session = session,
            appId = "mail",
            viewId = "messages.list",
            selectedRecordId = personal.id,
            parameterValues = emptyMap(),
            selectedRecordResourceId = "mailboxes",
            selectedRecordScope = personal.dynamicScreenCacheScope(),
        )
        val workKey = dynamicScreenCacheKey(
            session = session,
            appId = "mail",
            viewId = "messages.list",
            selectedRecordId = work.id,
            parameterValues = emptyMap(),
            selectedRecordResourceId = "mailboxes",
            selectedRecordScope = work.dynamicScreenCacheScope(),
        )

        assertNotEquals(personalKey, workKey)
        assertNotEquals(
            dynamicScreenSelectionIdentity(
                resourceId = "mailboxes",
                recordId = personal.id,
                recordScope = personal.dynamicScreenCacheScope(),
            ),
            dynamicScreenSelectionIdentity(
                resourceId = "mailboxes",
                recordId = work.id,
                recordScope = work.dynamicScreenCacheScope(),
            ),
        )
    }

    @Test
    fun `pagination completion is rejected after a Mail account selection changes`() {
        val personal = NativeRecord(
            id = "inbox",
            values = mapOf("accountId" to "personal", "mailboxId" to "inbox"),
        )
        val work = personal.copy(values = personal.values + ("accountId" to "work"))
        fun identity(record: NativeRecord) = dynamicPaginationRequestIdentity(
            session = session,
            appId = "mail",
            viewId = "messages.list",
            resourceId = "messages",
            selection = dynamicScreenSelectionIdentity(
                resourceId = "mailboxes",
                recordId = record.id,
                recordScope = record.dynamicScreenCacheScope(),
            ),
            pathParameters = mapOf("mailboxId" to record.id),
            cacheable = true,
        )

        val outstandingPersonalPage = identity(personal)
        val activeWorkSelection = identity(work)

        assertFalse(outstandingPersonalPage.isCurrentDynamicPaginationRequest(activeWorkSelection))
        assertNotEquals(
            personal.dynamicPaginationRecordIdentity("messages"),
            work.dynamicPaginationRecordIdentity("messages"),
        )
    }

    @Test
    fun `uncacheable sparse selection never stores or returns a screen`() {
        val cache = DynamicNativeMemoryCache()
        val key = dynamicScreenCacheKey(
            session = session,
            appId = "mail",
            viewId = "messages.list",
            selectedRecordId = "inbox",
            parameterValues = emptyMap(),
            selectedRecordResourceId = "mailboxes",
            cacheable = false,
        )

        cache.storeScreen(
            key,
            DynamicScreenSnapshot(listOf(NativeRecord("stale", emptyMap())), emptyMap()),
        )

        assertNull(cache.screen(key))
    }

    @Test
    fun `screen cache is bounded and isolated by account`() {
        val cache = DynamicNativeMemoryCache(maximumScreens = 1)
        val firstKey = dynamicScreenCacheKey(session, "mail", "messages.list", null, emptyMap())
        val otherKey = dynamicScreenCacheKey(
            session.copy(loginName = "bob"),
            "music",
            "albums.list",
            null,
            emptyMap(),
        )
        cache.storeScreen(
            firstKey,
            DynamicScreenSnapshot(listOf(NativeRecord("1", mapOf("id" to "1"))), emptyMap()),
        )
        cache.storeScreen(
            otherKey,
            DynamicScreenSnapshot(listOf(NativeRecord("2", mapOf("id" to "2"))), emptyMap()),
        )

        assertNull(cache.screen(firstKey))
        assertEquals("2", cache.screen(otherKey)?.records?.single()?.id)
    }

    @Test
    fun `screen remains fresh while navigating between apps but eventually revalidates`() {
        val clock = TestTimeSource()
        val cache = DynamicNativeMemoryCache(freshFor = 5.minutes, timeSource = clock)
        val key = dynamicScreenCacheKey(session, "mail", "messages.list", null, emptyMap())
        cache.storeScreen(
            key,
            DynamicScreenSnapshot(listOf(NativeRecord("1", mapOf("id" to "1"))), emptyMap()),
        )

        clock += 4.minutes
        assertEquals("1", cache.screen(key, freshOnly = true)?.records?.single()?.id)

        clock += 2.minutes
        assertNull(cache.screen(key, freshOnly = true))
        assertEquals("1", cache.screen(key)?.records?.single()?.id)
    }

    @Test
    fun `last known contract remains available but is never considered fresh`() {
        val cache = DynamicNativeMemoryCache()
        val discovery = DynamicDescriptorDiscovery(
            descriptor = DynamicAppDescriptor(
                descriptorVersion = DYNAMIC_APP_DESCRIPTOR_VERSION,
                app = AppIdentity("pantry", "Pantry", "0.23.0"),
                endpointPolicy = EndpointPolicy(
                    serverOrigin = "https://cloud.example.test",
                    approvedApiPrefixes = listOf("/ocs/v2.php/apps/pantry"),
                ),
            ),
            sourcePath = "signed-package/openapi.json",
            acquisition = DynamicDescriptorAcquisition.SignedAppStorePackage,
            versionStatus = DynamicContractVersionStatus.LastKnownReadOnly,
        )

        cache.storeDiscovery(session, "pantry", discovery)

        assertEquals(discovery, cache.discovery(session, "pantry"))
        assertFalse(cache.isDiscoveryFresh(session, "pantry"))
    }

    @Test
    fun `mutation invalidation removes only screens for the exact account and app`() {
        val cache = DynamicNativeMemoryCache()
        val target = dynamicScreenCacheKey(session, "pantry", "items.list", null, emptyMap())
        val siblingView = dynamicScreenCacheKey(session, "pantry", "lists.list", null, emptyMap())
        val otherApp = dynamicScreenCacheKey(session, "tasks", "tasks.list", null, emptyMap())
        val otherAccount = dynamicScreenCacheKey(
            session.copy(loginName = "bob"),
            "pantry",
            "items.list",
            null,
            emptyMap(),
        )
        listOf(target, siblingView, otherApp, otherAccount).forEachIndexed { index, key ->
            cache.storeScreen(
                key,
                DynamicScreenSnapshot(
                    records = listOf(NativeRecord(index.toString(), mapOf("id" to index.toString()))),
                    relatedRecords = emptyMap(),
                ),
            )
        }

        cache.invalidateScreens(session, "pantry")

        assertNull(cache.screen(target))
        assertNull(cache.screen(siblingView))
        assertEquals("2", cache.screen(otherApp)?.records?.single()?.id)
        assertEquals("3", cache.screen(otherAccount)?.records?.single()?.id)
    }

    @Test
    fun `retirement purges only the exact account across every cache class`() {
        val cache = DynamicNativeMemoryCache()
        val otherSession = session.copy(loginName = "bob")
        val targetScreen = dynamicScreenCacheKey(session, "mail", "messages.list", null, emptyMap())
        val otherScreen = dynamicScreenCacheKey(otherSession, "mail", "messages.list", null, emptyMap())
        cache.storeDiscovery(session, "mail", discovery("mail"))
        cache.storeDiscovery(otherSession, "mail", discovery("mail"))
        cache.markDiscoveryFailure(session, "mail")
        cache.markDiscoveryFailure(otherSession, "mail")
        cache.storeScreen(targetScreen, snapshot("target"))
        cache.storeScreen(otherScreen, snapshot("other"))

        cache.retireAccount(session.accountId.storageKey)

        assertNull(cache.discovery(session, "mail"))
        assertNull(cache.screen(targetScreen))
        assertFalse(cache.shouldRetryDiscovery(session, "mail"))
        assertEquals("mail", cache.discovery(otherSession, "mail")?.descriptor?.app?.id)
        assertEquals("other", cache.screen(otherScreen)?.records?.single()?.id)
        assertFalse(cache.shouldRetryDiscovery(otherSession, "mail"))

        cache.activateAccount(session.accountId.storageKey)

        assertNull(cache.discovery(session, "mail"))
        assertNull(cache.screen(targetScreen))
        assertTrue(cache.shouldRetryDiscovery(session, "mail"))
        assertEquals("other", cache.screen(otherScreen)?.records?.single()?.id)
    }

    @Test
    fun `completion crossing retirement and reactivation cannot store into the new incarnation`() {
        val cache = DynamicNativeMemoryCache()
        val key = dynamicScreenCacheKey(session, "mail", "messages.list", null, emptyMap())
        val staleProducer = requireNotNull(cache.producer(session))

        cache.retireAccount(session.accountId.storageKey)
        cache.activateAccount(session.accountId.storageKey)
        cache.storeDiscovery(session, "mail", discovery("mail"), staleProducer)
        cache.markDiscoveryFailure(session, "mail", staleProducer)
        cache.storeScreen(key, snapshot("late"), staleProducer)

        assertNull(cache.discovery(session, "mail"))
        assertNull(cache.screen(key))
        assertTrue(cache.shouldRetryDiscovery(session, "mail"))

        val currentProducer = requireNotNull(cache.producer(session))
        cache.storeDiscovery(session, "mail", discovery("mail"), currentProducer)
        cache.storeScreen(key, snapshot("current"), currentProducer)

        assertEquals("mail", cache.discovery(session, "mail")?.descriptor?.app?.id)
        assertEquals("current", cache.screen(key)?.records?.single()?.id)
    }

    @Test
    fun `concurrent cache access stays safe across retirement`() = runBlocking {
        val cache = DynamicNativeMemoryCache(maximumScreens = 8)
        val accountStorageKey = session.accountId.storageKey

        List(12) { worker ->
            async(Dispatchers.Default) {
                repeat(200) { iteration ->
                    val appId = "app-${iteration % 4}"
                    val key = dynamicScreenCacheKey(
                        session,
                        appId,
                        "view-$worker",
                        iteration.toString(),
                        emptyMap(),
                    )
                    cache.storeDiscovery(session, appId, discovery(appId))
                    cache.markDiscoveryFailure(session, appId)
                    cache.storeScreen(key, snapshot("$worker-$iteration"))
                    cache.discovery(session, appId)
                    cache.isDiscoveryFresh(session, appId)
                    cache.shouldRetryDiscovery(session, appId)
                    cache.screen(key)
                    if (iteration % 11 == 0) cache.invalidateScreens(session, appId)
                }
            }
        }.awaitAll()

        cache.retireAccount(accountStorageKey)

        repeat(4) { app ->
            assertNull(cache.discovery(session, "app-$app"))
            assertFalse(cache.shouldRetryDiscovery(session, "app-$app"))
        }
    }

    @Test
    fun `dynamic response identity is stable across query order and contains no credentials`() {
        val first = NextcloudApiRequest(
            method = NextcloudApiMethod.GET,
            relativePath = "/apps/mail/api/messages",
            queryParameters = mapOf("mailbox" to "Sent items", "page" to "2"),
            ocsApiRequest = true,
        )
        val second = first.copy(
            queryParameters = mapOf("page" to "2", "mailbox" to "Sent items"),
        )

        assertEquals(first.dynamicReadCacheIdentity(), second.dynamicReadCacheIdentity())
        assertNull(first.dynamicReadCacheIdentity().takeIf { "never-cache-this" in it })
        assertNotEquals(
            first.dynamicReadCacheIdentity(),
            first.copy(maximumResponseBytes = first.maximumResponseBytes + 1L).dynamicReadCacheIdentity(),
        )
    }

    private fun discovery(appId: String) = DynamicDescriptorDiscovery(
        descriptor = DynamicAppDescriptor(
            descriptorVersion = DYNAMIC_APP_DESCRIPTOR_VERSION,
            app = AppIdentity(appId, appId, "1.0.0"),
            endpointPolicy = EndpointPolicy(
                serverOrigin = "https://cloud.example.test",
                approvedApiPrefixes = listOf("/ocs/v2.php/apps/$appId"),
            ),
        ),
        sourcePath = "signed-package/openapi.json",
        acquisition = DynamicDescriptorAcquisition.SignedAppStorePackage,
        versionStatus = DynamicContractVersionStatus.VerifiedCurrent,
    )

    private fun snapshot(id: String) = DynamicScreenSnapshot(
        records = listOf(NativeRecord(id, mapOf("id" to id))),
        relatedRecords = emptyMap(),
    )

    private fun DynamicNativeMemoryCache.storeDiscovery(
        session: NextcloudSession,
        appId: String,
        discovery: DynamicDescriptorDiscovery,
    ) = storeDiscovery(session, appId, discovery, requireNotNull(producer(session)))

    private fun DynamicNativeMemoryCache.markDiscoveryFailure(session: NextcloudSession, appId: String) =
        markDiscoveryFailure(session, appId, requireNotNull(producer(session)))

    private fun DynamicNativeMemoryCache.storeScreen(
        key: DynamicScreenCacheKey,
        snapshot: DynamicScreenSnapshot,
    ) = storeScreen(key, snapshot, requireNotNull(producer(key)))
}
