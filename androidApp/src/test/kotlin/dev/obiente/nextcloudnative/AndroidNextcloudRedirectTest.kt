package dev.obiente.nextcloudnative

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import okhttp3.HttpUrl.Companion.toHttpUrl

class AndroidNextcloudRedirectTest {
    @Test
    fun `authenticated redirects resolve inside the account origin and base path`() {
        val serverUrl = "https://cloud.example.test/nextcloud"
        val requestUrl = "$serverUrl/apps/music/api/artwork/current".toHttpUrl()

        assertEquals(
            "/apps/music/api/covers/7?size=large",
            resolveAndroidNextcloudRedirectLocation(
                requestUrl,
                serverUrl,
                "../covers/7?size=large",
            ),
        )
        assertEquals(
            "/apps/music/api/covers/8",
            resolveAndroidNextcloudRedirectLocation(
                requestUrl,
                serverUrl,
                "$serverUrl/apps/music/api/covers/8",
            ),
        )
        assertNull(
            resolveAndroidNextcloudRedirectLocation(
                requestUrl,
                serverUrl,
                "https://other.example.test/nextcloud/apps/music/api/covers/9",
            ),
        )
        assertNull(
            resolveAndroidNextcloudRedirectLocation(
                requestUrl,
                serverUrl,
                "https://cloud.example.test/apps/music/api/covers/9",
            ),
        )
    }
}
