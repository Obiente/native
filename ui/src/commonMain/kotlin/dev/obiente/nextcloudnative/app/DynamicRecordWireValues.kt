package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.runtime.NativeRecord
import dev.obiente.nextcloudnative.nativeui.runtime.membershipObservation
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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

/** Keeps exact scalar observations separately so they never become declared full-echo fields. */
internal fun JsonObject.boundedDynamicRuleObservations(retained: (String, JsonElement) -> Boolean): Map<String, JsonElement> =
    entries.asSequence().filter { (key, value) ->
        key.length <= 64 && value is JsonPrimitive && value.toString().length <= 256 && retained(key, value)
    }.take(MAX_WIRE_FIELDS_PER_RECORD).associate { it.key to it.value }

/** Display deduplication must not turn an ambiguous or truncated response into write authority. */
internal fun List<NativeRecord>.withoutAmbiguousDynamicIdentities(complete: Boolean): List<NativeRecord> {
    val counts = groupingBy(NativeRecord::id).eachCount()
    return distinctBy(NativeRecord::id).map { record ->
        if (!complete || counts.getValue(record.id) > 1) record.copy(actionBindingProvenanceValid = false) else record
    }
}

private const val MAX_WIRE_FIELDS_PER_RECORD = 128
private const val MAX_WIRE_CHARACTERS_PER_VALUE = 16 * 1024
private const val MAX_WIRE_CHARACTERS_PER_RECORD = 64 * 1024

/** Label evidence is retained in full or explicitly unavailable, never display-truncated. */
internal fun NativeRecord.withMembershipObservation(source: JsonObject): NativeRecord =
    if ("labels" !in source) this else copy(membershipObservations = mapOf("labels" to membershipObservation(source["labels"])))
