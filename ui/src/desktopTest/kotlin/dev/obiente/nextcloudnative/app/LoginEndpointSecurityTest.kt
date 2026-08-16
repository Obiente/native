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
    fun explicitlyEnteredPlainHttpIsLimitedToTheEnteredOrigin() {
        val relationships = validateLoginEndpointRelationships(
            enteredServerUrl = "http://cloud.home.test:8080/nextcloud",
            loginUrl = "http://cloud.home.test:8080/nextcloud/login",
            pollEndpoint = "http://cloud.home.test:8080/nextcloud/login/v2/poll",
        )

        assertTrue(relationships.loginOriginMatchesEntered)
        assertTrue(relationships.pollOriginMatchesEntered)
        assertEquals(null, relationships.pollFallbackEndpoint)
        assertTrue(serverAddressUsesPlainHttp(" HTTP://cloud.home.test "))
        assertFalse(serverAddressUsesPlainHttp("cloud.home.test"))

        assertFailsWith<IllegalArgumentException> {
            validateLoginEndpointRelationships(
                enteredServerUrl = "http://cloud.home.test",
                loginUrl = "http://other.home.test/login",
                pollEndpoint = "http://cloud.home.test/login/v2/poll",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            validateLoginEndpointRelationships(
                enteredServerUrl = "https://cloud.example.com",
                loginUrl = "https://cloud.example.com/login",
                pollEndpoint = "http://cloud.example.com/login/v2/poll",
            )
        }
    }

    @Test
    fun plainHttpMayUpgradeAdvertisedEndpointsToHttps() {
        val relationships = validateLoginEndpointRelationships(
            enteredServerUrl = "http://cloud.home.test",
            loginUrl = "https://identity.example.test/login",
            pollEndpoint = "https://cloud.home.test/login/v2/poll",
        )

        assertFalse(relationships.loginOriginMatchesEntered)
        assertFalse(relationships.pollOriginMatchesEntered)
        assertEquals("http://cloud.home.test/index.php/login/v2/poll", relationships.pollFallbackEndpoint)
        assertFalse(
            loginResultOriginMatchesEntered(
                "http://cloud.home.test",
                "https://cloud.example.test",
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            loginResultOriginMatchesEntered(
                "http://cloud.home.test",
                "http://other.home.test",
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
