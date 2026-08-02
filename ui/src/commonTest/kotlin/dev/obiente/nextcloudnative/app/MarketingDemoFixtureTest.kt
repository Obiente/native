package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarketingDemoFixtureTest {
    @Test
    fun marketingFixtureIsSyntheticNetworkInertAndCredentialFree() {
        val fixture = nextcloudNativeMarketingFixture
        val server = fixture.serverInfo()

        assertEquals("https://fixture.invalid", server.serverUrl)
        assertEquals("demo-user", server.userId)
        assertEquals("Mara Lind", fixture.displayName)
        assertEquals("cloud.example.com", fixture.cloudName)
        assertTrue(server.apps.size >= 20)
        assertEquals(server.apps.size, server.apps.map(NextcloudAppEntry::id).distinct().size)
        assertTrue(server.apps.map(NextcloudAppEntry::id).containsAll(
            listOf("files", "photos", "talk", "mail", "calendar", "deck", "tables", "activity"),
        ))
        assertTrue(server.apps.all { it.href == null })
        assertTrue(
            listOf(fixture.displayName, fixture.cloudName, server.userId)
                .all { value -> value.isNotBlank() && value.none(Char::isISOControl) },
        )
    }

    @Test
    fun `marketing identity represents an actively used account`() {
        val identity = marketingDesktopIdentity()

        assertEquals(4, identity.shortcuts.size)
        assertTrue(identity.shortcuts.any { it.badge != null })
        assertEquals("deck", identity.recentApp?.id)
        assertTrue(identity.syncSummary?.contains("4 folders") == true)
        assertTrue((identity.storageProgress ?: 0f) in .3f..0.4f)
    }
}
