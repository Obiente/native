package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okhttp3.HttpUrl.Companion.toHttpUrl

class AndroidTransportRedirectPolicyTest {
    @Test
    fun tlsSessionsRejectCleartextDowngrades() {
        assertFalse(androidTransportRequestAllowed(null, null, "http://cloud.example.test/ocs".toHttpUrl()))
        assertTrue(androidTransportRequestAllowed(null, null, "https://cloud.example.test/ocs".toHttpUrl()))
    }

    @Test
    fun approvedPlainHttpSessionsAllowTheirOriginAndHttpsUpgradesOnly() {
        assertTrue(
            androidTransportRequestAllowed(
                "cloud.example.test",
                80,
                "http://cloud.example.test/login".toHttpUrl(),
            ),
        )
        assertTrue(
            androidTransportRequestAllowed(
                "cloud.example.test",
                80,
                "https://canonical.example.test/login".toHttpUrl(),
            ),
        )
        assertFalse(
            androidTransportRequestAllowed(
                "cloud.example.test",
                80,
                "http://other.example.test/login".toHttpUrl(),
            ),
        )
    }
}
