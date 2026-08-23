package dev.obiente.nextcloudnative.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

internal fun dashboardOcsStatusCode(response: NextcloudApiResponse): Int? {
    if (response.status !in 200..299) return null
    return runCatching {
        val root = Json.parseToJsonElement(response.body.decodeToString()).jsonObject
        val ocs = root["ocs"] as? JsonObject
        val meta = ocs?.get("meta") as? JsonObject
        (meta?.get("statuscode") as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
    }.getOrNull()
}
