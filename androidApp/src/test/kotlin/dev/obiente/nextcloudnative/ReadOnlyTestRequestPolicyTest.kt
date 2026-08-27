package dev.obiente.nextcloudnative

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReadOnlyTestRequestPolicyTest {
    @Test
    fun `read only emulator sessions allow retrieval protocols`() {
        listOf("GET", "HEAD", "OPTIONS", "PROPFIND", "REPORT", "SEARCH").forEach { method ->
            assertTrue(method.isReadOnlyTestRequestMethod(), method)
            assertTrue(method.lowercase(Locale.ROOT).isReadOnlyTestRequestMethod(), method)
        }
    }

    @Test
    fun `read only request matching is independent of the process locale`() {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertTrue("options".isReadOnlyTestRequestMethod())
            assertTrue("propfind".isReadOnlyTestRequestMethod())
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun `read only emulator sessions reject every mutation protocol`() {
        listOf("POST", "PUT", "PATCH", "DELETE", "MKCOL", "MOVE", "COPY", "LOCK", "UNLOCK").forEach { method ->
            assertFalse(method.isReadOnlyTestRequestMethod(), method)
        }
    }

    @Test
    fun `debug write scope permits only one exact app record subtree`() {
        val scope = assertNotNull(
            ScopedTestWriteAuthorization.create(
                serverUrl = "https://cloud.example.test/nextcloud",
                apiPathPrefix = "/ocs/v2.php/apps/example/api/houses/1",
            ),
        )

        listOf("POST", "PUT", "PATCH", "DELETE").forEach { method ->
            assertTrue(
                scope.allows(
                    method,
                    "https://cloud.example.test/nextcloud/ocs/v2.php/apps/example/api/houses/1/lists?format=json",
                ),
                method,
            )
        }
        listOf(
            "https://cloud.example.test/nextcloud/ocs/v2.php/apps/example/api/houses/10/lists",
            "https://cloud.example.test/nextcloud/ocs/v2.php/apps/example/api/houses/2/lists",
            "https://cloud.example.test/nextcloud/remote.php/dav/files/test",
            "https://other.example.test/nextcloud/ocs/v2.php/apps/example/api/houses/1/lists",
            "http://cloud.example.test/nextcloud/ocs/v2.php/apps/example/api/houses/1/lists",
        ).forEach { url ->
            assertFalse(scope.allows("POST", url), url)
        }
        assertFalse(scope.allows("MKCOL", "https://cloud.example.test/nextcloud/ocs/v2.php/apps/example/api/houses/1"))
        assertEquals(false, scope.allows("GET", "https://cloud.example.test/nextcloud/ocs/v2.php/apps/example/api/houses/1"))
    }

    @Test
    fun `debug write scope recognizes direct and front controller app APIs`() {
        listOf(
            "/apps/chores/api/v1.0/team" to
                "https://cloud.example.test/apps/chores/api/v1.0/team/chores",
            "/index.php/apps/tables/api/1/rows" to
                "https://cloud.example.test/index.php/apps/tables/api/1/rows/24",
        ).forEach { (prefix, requestUrl) ->
            val scope = assertNotNull(
                ScopedTestWriteAuthorization.create(
                    serverUrl = "https://cloud.example.test",
                    apiPathPrefix = prefix,
                ),
                prefix,
            )
            assertTrue(scope.allows("POST", requestUrl), requestUrl)
        }
    }

    @Test
    fun `debug write scope rejects broad malformed and non app prefixes`() {
        listOf(
            "/ocs/v2.php/apps/example/api",
            "/ocs/v2.php/apps/example/api/houses",
            "/ocs/v2.php/apps/example/api/houses/..",
            "/ocs/v2.php/apps/example/api/houses/1?all=true",
            "/apps/chores/api",
            "/apps/chores/api/v1.0",
            "/apps/chores/not-api/v1.0/team",
            "/apps/chores/api/v1.0/../team",
            "/index.php/apps/tables/api/1",
            "/index.php/apps/tables/api/1/rows?all=true",
            "/remote.php/dav/files/test",
        ).forEach { path ->
            assertNull(ScopedTestWriteAuthorization.create("https://cloud.example.test", path), path)
        }
        assertNull(
            ScopedTestWriteAuthorization.create(
                "https://user@cloud.example.test",
                "/ocs/v2.php/apps/example/api/houses/1",
            ),
        )
    }
}
