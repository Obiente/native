package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DynamicAppWebHandoffTest {
    @Test
    fun `nextcloud office navigation identity uses its advertised web route`() {
        assertEquals(
            "https://cloud.example.test/apps/office/",
            verifiedEmbeddedWebAppUrl(
                serverUrl = "https://cloud.example.test",
                appId = "office",
                advertisedHref = "/apps/office/",
            ),
        )
    }

    @Test
    fun `server advertised relative app href is preferred`() {
        assertEquals(
            "https://cloud.example.test/nextcloud/apps/office/",
            verifiedEmbeddedWebAppUrl(
                serverUrl = "https://cloud.example.test/nextcloud",
                appId = "richdocuments",
                advertisedHref = "/apps/office/",
            ),
        )
    }

    @Test
    fun `missing href uses conventional app route`() {
        assertEquals(
            "https://cloud.example.test/index.php/apps/richdocuments/",
            verifiedEmbeddedWebAppUrl("https://cloud.example.test/", "richdocuments", null),
        )
    }

    @Test
    fun `cross origin href is ignored`() {
        assertEquals(
            "https://cloud.example.test/index.php/apps/richdocuments/",
            verifiedEmbeddedWebAppUrl(
                "https://cloud.example.test",
                "richdocuments",
                "https://attacker.example/apps/office",
            ),
        )
    }

    @Test
    fun `unsafe app identity cannot produce a fallback route`() {
        assertNull(verifiedEmbeddedWebAppUrl("https://cloud.example.test", "../settings", null))
    }

    @Test
    fun `metadata fallback remains native for apps that are not verified web only`() {
        assertNull(
            verifiedEmbeddedWebAppUrl(
                "https://cloud.example.test",
                "notes",
                "/index.php/apps/notes/",
            ),
        )
    }
}
