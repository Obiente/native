package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RecognizeBridgeTest {
    @Test
    fun treatsMissingBridgeAsOptional() {
        assertEquals(
            RecognizeBridgeDiscovery.NotAdvertised,
            discoverRecognizeBridge("""{"files": {"bigfilechunking": true}}"""),
        )
    }

    @Test
    fun discoversSupportedBridgeAndPlansBoundedPost() {
        val discovery = discoverRecognizeBridge(validCapabilities())
        val available = assertIs<RecognizeBridgeDiscovery.Available>(discovery)

        assertEquals(1, available.capability.bridgeApiVersion)
        assertEquals("12.0.0", available.capability.recognizeVersion)
        assertEquals("X-Recognize-Api-Key", available.capability.davHeaderName)
        assertEquals(86_400L, available.capability.tokenLifetimeSeconds)

        val ready = assertIs<RecognizeBridgeTokenRequestPlan.Ready>(
            planRecognizeBridgeTokenRequest(discovery),
        )
        assertEquals(NextcloudApiMethod.POST, ready.request.method)
        assertEquals(
            "/ocs/v2.php/apps/obiente_native_bridge/api/v1/recognize/token",
            ready.request.relativePath,
        )
        assertEquals(mapOf("format" to "json"), ready.request.queryParameters)
        assertTrue(ready.request.ocsApiRequest)
        assertEquals(64L * 1024L, ready.request.maximumResponseBytes)
        assertEquals(null, ready.request.body)
    }

    @Test
    fun keepsServerReportedUnavailabilityNonExecutable() {
        val discovery = discoverRecognizeBridge(
            validCapabilities(
                recognize = """
                    {
                      "available": false,
                      "reason": "recognize_disabled",
                      "recognize_version": null,
                      "minimum_recognize_version": "11.0.0",
                      "token_endpoint": "/ocs/v2.php/apps/obiente_native_bridge/api/v1/recognize/token",
                      "method": "POST",
                      "ocs_api_request_required": true,
                      "dav_header": "X-Recognize-Api-Key",
                      "expires_in": 86400
                    }
                """.trimIndent(),
            ),
        )

        val unavailable = assertIs<RecognizeBridgeDiscovery.ServerUnavailable>(discovery)
        assertEquals("recognize_disabled", unavailable.reason)
        assertIs<RecognizeBridgeTokenRequestPlan.Unavailable>(
            planRecognizeBridgeTokenRequest(discovery),
        )
    }

    @Test
    fun rejectsBroadenedOrMalformedCapabilityAdvertisements() {
        val cases = listOf(
            validCapabilities(endpoint = "https://attacker.example/token"),
            validCapabilities(endpoint = "/ocs/v2.php/apps/obiente_native_bridge/../files/token"),
            validCapabilities(method = "GET"),
            validCapabilities(header = "Authorization"),
            validCapabilities(ocsRequired = false),
            validCapabilities(expiresIn = 604_800),
        )

        cases.forEach { fixture ->
            assertIs<RecognizeBridgeDiscovery.InvalidAdvertisement>(discoverRecognizeBridge(fixture))
        }
    }

    @Test
    fun rejectsUnsupportedBridgeApiVersionsBeforePlanning() {
        val discovery = discoverRecognizeBridge(validCapabilities(apiVersion = 2))
        assertEquals(RecognizeBridgeDiscovery.UnsupportedApiVersion(2), discovery)
        assertIs<RecognizeBridgeTokenRequestPlan.Unavailable>(
            planRecognizeBridgeTokenRequest(discovery),
        )
    }

    @Test
    fun parsesOcsTokenWithoutLeakingItThroughToString() {
        val capability = assertIs<RecognizeBridgeDiscovery.Available>(
            discoverRecognizeBridge(validCapabilities()),
        ).capability
        val secret = "opaque-encrypted-recognize-token"
        val result = parseRecognizeBridgeTokenResponse(
            response = NextcloudApiResponse(
                status = 200,
                body = """
                    {
                      "ocs": {
                        "meta": {"status": "ok", "statuscode": 100},
                        "data": {
                          "token": "$secret",
                          "header_name": "X-Recognize-Api-Key",
                          "expires_in": 86400,
                          "expires_at": "2026-07-23T21:00:00+00:00",
                          "recognize_version": "12.0.0"
                        }
                      }
                    }
                """.trimIndent().encodeToByteArray(),
                contentType = "application/json",
                etag = null,
            ),
            capability = capability,
        )

        val token = assertIs<RecognizeBridgeTokenParseResult.Success>(result).token
        assertEquals(secret, token.value)
        assertEquals("X-Recognize-Api-Key", token.headerName)
        assertFalse(token.toString().contains(secret))
        assertTrue(token.toString().contains("[redacted]"))
    }

    @Test
    fun rejectsTokenResponsesThatBroadenTheAdvertisedContract() {
        val capability = assertIs<RecognizeBridgeDiscovery.Available>(
            discoverRecognizeBridge(validCapabilities()),
        ).capability
        val response = NextcloudApiResponse(
            status = 200,
            body = """
                {"ocs":{"data":{
                  "token":"opaque",
                  "header_name":"Authorization",
                  "expires_in":604800,
                  "expires_at":"2026-07-30T21:00:00+00:00",
                  "recognize_version":"12.0.0"
                }}}
            """.trimIndent().encodeToByteArray(),
            contentType = "application/json",
            etag = null,
        )

        val failure = assertIs<RecognizeBridgeTokenParseResult.Failure>(
            parseRecognizeBridgeTokenResponse(response, capability),
        )
        assertEquals(RecognizeBridgeTokenFailure.ContractMismatch, failure.reason)
    }

    @Test
    fun doesNotParseRejectedRequestsAsCredentials() {
        val capability = assertIs<RecognizeBridgeDiscovery.Available>(
            discoverRecognizeBridge(validCapabilities()),
        ).capability
        val failure = assertIs<RecognizeBridgeTokenParseResult.Failure>(
            parseRecognizeBridgeTokenResponse(
                NextcloudApiResponse(503, ByteArray(0), "application/json", null),
                capability,
            ),
        )
        assertEquals(503, failure.status)
        assertEquals(RecognizeBridgeTokenFailure.RequestRejected, failure.reason)
    }

    private fun validCapabilities(
        apiVersion: Int = 1,
        endpoint: String = "/ocs/v2.php/apps/obiente_native_bridge/api/v1/recognize/token",
        method: String = "POST",
        header: String = "X-Recognize-Api-Key",
        ocsRequired: Boolean = true,
        expiresIn: Long = 86_400,
        recognize: String? = null,
    ): String = """
        {
          "obiente_native_bridge": {
            "api_version": $apiVersion,
            "recognize": ${recognize ?: """
              {
                "available": true,
                "reason": null,
                "recognize_version": "12.0.0",
                "minimum_recognize_version": "11.0.0",
                "token_endpoint": "$endpoint",
                "method": "$method",
                "ocs_api_request_required": $ocsRequired,
                "dav_header": "$header",
                "expires_in": $expiresIn
              }
            """.trimIndent()}
          }
        }
    """.trimIndent()
}
