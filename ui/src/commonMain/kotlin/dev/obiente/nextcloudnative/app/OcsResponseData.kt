package dev.obiente.nextcloudnative.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The `ocs.data` element of a successful OCS JSON response, or null when the response is not
 * JSON, not OCS, or reports a failure status code.
 */
internal fun NextcloudApiResponse.successfulOcsData(): JsonElement? {
    if (status !in 200..299 || contentType?.contains("json", ignoreCase = true) != true) return null
    val root = try {
        ocsResponseJson.parseToJsonElement(body.decodeToString()) as? JsonObject
    } catch (_: IllegalArgumentException) {
        null
    } ?: return null
    val ocs = root["ocs"] as? JsonObject ?: return null
    val meta = ocs["meta"] as? JsonObject ?: return null
    val statusCode = (meta["statuscode"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
    if (statusCode !in OCS_SUCCESS_STATUS_CODES) return null
    return ocs["data"]
}

private val ocsResponseJson = Json { ignoreUnknownKeys = true }
private val OCS_SUCCESS_STATUS_CODES = setOf(100, 200)
