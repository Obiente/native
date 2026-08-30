package dev.obiente.nextcloudnative.nativeui.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal fun resolveOpenApiPath(base: String, path: String, policy: EndpointPolicy): String =
    if (path.isApproved(policy)) path else combineOpenApiPaths(base, path)

private fun combineOpenApiPaths(base: String, path: String): String =
    "${base.trimEnd('/')}/${path.trimStart('/')}"

internal fun String.isApproved(policy: EndpointPolicy): Boolean =
    isSafeRelativePath() && policy.approvedApiPrefixes.any(::matchesPrefix)

/** Only resolved operation parameters can authorize a version, including operation overrides. */
internal fun serverApiVersionDefault(base: String, parameter: HttpParameter): String? {
    if (parameter.name.lowercase().filter(Char::isLetterOrDigit) !in OPEN_API_VERSION_PARAMETER_NAMES) return null
    val schema = parameter.schema as? JsonObject ?: return null
    // Unknown validation keywords must remain runtime inputs, not silently bypassed constraints.
    if (schema.keys.any { it !in OPEN_API_VERSION_SCHEMA_KEYS }) return null
    if (schema["type"]?.let { it != JsonPrimitive("string") } == true) return null
    val segments = base.trim('/').split('/')
    val apiIndex = segments.indexOfLast { segment -> segment.equals("api", ignoreCase = true) }
    if (apiIndex < 0) return null
    val defaultVersion = segments.getOrNull(apiIndex + 1)
        ?.takeIf { value -> value.matches(OPEN_API_VERSION_VALUE) }
        ?: return null
    val value = JsonPrimitive(defaultVersion)
    if ("enum" in schema && (schema["enum"] as? JsonArray)?.contains(value) != true) return null
    if ("default" in schema && schema["default"] != value) return null
    return defaultVersion
}

internal fun documentedOpenApiPathDefault(parameter: HttpParameter, base: String): String? {
    val schema = parameter.schema as? JsonObject ?: return null
    val declared = schema["default"] ?: (schema["enum"] as? JsonArray)?.singleOrNull()
    if (declared == null) return serverApiVersionDefault(base, parameter)
    if ("enum" in schema && (schema["enum"] as? JsonArray)?.contains(declared) != true) return null
    return (declared as? JsonPrimitive)?.contentOrNull
}

private val OPEN_API_VERSION_SCHEMA_KEYS = setOf("type", "enum", "default", "description", "title", "example", "examples", "deprecated")
private val OPEN_API_VERSION_VALUE = Regex("(?i)v?[0-9]+(?:[.][0-9]+)*")
private val OPEN_API_VERSION_PARAMETER_NAMES = setOf("apiversion")
