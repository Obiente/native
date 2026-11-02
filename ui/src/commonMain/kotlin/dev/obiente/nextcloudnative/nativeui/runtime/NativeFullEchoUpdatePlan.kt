package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/*
 * Partial versus replacement updates (technique C). Merge semantics are never learned by writing:
 * omitting a field under replace semantics destroys it. A replacement whose merge behaviour the
 * contract does not declare therefore sends the complete representation: every declared body
 * field, exactly as the server returned it, with only the person's edits substituted. That is
 * correct whether the server merges or replaces. A declared PATCH may send only the edits.
 */

internal sealed interface NativeFullEchoUpdatePlan {
    /** Send [echo] verbatim for every untouched field and encode only [edited]. */
    data class Echo(val echo: Map<String, JsonElement>, val edited: Set<String>) : NativeFullEchoUpdatePlan

    /** The contract declares a partial update; only [edited] fields are sent. */
    data class Sparse(val edited: Set<String>) : NativeFullEchoUpdatePlan

    /** The record cannot be replaced safely through this route; it stays read-only here. */
    data class ReadOnly(val reason: NativeFullEchoRefusal, val field: String) : NativeFullEchoUpdatePlan {
        val message: String = when (reason) {
            NativeFullEchoRefusal.IncompleteRepresentation ->
                "This app replaces the whole record, and \"$field\" was not part of what it returned, so editing here could clear it."
            NativeFullEchoRefusal.NotRoundTripExact ->
                "This app replaces the whole record, and \"$field\" cannot be sent back exactly as stored, so it is not edited here."
            NativeFullEchoRefusal.NonJsonBody ->
                "This app replaces the whole record with a form body, which cannot carry \"$field\" back exactly as stored, so it is not edited here."
        }
    }
}

internal enum class NativeFullEchoRefusal {
    /** A declared body field was not returned by the read, so its stored value is unknown. */
    IncompleteRepresentation,

    /** A returned value does not fit the declared field type, so echoing it is not exact. */
    NotRoundTripExact,

    /** Stored values must be echoed, but the body is not JSON, so they cannot travel verbatim. */
    NonJsonBody,
}

/**
 * Plans the update of a record whose exact stored JSON is [stored] through [action]. [edited]
 * names the body fields the person changed; they are encoded by the caller. [contextInputs] are body fields bound from navigation
 * (such as a parent reference) and are not part of the stored representation. Fields the
 * contract marks read-only are never sent.
 */
internal fun nativeFullEchoUpdatePlan(
    action: ActionSpec,
    stored: Map<String, JsonElement>,
    edited: Set<String>,
    contextInputs: Set<String> = emptySet(),
): NativeFullEchoUpdatePlan {
    if (action.binding.method == HttpMethod.PATCH) return NativeFullEchoUpdatePlan.Sparse(edited)
    val properties = (action.binding.bodySchema as? JsonObject)?.get("properties") as? JsonObject ?: JsonObject(emptyMap())
    val echo = linkedMapOf<String, JsonElement>()
    properties.forEach { (name, schema) ->
        if (name in edited || name in contextInputs || (schema as? JsonObject).isReadOnlyProperty()) return@forEach
        val value = stored.valueNamed(name)
            ?: return NativeFullEchoUpdatePlan.ReadOnly(NativeFullEchoRefusal.IncompleteRepresentation, name)
        if (!value.fitsDeclaredSchema(schema)) {
            return NativeFullEchoUpdatePlan.ReadOnly(NativeFullEchoRefusal.NotRoundTripExact, name)
        }
        echo[name] = value
    }
    // Exact stored values travel only in a JSON body; a form body would re-encode them as text.
    val nonJson = action.binding.bodyContentType?.let { it.substringBefore(';').trim().lowercase() != "application/json" } == true
    echo.keys.firstOrNull()?.takeIf { nonJson }?.let { field ->
        return NativeFullEchoUpdatePlan.ReadOnly(NativeFullEchoRefusal.NonJsonBody, field)
    }
    return NativeFullEchoUpdatePlan.Echo(echo, edited)
}

/** The exact stored value of [name]; a case-only spelling difference matches one field only. */
internal fun Map<String, JsonElement>.valueNamed(name: String): JsonElement? =
    this[name] ?: entries.filter { it.key.equals(name, ignoreCase = true) }.singleOrNull()?.value

private fun JsonObject?.isReadOnlyProperty(): Boolean = (this?.get("readOnly") as? JsonPrimitive)?.booleanOrNull == true

/**
 * Whether [this] stored value can be sent back verbatim under [schema]. An undeclared or opaque
 * schema accepts any value; a declared type must match exactly, and null needs a nullable field.
 */
internal fun JsonElement.fitsDeclaredSchema(schema: JsonElement?, depth: Int = 0): Boolean {
    val declared = schema as? JsonObject ?: return true
    if (depth > MAX_SCHEMA_DEPTH) return false
    listOf("oneOf", "anyOf").forEach { key ->
        (declared[key] as? JsonArray)?.let { alternatives -> return alternatives.any { fitsDeclaredSchema(it, depth + 1) } }
    }
    val types = when (val type = declared["type"]) {
        is JsonPrimitive -> setOfNotNull(type.contentOrNull)
        is JsonArray -> type.mapNotNullTo(mutableSetOf()) { (it as? JsonPrimitive)?.contentOrNull }
        else -> emptySet()
    }
    val nullable = (declared["nullable"] as? JsonPrimitive)?.booleanOrNull == true || "null" in types
    if (this is JsonNull) return nullable || types.isEmpty() && declared["enum"] == null
    (declared["enum"] as? JsonArray)?.let { values -> if (values.none { sameWireValue(it, this) }) return false }
    if (types.isEmpty() || types == setOf("null")) return true
    return types.any { type ->
        when (type) {
            "string" -> this is JsonPrimitive && isString
            "integer" -> this is JsonPrimitive && !isString && longOrNull != null
            "number" -> this is JsonPrimitive && !isString && doubleOrNull != null
            "boolean" -> this is JsonPrimitive && !isString && booleanOrNull != null
            "array" -> this is JsonArray && all { item -> item.fitsDeclaredSchema(declared["items"], depth + 1) }
            "object" -> this is JsonObject
            else -> false
        }
    }
}

private const val MAX_SCHEMA_DEPTH = 8
