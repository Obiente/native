package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JvmLoginFlowHttpPolicyTest {
    @Test
    fun `challenge parsing validates endpoints and builds safe diagnostics`() {
        val interpretation = interpretLoginChallengeHttpResponse(
            status = 200,
            body = """{
                "poll": {
                    "token": "one-time-token",
                    "endpoint": "https://cloud.example.test/login/v2/poll"
                },
                "login": "https://cloud.example.test/login/v2/flow/abc"
            }""".trimIndent(),
            enteredServerUrl = "https://cloud.example.test",
            transportSecurity = LoginTransportSecurity.Tls,
        )

        assertEquals("one-time-token", interpretation.challenge.token)
        assertTrue(interpretation.loginOriginMatchesEntered)
        assertTrue(interpretation.pollOriginMatchesEntered)
        val fields = interpretation.toStartedDiagnostic().fields.associate { it.name to it.value }
        assertEquals("true", fields["login_origin_matches_entered"])
        assertEquals("tls", fields["transport_security"])
        assertTrue(fields.values.none { "one-time-token" in it })
    }

    @Test
    fun `challenge parsing reports unsuccessful HTTP responses consistently`() {
        val failure = assertFailsWith<IllegalStateException> {
            interpretLoginChallengeHttpResponse(
                status = 503,
                body = "ignored",
                enteredServerUrl = "https://cloud.example.test",
                transportSecurity = LoginTransportSecurity.Tls,
            )
        }

        assertEquals("This server did not start Nextcloud Login Flow v2 (HTTP 503).", failure.message)
    }

    @Test
    fun `not found poll response is the pending protocol state`() {
        val interpretation = interpretLoginPollHttpResponse(
            status = 404,
            body = "[]",
            challenge = challenge(),
        )

        assertEquals(LoginPollResult.Pending, interpretation.result)
        assertNull(interpretation.resultOriginMatchesEntered)
    }

    @Test
    fun `valid approval carries credentials without putting them in diagnostics`() {
        val interpretation = interpretLoginPollHttpResponse(
            status = 200,
            body = """{
                "server": "https://cloud.example.test/",
                "loginName": "person",
                "appPassword": "private-app-password"
            }""".trimIndent(),
            challenge = challenge(),
        )

        assertIs<LoginPollResult.Approved>(interpretation.result)
        assertEquals("person", interpretation.approvedLoginName)
        assertEquals("private-app-password", interpretation.approvedAppPassword)
        val diagnostic = interpretation.toApprovedDiagnostic(usedFallback = false)
        assertTrue(diagnostic.fields.none { "person" in it.value || "private-app-password" in it.value })
    }

    @Test
    fun `empty credentials make an approval response ambiguous and non retryable`() {
        val interpretation = interpretLoginPollHttpResponse(
            status = 200,
            body = """{
                "server": "https://cloud.example.test",
                "loginName": "person",
                "appPassword": ""
            }""".trimIndent(),
            challenge = challenge(),
        )

        val failure = assertIs<LoginPollResult.AmbiguousAfterExchangeFailure>(interpretation.result)
        assertEquals("LOGIN_POLL_RESPONSE_INVALID", failure.code)
        assertNull(interpretation.approvedLoginName)
    }

    private fun challenge() = LoginChallenge(
        enteredServerUrl = "https://cloud.example.test",
        pollEndpoint = "https://cloud.example.test/login/v2/poll",
        pollFallbackEndpoint = "https://cloud.example.test/index.php/login/v2/poll",
        token = "one-time-token",
        loginUrl = "https://cloud.example.test/login/v2/flow/abc",
        transportSecurity = LoginTransportSecurity.Tls,
    )
}
