package dev.obiente.nextcloudnative.contracts

import org.json.JSONArray
import org.json.JSONObject

/**
 * Capability key names an app publishes, read from a `getCapabilities()` method whose only
 * `return` is a literal array. Keys are dotted paths; a literal scalar or list of
 * literal scalars is recorded as stated, anything computed is recorded as present but dynamic.
 * These are facts to confirm against the live capabilities response, never a substitute for it.
 */
internal data class StaticCapabilityKey(val path: String, val values: List<String>?)

internal data class StaticCapabilityFacts(val keys: List<StaticCapabilityKey>) {
    /** Keys whose last segment is `version`, such as `cookbook.version`. */
    val versionKeys: List<String> get() = keys.map(StaticCapabilityKey::path).filter { it.lastSegment() == "version" }

    /** Keys naming API versions, such as `deck.apiVersions` or `notes.api_version`. */
    val apiVersionKeys: List<String>
        get() = keys.map(StaticCapabilityKey::path).filter { it.lastSegment() in API_VERSION_SEGMENTS }

    fun toJson(): JSONObject = JSONObject()
        .put(
            "keys",
            JSONArray(
                keys.map { key ->
                    JSONObject().put("path", key.path).also { json ->
                        if (key.values == null) json.put("dynamic", true) else json.put("values", JSONArray(key.values))
                    }
                },
            ),
        )
        .put("versionKeys", JSONArray(versionKeys))
        .put("apiVersionKeys", JSONArray(apiVersionKeys))

    private fun String.lastSegment(): String = substringAfterLast('.').lowercase().filter(Char::isLetterOrDigit)
}

internal fun readCapabilityFacts(declarations: List<PhpClassDeclaration>): StaticCapabilityFacts? {
    val keys = declarations.flatMap { declaration ->
        val method = declaration.methods.firstOrNull { it.public && it.name.equals("getCapabilities", ignoreCase = true) }
            ?: return@flatMap emptyList()
        val body = methodBodyTokens(declaration.tokens, method.nameIndex) ?: return@flatMap emptyList()
        // Exactly one return anywhere, so a conditional branch cannot publish other keys.
        val returns = body.indices.filter { index -> (body[index] as? PhpToken.Word)?.value == "return" }
        val returnIndex = returns.singleOrNull() ?: return@flatMap emptyList()
        val value = readPhpValueExpression(body, returnIndex + 1) as? PhpValue.Array ?: return@flatMap emptyList()
        buildList { collectCapabilityKeys(value, prefix = "", depth = 0) }
    }.distinctBy(StaticCapabilityKey::path).take(MAX_CAPABILITY_KEYS)
    return StaticCapabilityFacts(keys).takeIf { keys.isNotEmpty() }
}

private fun MutableList<StaticCapabilityKey>.collectCapabilityKeys(value: PhpValue.Array, prefix: String, depth: Int) {
    if (depth > MAX_CAPABILITY_DEPTH) return
    value.entries.forEach { (key, entry) ->
        val name = (key as? PhpValue.Text)?.value?.takeIf(String::isSafeFactValue) ?: return@forEach
        val path = if (prefix.isEmpty()) name else "$prefix.$name"
        if (size >= MAX_CAPABILITY_KEYS || path.length > MAX_CAPABILITY_PATH_CHARS) return
        when {
            entry is PhpValue.Array && entry.entries.isNotEmpty() && entry.entries.all { it.first is PhpValue.Text } ->
                collectCapabilityKeys(entry, path, depth + 1)
            entry is PhpValue.Array && entry.entries.all { it.first == null } -> {
                val literals = entry.entries.map { it.second.literalText() }
                add(StaticCapabilityKey(path, literals.takeIf { values -> values.all { it != null } }?.filterNotNull()?.take(32)))
            }
            else -> add(StaticCapabilityKey(path, entry.literalText()?.takeIf(String::isSafeFactValue)?.let(::listOf)))
        }
    }
}

private val API_VERSION_SEGMENTS = setOf("apiversion", "apiversions")
private const val MAX_CAPABILITY_KEYS = 256
private const val MAX_CAPABILITY_DEPTH = 6
private const val MAX_CAPABILITY_PATH_CHARS = 256
