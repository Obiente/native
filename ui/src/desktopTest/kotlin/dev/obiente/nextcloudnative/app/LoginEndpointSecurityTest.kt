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
    fun sameOriginPrettyPollingPathGetsEnteredBasePathCompatibilityEndpoint() {
        val relationships = validateLoginEndpointRelationships(
            enteredServerUrl = "https://cloud.example.com/nextcloud",
            loginUrl = "https://cloud.example.com/nextcloud/login",
            pollEndpoint = "https://cloud.example.com/custom/poll",
        )

        assertEquals(
            "https://cloud.example.com/nextcloud/index.php/login/v2/poll",
            relationships.pollFallbackEndpoint,
        )
    }

    @Test
    fun canonicalPollingPathDoesNotCreateASecondEndpoint() {
        val relationships = validateLoginEndpointRelationships(
            enteredServerUrl = "https://cloud.example.com/nextcloud",
            loginUrl = "https://cloud.example.com/nextcloud/login",
            pollEndpoint = "https://cloud.example.com/nextcloud/index.php/login/v2/poll",
        )

        assertEquals(null, relationships.pollFallbackEndpoint)
    }

    @Test
    fun encodedBasePathIsPreservedInCompatibilityEndpoint() {
        val relationships = validateLoginEndpointRelationships(
            enteredServerUrl = "https://cloud.example.com/next%20cloud",
            loginUrl = "https://cloud.example.com/next%20cloud/login",
            pollEndpoint = "https://cloud.example.com/custom/poll",
        )

        assertEquals(
            "https://cloud.example.com/next%20cloud/index.php/login/v2/poll",
            relationships.pollFallbackEndpoint,
        )
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
        assertEquals(
            "http://cloud.home.test:8080/nextcloud/index.php/login/v2/poll",
            relationships.pollFallbackEndpoint,
        )
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
    fun onlyTransientConnectionSetupFailuresAreSafeToRetry() {
        val dns = JvmNetworkFailureDiagnostic(
            code = "NETWORK_DNS_UNRESOLVED",
            phase = JvmNetworkFailurePhase.Dns,
            retryable = false,
            attempt = 1,
            exchangeStarted = false,
            protocol = null,
        )
        val connectionSetupFailures = listOf(
            dns,
            dns.copy(code = "NETWORK_CONNECT_FAILED", phase = JvmNetworkFailurePhase.Connect),
            dns.copy(code = "NETWORK_CONNECT_TIMEOUT", phase = JvmNetworkFailurePhase.Connect),
            dns.copy(code = "NETWORK_UNREACHABLE", phase = JvmNetworkFailurePhase.Connect),
            dns.copy(code = "NETWORK_TLS_TIMEOUT", phase = JvmNetworkFailurePhase.Tls),
            dns.copy(code = "NETWORK_SOCKET_FAILED", phase = JvmNetworkFailurePhase.Connect),
            dns.copy(code = "NETWORK_CONNECTION_RESET", phase = JvmNetworkFailurePhase.Connect),
        )
        connectionSetupFailures.forEach { failure ->
            val result = assertIs<LoginPollResult.RetryablePreExchangeFailure>(
                classifyLoginPollNetworkFailure(failure),
            )
            assertEquals(failure.code, result.code)
            // A request that may have reached the server is never retried, whatever its cause.
            val afterExchange = failure.copy(
                exchangeStarted = true,
                phase = JvmNetworkFailurePhase.ResponseHeaders,
            )
            val ambiguous = assertIs<LoginPollResult.AmbiguousAfterExchangeFailure>(
                classifyLoginPollNetworkFailure(afterExchange),
            )
            assertEquals(failure.code, ambiguous.code)
        }
        listOf(
            dns.copy(code = "NETWORK_CERTIFICATE_REJECTED", phase = JvmNetworkFailurePhase.Tls),
            dns.copy(code = "NETWORK_TLS_HANDSHAKE", phase = JvmNetworkFailurePhase.Tls),
            dns.copy(code = "NETWORK_PROTOCOL_FAILED", phase = JvmNetworkFailurePhase.Connect),
            dns.copy(code = "NETWORK_UNKNOWN_FAILED", phase = JvmNetworkFailurePhase.Unknown),
            dns.copy(code = "NETWORK_SOCKET_FAILED", phase = JvmNetworkFailurePhase.Tls),
        ).forEach { failure ->
            assertIs<LoginPollResult.FatalFailure>(classifyLoginPollNetworkFailure(failure))
        }
        assertIs<LoginPollResult.FatalFailure>(classifyLoginPollNetworkFailure(null))
        assertEquals(
            false,
            loginResultOriginMatchesEntered("https://cloud.example.com", "https://other.example.com"),
        )
    }
}
