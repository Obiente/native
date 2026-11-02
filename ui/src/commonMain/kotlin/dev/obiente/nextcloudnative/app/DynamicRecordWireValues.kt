package dev.obiente.nextcloudnative.app

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The exact JSON of every contract-declared top-level field of one read record, bounded so a
 * large or hostile response cannot multiply memory. A value over the bound is left out entirely,
 * never truncated: a truncated value would be echoed back as if it were the stored one.
 */
internal fun JsonObject.boundedDynamicWireValues(
    declaredFieldIds: Set<String>,
    retained: (String, JsonElement) -> Boolean,
): Map<String, JsonElement> {
    var remaining = MAX_WIRE_CHARACTERS_PER_RECORD
    return entries.asSequence()
        .filter { (key, value) -> declaredFieldIds.any { it.equals(key, ignoreCase = true) } && retained(key, value) }
        .take(MAX_WIRE_FIELDS_PER_RECORD)
        .mapNotNull { (key, value) ->
            val size = value.toString().length
            if (size > MAX_WIRE_CHARACTERS_PER_VALUE || size > remaining) return@mapNotNull null
            remaining -= size
            key to value
        }
        .toMap()
}

private const val MAX_WIRE_FIELDS_PER_RECORD = 128
private const val MAX_WIRE_CHARACTERS_PER_VALUE = 16 * 1024
private const val MAX_WIRE_CHARACTERS_PER_RECORD = 64 * 1024
