package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LoginEndpointSecurityTest {
    @Test
    fun endpointOriginsAreComparedWithoutPersistingTheirHostnames() {
        val relationships = validateLoginEndpointRelationships(
            enteredServerUrl = "https://Cloud.Example.com",
            loginUrl = "https://cloud.example.com/index.php/login/flow",
            pollEndpoint = "https://auth.example.net/index.php/login/v2/poll",
        )

        assertTrue(relationships.loginOriginMatchesEntered)
        assertFalse(relationships.pollOriginMatchesEntered)
        assertEquals("https://Cloud.Example.com/index.php/login/v2/poll", relationships.pollFallbackEndpoint)
    }

    @Test
    fun sameOriginAdvertisedPollingPathIsPreserved() {
        val relationships = validateLoginEndpointRelationships(
            enteredServerUrl = "https://cloud.example.com/nextcloud",
            loginUrl = "https://cloud.example.com/nextcloud/login",
            pollEndpoint = "https://cloud.example.com/custom/poll",
        )

        assertEquals(null, relationships.pollFallbackEndpoint)
    }

    @Test
    fun unsafeLoginEndpointsAreRejectedBeforeOpeningTheBrowser() {
        assertFailsWith<IllegalArgumentException> {
            validateLoginEndpointRelationships(
                enteredServerUrl = "https://cloud.example.com",
                loginUrl = "http://cloud.example.com/login",
                pollEndpoint = "https://cloud.example.com/poll",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            validateLoginEndpointRelationships(
                enteredServerUrl = "https://cloud.example.com",
                loginUrl = "https://person@cloud.example.com/login",
                pollEndpoint = "https://cloud.example.com/poll",
            )
        }
    }

    @Test
    fun onlyPreExchangeDnsFailureIsSafeToRetry() {
        val dns = JvmNetworkFailureDiagnostic(
            code = "NETWORK_DNS_UNRESOLVED",
            phase = JvmNetworkFailurePhase.Dns,
            retryable = false,
            attempt = 1,
            exchangeStarted = false,
            protocol = null,
        )
        val afterExchange = dns.copy(exchangeStarted = true)

        assertIs<LoginPollResult.RetryablePreExchangeFailure>(classifyLoginPollNetworkFailure(dns))
        assertIs<LoginPollResult.AmbiguousAfterExchangeFailure>(classifyLoginPollNetworkFailure(afterExchange))
        assertEquals(
            false,
            loginResultOriginMatchesEntered("https://cloud.example.com", "https://other.example.com"),
        )
    }
}
