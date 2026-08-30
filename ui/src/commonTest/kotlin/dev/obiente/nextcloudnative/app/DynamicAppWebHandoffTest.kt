package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun `advertised href that includes installation path does not duplicate it`() {
        assertEquals(
            "https://cloud.example.test/nextcloud/index.php/apps/office/",
            verifiedEmbeddedWebAppUrl(
                serverUrl = "https://cloud.example.test/nextcloud",
                appId = "office",
                advertisedHref = "/nextcloud/index.php/apps/office/",
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
    fun `generic office alias requires an advertised route or suite discovery`() {
        assertNull(
            verifiedEmbeddedWebAppUrl("https://cloud.example.test", "office", null),
        )
    }

    @Test
    fun `onlyoffice keeps its own dashboard route`() {
        assertEquals(
            "https://cloud.example.test/index.php/apps/onlyoffice/",
            verifiedEmbeddedWebAppUrl("https://cloud.example.test", "onlyoffice", null),
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

    @Test
    fun `generic office navigation discovers every known advertised suite`() {
        val capabilities = NextcloudDocumentEditingCapabilities(
            editors = mapOf(
                "richdocuments" to NextcloudDocumentEditorCapability(
                    id = "richdocuments",
                    displayName = "Nextcloud Office",
                    mimeTypes = setOf("application/vnd.oasis.opendocument.text"),
                    optionalMimeTypes = emptySet(),
                    secure = true,
                ),
                "onlyoffice" to NextcloudDocumentEditorCapability(
                    id = "onlyoffice",
                    displayName = "ONLYOFFICE",
                    mimeTypes = setOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
                    optionalMimeTypes = emptySet(),
                    secure = true,
                ),
            ),
            creators = emptyMap(),
            supportsFileId = true,
        )

        assertEquals(
            listOf("Nextcloud Office", "ONLYOFFICE"),
            officeDashboardChoices("https://cloud.example.test", capabilities)
                .map(OfficeDashboardChoice::displayName),
        )
    }

    @Test
    fun `dashboard discovery excludes insecure and unknown editor routes`() {
        val capabilities = NextcloudDocumentEditingCapabilities(
            editors = mapOf(
                "onlyoffice" to NextcloudDocumentEditorCapability(
                    id = "onlyoffice",
                    displayName = "ONLYOFFICE",
                    mimeTypes = emptySet(),
                    optionalMimeTypes = emptySet(),
                    secure = false,
                ),
                "future_suite" to NextcloudDocumentEditorCapability(
                    id = "future_suite",
                    displayName = "Future Suite",
                    mimeTypes = emptySet(),
                    optionalMimeTypes = emptySet(),
                    secure = true,
                ),
            ),
            creators = emptyMap(),
            supportsFileId = true,
        )

        assertTrue(officeDashboardChoices("https://cloud.example.test", capabilities).isEmpty())
    }
}
