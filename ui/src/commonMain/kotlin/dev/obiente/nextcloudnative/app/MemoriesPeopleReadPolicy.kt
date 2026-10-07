package dev.obiente.nextcloudnative.app

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class MemoriesPeopleSetupRequired : IllegalStateException(
    "People recognition is unavailable. Ask your server administrator to enable a compatible " +
        "Recognize app and turn on face recognition, then try again.",
)

/** Applies only to GET /apps/memories/api/clusters/{backend}, never mutation preconditions. */
fun requireMemoriesPeopleListSuccess(backend: String, status: Int, body: String) {
    if (status in 200..299) return
    // Memories 9.0.1 Exceptions::NotEnabled and Util::recognizeIsEnabled define this exact signal.
    // https://github.com/pulsejet/memories/blob/v9.0.1/lib/Exceptions.php
    // https://github.com/pulsejet/memories/blob/v9.0.1/lib/Util.php
    if (backend == "recognize" && status == 412 && body.length <= 4_096) {
        val document = try {
            Json.parseToJsonElement(body) as? JsonObject
        } catch (_: SerializationException) {
            null
        }
        if (document?.get("message") == JsonPrimitive(RECOGNIZE_UNAVAILABLE_MESSAGE)) {
            throw MemoriesPeopleSetupRequired()
        }
    }
    throw IllegalStateException("Loading people from Memories failed (HTTP $status).")
}

private const val RECOGNIZE_UNAVAILABLE_MESSAGE =
    "Recognize app not enabled or not the required version."
