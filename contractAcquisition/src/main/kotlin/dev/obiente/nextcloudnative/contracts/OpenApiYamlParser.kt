package dev.obiente.nextcloudnative.contracts

import org.json.JSONArray
import org.json.JSONObject
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

internal fun parseYamlObject(source: String): JSONObject? {
    val settings = LoadSettings.builder()
        .setLabel("OpenAPI contract")
        .setAllowDuplicateKeys(false)
        .setMaxAliasesForCollections(MAX_YAML_ALIASES)
        .setCodePointLimit(MAX_SELECTED_FILE_BYTES.toInt())
        .build()
    val loaded = Load(settings).loadFromString(source) ?: return null
    return yamlToJson(loaded, 0, intArrayOf(0)) as? JSONObject
}

private fun yamlToJson(value: Any?, depth: Int, nodes: IntArray): Any {
    check(depth <= MAX_YAML_DEPTH) { "The OpenAPI YAML is nested too deeply." }
    nodes[0] += 1
    check(nodes[0] <= MAX_YAML_NODES) { "The OpenAPI YAML has too many values." }
    return when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().also { result ->
            value.forEach { (key, child) ->
                check(key is String || key is Number || key is Boolean) {
                    "OpenAPI YAML object keys must be scalar values."
                }
                result.put(key.toString(), yamlToJson(child, depth + 1, nodes))
            }
        }
        is Iterable<*> -> JSONArray().also { result ->
            value.forEach { child -> result.put(yamlToJson(child, depth + 1, nodes)) }
        }
        is String, is Number, is Boolean -> value
        else -> value.toString()
    }
}

private const val MAX_YAML_ALIASES = 32
private const val MAX_YAML_DEPTH = 100
private const val MAX_YAML_NODES = 250_000
