package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.DynamicAction
import dev.obiente.nextcloudnative.nativeui.model.DynamicAppDescriptor
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.hasTrustedRootMutationEvidence
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The app-relative shape of one contract route: collection words, identity placeholders, and a
 * trailing verb. Mount prefixes (`/index.php`, `/ocs/v2.php`, `/apps/{app}`), `api`, and version
 * segments are stripped so the same rules apply to every app and every published API version.
 */
internal data class SemanticRouteShape(
    val tokens: List<Token>,
    val version: List<Int>,
    val apiRoute: Boolean,
) {
    sealed interface Token {
        data class Word(val word: String) : Token
        data class Placeholder(val name: String) : Token {
            val normalized: String = name.semanticRouteId()
        }
    }

    val placeholders: List<Token.Placeholder> get() = tokens.filterIsInstance<Token.Placeholder>()

    /** Words in route order, normalized to letters and digits. */
    val words: List<String> get() = tokens.filterIsInstance<Token.Word>().map(Token.Word::word)

    companion object {
        fun of(path: String): SemanticRouteShape {
            val segments = path.split('/').filter(String::isNotEmpty).toMutableList()
            var apiRoute = false
            var version = emptyList<Int>()
            while (segments.isNotEmpty()) {
                val head = segments.first().lowercase()
                when {
                    head in ROUTE_MOUNT_SEGMENTS -> segments.removeAt(0)
                    head == "apps" && segments.size > 1 -> {
                        segments.removeAt(0)
                        segments.removeAt(0)
                    }
                    head == "api" -> {
                        apiRoute = true
                        segments.removeAt(0)
                    }
                    head.parseRouteVersion() != null -> version = checkNotNull(segments.removeAt(0).parseRouteVersion())
                    else -> break
                }
            }
            val tokens = segments.map { segment ->
                if (segment.startsWith('{') && segment.endsWith('}') && segment.length > 2) {
                    Token.Placeholder(segment.substring(1, segment.length - 1))
                } else {
                    Token.Word(segment.semanticRouteId())
                }
            }
            return SemanticRouteShape(tokens, version, apiRoute)
        }
    }
}

/** Lowercase letters and digits only, so `stack_id`, `stackId`, and `StackID` compare equal. */
internal fun String.semanticRouteId(): String = lowercase().filter(Char::isLetterOrDigit)

/** The identity stem of a placeholder or field name: `boardId` -> `board`, `id` -> null. */
internal fun String.semanticIdentityStem(): String? {
    val normalized = semanticRouteId()
    val stem = when {
        normalized.endsWith("uid") && normalized.length > 3 -> normalized.dropLast(3)
        normalized.endsWith("id") && normalized.length > 2 -> normalized.dropLast(2)
        else -> return null
    }
    return stem.takeIf(String::isNotBlank)
}

/** Declared body property names and their JSON schemas, from an exact object request schema. */
internal fun DynamicAction.declaredBodyProperties(): Map<String, JsonObject> {
    val schema = binding.body?.schema as? JsonObject ?: return emptyMap()
    val properties = schema["properties"] as? JsonObject ?: return emptyMap()
    return properties.mapNotNull { (name, value) -> (value as? JsonObject)?.let { name to it } }.toMap()
}

internal fun DynamicAction.requiredBodyProperties(): Set<String> {
    val schema = binding.body?.schema as? JsonObject ?: return emptySet()
    return (schema["required"] as? JsonArray).orEmpty()
        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        .toSet()
}

internal fun JsonObject.declaredJsonType(): String? = (this["type"] as? JsonPrimitive)?.contentOrNull
    ?: (this["type"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        ?.firstOrNull { it != "null" }

/** Whether the schema accepts JSON null (`nullable: true` or a `null` member of a type union). */
internal fun JsonObject.declaresNull(): Boolean =
    (this["nullable"] as? JsonPrimitive)?.contentOrNull == "true" ||
        (this["type"] as? JsonArray)?.any { (it as? JsonPrimitive)?.contentOrNull == "null" } == true

internal fun JsonObject.declaredEnumValues(): List<String> = (this["enum"] as? JsonArray).orEmpty()
    .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

/**
 * Whether [action] may be offered as a write from [descriptor].
 *
 * Writes come only from the installed app's own published contract (OpenAPI, signed package, or
 * linked source tag) and only while that contract is verified for the installed version.
 */
internal fun DynamicAction.isAuthorizedSemanticWrite(writesAuthorized: Boolean): Boolean =
    writesAuthorized && hasTrustedRootMutationEvidence()

internal fun DynamicAction.isSemanticRead(): Boolean =
    binding.method == HttpMethod.GET && binding.body == null

/**
 * Orders equivalent contract routes deterministically: app API routes before web routes, the
 * newest published version first, fewer path inputs, and finally the literal path.
 */
internal val semanticRoutePreference: Comparator<Pair<DynamicAction, SemanticRouteShape>> =
    compareByDescending<Pair<DynamicAction, SemanticRouteShape>> { (action, _) -> !action.fallbackOnly }
        .thenByDescending { (_, shape) -> shape.apiRoute }
        .thenComparator { left, right -> compareRouteVersions(right.second.version, left.second.version) }
        .thenBy { (_, shape) -> shape.placeholders.size }
        .thenBy { (action, _) -> action.binding.path }

internal fun DynamicAppDescriptor.semanticRoutes(): List<Pair<DynamicAction, SemanticRouteShape>> =
    actions.map { action -> action to SemanticRouteShape.of(action.binding.path) }

private fun compareRouteVersions(left: List<Int>, right: List<Int>): Int {
    for (index in 0 until maxOf(left.size, right.size)) {
        val compared = left.getOrElse(index) { 0 }.compareTo(right.getOrElse(index) { 0 })
        if (compared != 0) return compared
    }
    return 0
}

private fun String.parseRouteVersion(): List<Int>? {
    val value = removePrefix("v").removePrefix("V")
    if (value.isEmpty() || value == this && !first().isDigit()) return null
    val parts = value.split('.')
    if (parts.size > 4 || parts.any { part -> part.isEmpty() || !part.all(Char::isDigit) || part.length > 6 }) {
        return null
    }
    return parts.map(String::toInt)
}

private val ROUTE_MOUNT_SEGMENTS = setOf("index.php", "ocs", "v1.php", "v2.php")
