package dev.obiente.nextcloudnative.app

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DesktopProjectContentTest {
    @Test
    fun publicContentClientUsesBoundedTotalAndPhaseTimeouts() {
        val client = buildDesktopProjectContentHttpClient()

        assertEquals(DESKTOP_PROJECT_CONTENT_CONNECT_TIMEOUT_SECONDS * 1_000, client.connectTimeoutMillis.toLong())
        assertEquals(DESKTOP_PROJECT_CONTENT_READ_TIMEOUT_SECONDS * 1_000, client.readTimeoutMillis.toLong())
        assertEquals(DESKTOP_PROJECT_CONTENT_WRITE_TIMEOUT_SECONDS * 1_000, client.writeTimeoutMillis.toLong())
        assertEquals(DESKTOP_PROJECT_CONTENT_CALL_TIMEOUT_SECONDS * 1_000, client.callTimeoutMillis.toLong())
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
    }

    @Test
    fun refreshedPublicContentAtomicallyReplacesAnExistingCache() {
        val directory = Files.createTempDirectory("project-content-cache-test").toFile()
        try {
            val destination = directory.resolve("news-feed-v1.json")
            val temporary = directory.resolve("news-feed-v1.json.part")
            destination.writeText("old")
            temporary.writeText("new")

            publishDesktopProjectContentCache(temporary, destination)

            assertEquals("new", destination.readText())
            assertFalse(temporary.exists())
        } finally {
            directory.deleteRecursively()
        }
    }
}
