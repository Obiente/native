package dev.obiente.nextcloudnative.app

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class JvmLoginFlowTransportTest {
    @Test
    fun `not found advertised path accepts approval from entered base path compatibility endpoint`() {
        val endpoints = mutableListOf<String>()
        val execution = executeLoginPollHttp(
            challenge = challenge(
                pollEndpoint = "https://cloud.example.test/nextcloud/login/v2/poll",
                fallbackEndpoint = "https://cloud.example.test/nextcloud/index.php/login/v2/poll",
            ),
            fallbackAlreadySelected = false,
            poll = { endpoint ->
                endpoints += endpoint
                if ("/index.php/" in endpoint) approvedResponse() else LoginPollHttpResponse(404, "[]")
            },
            networkFailure = { null },
        )

        assertEquals(
            listOf(
                "https://cloud.example.test/nextcloud/login/v2/poll",
                "https://cloud.example.test/nextcloud/index.php/login/v2/poll",
            ),
            endpoints,
        )
        assertIs<LoginPollResult.Approved>(execution.interpretation.result)
        assertEquals(LoginPollFallbackReason.AdvertisedEndpointNotFound, execution.selectedFallbackReason)
        assertEquals(true, execution.usedFallback)
    }

    @Test
    fun `dual not found responses keep probing without abandoning advertised route`() {
        val endpoints = mutableListOf<String>()
        val execution = executeLoginPollHttp(
            challenge = challenge(
                pollEndpoint = "https://cloud.example.test/login/v2/poll",
                fallbackEndpoint = "https://cloud.example.test/index.php/login/v2/poll",
            ),
            fallbackAlreadySelected = false,
            poll = { endpoint ->
                endpoints += endpoint
                LoginPollHttpResponse(status = 404, body = "[]")
            },
            networkFailure = { null },
        )

        assertEquals(2, endpoints.size)
        assertEquals(LoginPollResult.Pending, execution.interpretation.result)
        assertNull(execution.selectedFallbackReason)
        assertEquals(false, execution.usedFallback)

        val laterApproval = executeLoginPollHttp(
            challenge = challenge(
                pollEndpoint = "https://cloud.example.test/login/v2/poll",
                fallbackEndpoint = "https://cloud.example.test/index.php/login/v2/poll",
            ),
            fallbackAlreadySelected = execution.usedFallback,
            poll = { endpoint ->
                endpoints += endpoint
                approvedResponse()
            },
            networkFailure = { null },
        )

        assertEquals("https://cloud.example.test/login/v2/poll", endpoints.last())
        assertEquals(3, endpoints.size)
        assertIs<LoginPollResult.Approved>(laterApproval.interpretation.result)
        assertNull(laterApproval.selectedFallbackReason)
    }

    @Test
    fun `incompatible compatibility response leaves advertised pending route selected`() {
        val execution = executeLoginPollHttp(
            challenge = challenge(
                pollEndpoint = "https://cloud.example.test/login/v2/poll",
                fallbackEndpoint = "https://cloud.example.test/index.php/login/v2/poll",
            ),
            fallbackAlreadySelected = false,
            poll = { endpoint ->
                if ("/index.php/" in endpoint) {
                    LoginPollHttpResponse(405, "")
                } else {
                    LoginPollHttpResponse(404, "[]")
                }
            },
            networkFailure = { null },
        )

        assertEquals(LoginPollResult.Pending, execution.interpretation.result)
        assertNull(execution.selectedFallbackReason)
        assertEquals(false, execution.usedFallback)
    }

    @Test
    fun `pre exchange DNS failure probes pending compatibility endpoint without pinning it`() {
        var diagnostic: JvmNetworkFailureDiagnostic? = null
        val endpoints = mutableListOf<String>()
        val execution = executeLoginPollHttp(
            challenge = challenge(
                pollEndpoint = "https://internal.example.test/login/v2/poll",
                fallbackEndpoint = "https://cloud.example.test/index.php/login/v2/poll",
            ),
            fallbackAlreadySelected = false,
            poll = { endpoint ->
                endpoints += endpoint
                diagnostic = if (endpoint.contains("internal")) dnsFailure() else null
                if (diagnostic != null) throw IOException("synthetic DNS failure")
                LoginPollHttpResponse(status = 404, body = "[]")
            },
            networkFailure = { diagnostic },
        )

        assertEquals(LoginPollResult.Pending, execution.interpretation.result)
        assertEquals(
            listOf(
                "https://internal.example.test/login/v2/poll",
                "https://cloud.example.test/index.php/login/v2/poll",
            ),
            endpoints,
        )
        assertNull(execution.selectedFallbackReason)
        assertEquals(false, execution.usedFallback)
    }

    @Test
    fun `pre exchange DNS failure selects compatibility endpoint after approval`() {
        var diagnostic: JvmNetworkFailureDiagnostic? = null
        val execution = executeLoginPollHttp(
            challenge = challenge(
                pollEndpoint = "https://internal.example.test/login/v2/poll",
                fallbackEndpoint = "https://cloud.example.test/index.php/login/v2/poll",
            ),
            fallbackAlreadySelected = false,
            poll = { endpoint ->
                diagnostic = if (endpoint.contains("internal")) dnsFailure() else null
                if (diagnostic != null) throw IOException("synthetic DNS failure")
                approvedResponse()
            },
            networkFailure = { diagnostic },
        )

        assertIs<LoginPollResult.Approved>(execution.interpretation.result)
        assertEquals(LoginPollFallbackReason.PreExchangeFailure, execution.selectedFallbackReason)
        assertEquals(true, execution.usedFallback)
    }

    @Test
    fun `incompatible fallback response preserves retryable advertised endpoint failure`() {
        listOf(405, 503).forEach { fallbackStatus ->
            var diagnostic: JvmNetworkFailureDiagnostic? = null
            val endpoints = mutableListOf<String>()
            val challenge = challenge(
                pollEndpoint = "https://internal.example.test/login/v2/poll",
                fallbackEndpoint = "https://cloud.example.test/index.php/login/v2/poll",
            )
            val execution = executeLoginPollHttp(
                challenge = challenge,
                fallbackAlreadySelected = false,
                poll = { endpoint ->
                    endpoints += endpoint
                    diagnostic = if (endpoint.contains("internal")) dnsFailure() else null
                    if (diagnostic != null) throw IOException("synthetic DNS failure")
                    LoginPollHttpResponse(status = fallbackStatus, body = "unavailable")
                },
                networkFailure = { diagnostic },
            )

            assertIs<LoginPollResult.RetryablePreExchangeFailure>(execution.interpretation.result)
            assertNull(execution.selectedFallbackReason)
            assertEquals(false, execution.usedFallback)
            assertEquals(
                listOf(
                    "https://internal.example.test/login/v2/poll",
                    "https://cloud.example.test/index.php/login/v2/poll",
                ),
                endpoints,
            )

            val laterApproval = executeLoginPollHttp(
                challenge = challenge,
                fallbackAlreadySelected = execution.usedFallback,
                poll = { endpoint ->
                    endpoints += endpoint
                    approvedResponse()
                },
                networkFailure = { null },
            )
            assertEquals("https://internal.example.test/login/v2/poll", endpoints.last())
            assertIs<LoginPollResult.Approved>(laterApproval.interpretation.result)
        }
    }

    @Test
    fun `failure after compatibility exchange is ambiguous`() {
        var diagnostic: JvmNetworkFailureDiagnostic? = null
        val execution = executeLoginPollHttp(
            challenge = challenge(
                pollEndpoint = "https://cloud.example.test/login/v2/poll",
                fallbackEndpoint = "https://cloud.example.test/index.php/login/v2/poll",
            ),
            fallbackAlreadySelected = false,
            poll = { endpoint ->
                if ("/index.php/" !in endpoint) {
                    LoginPollHttpResponse(status = 404, body = "[]")
                } else {
                    diagnostic = dnsFailure().copy(exchangeStarted = true)
                    throw IOException("synthetic response failure")
                }
            },
            networkFailure = { diagnostic },
        )

        assertIs<LoginPollResult.AmbiguousAfterExchangeFailure>(execution.interpretation.result)
        assertEquals(false, execution.usedFallback)
    }

    private fun challenge(pollEndpoint: String, fallbackEndpoint: String?) = LoginChallenge(
        enteredServerUrl = "https://cloud.example.test/nextcloud",
        pollEndpoint = pollEndpoint,
        pollFallbackEndpoint = fallbackEndpoint,
        token = "synthetic-one-time-token",
        loginUrl = "https://cloud.example.test/nextcloud/login/v2/flow/synthetic",
    )

    private fun dnsFailure() = JvmNetworkFailureDiagnostic(
        code = "NETWORK_DNS_UNRESOLVED",
        phase = JvmNetworkFailurePhase.Dns,
        retryable = false,
        attempt = 1,
        exchangeStarted = false,
        protocol = null,
    )

    private fun approvedResponse() = LoginPollHttpResponse(
        status = 200,
        body = """{
            "server": "https://cloud.example.test/nextcloud",
            "loginName": "person",
            "appPassword": "synthetic-app-password"
        }""".trimIndent(),
    )
}
