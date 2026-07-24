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
        assertEquals("Obiente", fixture.displayName)
        assertEquals("Nextcloud", fixture.cloudName)
        assertTrue(server.apps.isNotEmpty())
        assertTrue(server.apps.all { it.href == null })
        assertTrue(
            listOf(fixture.displayName, fixture.cloudName, server.userId)
                .all { value -> value.isNotBlank() && value.none(Char::isISOControl) },
        )
    }
}
