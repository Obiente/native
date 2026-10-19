package dev.obiente.nextcloudnative.contracts

import org.json.JSONObject

/*
 * Typed reads of untrusted catalog and contract JSON. org.json's optString and optBoolean coerce
 * numbers, objects, arrays, and strings such as "true" into the requested type, so a value of the
 * wrong type could pass as a well-formed one. These reads accept only the exact JSON type.
 */

/** The string at [name], or null when it is absent, JSON null, or any other type. */
internal fun JSONObject.stringValue(name: String): String? = opt(name) as? String

/** The boolean at [name]: [absent] when missing, and [wrongType] for any other value, including JSON null. */
internal fun JSONObject.booleanValue(name: String, absent: Boolean, wrongType: Boolean): Boolean =
    when (val value = opt(name)) {
        null -> absent
        is Boolean -> value
        else -> wrongType
    }
