package dev.obiente.nextcloudnative.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

private const val BRIDGE_APP_ID = "obiente_native_bridge"
private const val SUPPORTED_BRIDGE_API_VERSION = 1
private const val EXPECTED_TOKEN_METHOD = "POST"
private const val EXPECTED_DAV_HEADER = "X-Recognize-Api-Key"
private const val BRIDGE_TOKEN_ENDPOINT =
    "/ocs/v2.php/apps/$BRIDGE_APP_ID/api/v1/recognize/token"
private const val TOKEN_RESPONSE_LIMIT_BYTES = 64L * 1024L
private const val MAX_TOKEN_LENGTH = 16 * 1024

/**
 * The capability advertised by the optional Obiente server companion.
 *
 * This contains no credential. A token only exists after the explicit mint request succeeds.
 */
data class RecognizeBridgeCapability(
    val bridgeApiVersion: Int,
    val recognizeVersion: String?,
    val minimumRecognizeVersion: String,
    val tokenEndpoint: String,
    val davHeaderName: String,
    val tokenLifetimeSeconds: Long,
)

sealed interface RecognizeBridgeDiscovery {
    data object NotAdvertised : RecognizeBridgeDiscovery

    data class ServerUnavailable(
        val reason: String?,
        val recognizeVersion: String?,
    ) : RecognizeBridgeDiscovery

    data class UnsupportedApiVersion(val advertisedVersion: Int?) : RecognizeBridgeDiscovery

    data class InvalidAdvertisement(val reason: String) : RecognizeBridgeDiscovery

    data class Available(val capability: RecognizeBridgeCapability) : RecognizeBridgeDiscovery
}

sealed interface RecognizeBridgeTokenRequestPlan {
    data class Ready(
        val capability: RecognizeBridgeCapability,
        val request: NextcloudApiRequest,
    ) : RecognizeBridgeTokenRequestPlan

    data class Unavailable(val discovery: RecognizeBridgeDiscovery) : RecognizeBridgeTokenRequestPlan
}

/**
 * A sensitive credential model whose string representation is deliberately redacted.
 *
 * The value must be scoped to the connected server and sent only under [headerName] to that
 * server's `/remote.php/dav/recognize/` collection. No generic request or logging model owns it.
 */
class RecognizeBridgeToken internal constructor(
    val value: String,
    val headerName: String,
    val expiresInSeconds: Long,
    val expiresAt: String,
    val recognizeVersion: String,
) {
    override fun toString(): String =
        "RecognizeBridgeToken(value=[redacted], headerName=$headerName, " +
            "expiresInSeconds=$expiresInSeconds, expiresAt=$expiresAt, " +
            "recognizeVersion=$recognizeVersion)"
}

sealed interface RecognizeBridgeTokenParseResult {
    data class Success(val token: RecognizeBridgeToken) : RecognizeBridgeTokenParseResult

    data class Failure(
        val status: Int,
        val reason: RecognizeBridgeTokenFailure,
    ) : RecognizeBridgeTokenParseResult
}

enum class RecognizeBridgeTokenFailure {
    RequestRejected,
    InvalidResponse,
    InvalidToken,
    ContractMismatch,
}

/**
 * Parses only the Obiente bridge capability subtree from the normal Nextcloud capabilities map.
 * Malformed or broadened advertisements never produce an executable request.
 */
fun discoverRecognizeBridge(capabilitiesJson: String): RecognizeBridgeDiscovery {
    val capabilities = runCatching { Json.parseToJsonElement(capabilitiesJson).jsonObject }
        .getOrElse { return RecognizeBridgeDiscovery.InvalidAdvertisement("Capabilities JSON is invalid.") }
    val bridgeElement = capabilities[BRIDGE_APP_ID] ?: return RecognizeBridgeDiscovery.NotAdvertised
    val bridge = bridgeElement as? JsonObject
        ?: return RecognizeBridgeDiscovery.InvalidAdvertisement("The bridge capability is not an object.")
    val apiVersion = bridge.primitiveInt("api_version")
    if (apiVersion != SUPPORTED_BRIDGE_API_VERSION) {
        return RecognizeBridgeDiscovery.UnsupportedApiVersion(apiVersion)
    }

    val recognize = bridge["recognize"] as? JsonObject
        ?: return RecognizeBridgeDiscovery.InvalidAdvertisement("The Recognize capability is missing.")
    val available = recognize.primitiveBoolean("available")
        ?: return RecognizeBridgeDiscovery.InvalidAdvertisement("Recognize availability is missing.")
    val recognizeVersion = recognize.primitiveString("recognize_version")
    if (!available) {
        return RecognizeBridgeDiscovery.ServerUnavailable(
            reason = recognize.primitiveString("reason"),
            recognizeVersion = recognizeVersion,
        )
    }

    val minimumVersion = recognize.primitiveString("minimum_recognize_version")
        ?: return RecognizeBridgeDiscovery.InvalidAdvertisement("The minimum Recognize version is missing.")
    val endpoint = recognize.primitiveString("token_endpoint")
        ?: return RecognizeBridgeDiscovery.InvalidAdvertisement("The token endpoint is missing.")
    val method = recognize.primitiveString("method")
    val ocsRequired = recognize.primitiveBoolean("ocs_api_request_required")
    val header = recognize.primitiveString("dav_header")
        ?: return RecognizeBridgeDiscovery.InvalidAdvertisement("The bridge DAV header is missing.")
    val lifetime = recognize.primitiveLong("expires_in")
        ?: return RecognizeBridgeDiscovery.InvalidAdvertisement("The bridge token lifetime is missing.")

    val invalidReason = when {
        minimumVersion.isBlank() -> "The minimum Recognize version is empty."
        method != EXPECTED_TOKEN_METHOD -> "The bridge token method is unsupported."
        ocsRequired != true -> "The bridge must require an OCS API request."
        header != EXPECTED_DAV_HEADER -> "The bridge DAV header is unsupported."
        endpoint != BRIDGE_TOKEN_ENDPOINT -> "The bridge token endpoint does not match API version 1."
        lifetime !in 1..86_400L -> "The bridge token lifetime is invalid."
        else -> null
    }
    if (invalidReason != null) return RecognizeBridgeDiscovery.InvalidAdvertisement(invalidReason)

    return RecognizeBridgeDiscovery.Available(
        RecognizeBridgeCapability(
            bridgeApiVersion = apiVersion,
            recognizeVersion = recognizeVersion,
            minimumRecognizeVersion = minimumVersion,
            tokenEndpoint = endpoint,
            davHeaderName = header,
            tokenLifetimeSeconds = lifetime,
        ),
    )
}

fun planRecognizeBridgeTokenRequest(
    discovery: RecognizeBridgeDiscovery,
): RecognizeBridgeTokenRequestPlan = when (discovery) {
    is RecognizeBridgeDiscovery.Available -> RecognizeBridgeTokenRequestPlan.Ready(
        capability = discovery.capability,
        request = NextcloudApiRequest(
            method = NextcloudApiMethod.POST,
            relativePath = discovery.capability.tokenEndpoint,
            queryParameters = mapOf("format" to "json"),
            ocsApiRequest = true,
            maximumResponseBytes = TOKEN_RESPONSE_LIMIT_BYTES,
        ).requireSafe(),
    )

    else -> RecognizeBridgeTokenRequestPlan.Unavailable(discovery)
}

/**
 * Decodes the small OCS response without executing a request or persisting the returned secret.
 */
fun parseRecognizeBridgeTokenResponse(
    response: NextcloudApiResponse,
    capability: RecognizeBridgeCapability,
): RecognizeBridgeTokenParseResult {
    if (response.status !in 200..299) {
        return RecognizeBridgeTokenParseResult.Failure(
            status = response.status,
            reason = RecognizeBridgeTokenFailure.RequestRejected,
        )
    }
    val data = runCatching {
        Json.parseToJsonElement(response.body.decodeToString())
            .jsonObject["ocs"]
            ?.jsonObject
            ?.get("data")
            ?.jsonObject
    }.getOrNull() ?: return RecognizeBridgeTokenParseResult.Failure(
        status = response.status,
        reason = RecognizeBridgeTokenFailure.InvalidResponse,
    )

    val tokenValue = data.primitiveString("token")
    val headerName = data.primitiveString("header_name")
    val expiresIn = data.primitiveLong("expires_in")
    val expiresAt = data.primitiveString("expires_at")
    val recognizeVersion = data.primitiveString("recognize_version")
    if (
        tokenValue == null || tokenValue.isBlank() || tokenValue.length > MAX_TOKEN_LENGTH ||
        tokenValue.any(Char::isISOControl)
    ) {
        return RecognizeBridgeTokenParseResult.Failure(
            status = response.status,
            reason = RecognizeBridgeTokenFailure.InvalidToken,
        )
    }
    if (
        headerName != capability.davHeaderName ||
        expiresIn == null || expiresIn !in 1..capability.tokenLifetimeSeconds ||
        expiresAt.isNullOrBlank() || recognizeVersion.isNullOrBlank()
    ) {
        return RecognizeBridgeTokenParseResult.Failure(
            status = response.status,
            reason = RecognizeBridgeTokenFailure.ContractMismatch,
        )
    }

    return RecognizeBridgeTokenParseResult.Success(
        RecognizeBridgeToken(
            value = tokenValue,
            headerName = headerName,
            expiresInSeconds = expiresIn,
            expiresAt = expiresAt,
            recognizeVersion = recognizeVersion,
        ),
    )
}

private fun JsonObject.primitiveString(name: String): String? =
    (get(name) as? JsonPrimitive)?.content?.takeUnless { it == "null" }

private fun JsonObject.primitiveInt(name: String): Int? = (get(name) as? JsonPrimitive)?.intOrNull

private fun JsonObject.primitiveLong(name: String): Long? = (get(name) as? JsonPrimitive)?.longOrNull

private fun JsonObject.primitiveBoolean(name: String): Boolean? = (get(name) as? JsonPrimitive)?.booleanOrNull
