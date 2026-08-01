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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TestTimeSource

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
}
