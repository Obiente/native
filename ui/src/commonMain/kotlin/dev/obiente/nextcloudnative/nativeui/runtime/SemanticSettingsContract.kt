package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.DynamicAction
import dev.obiente.nextcloudnative.nativeui.model.DynamicAppDescriptor
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.runtime.SemanticRouteShape.Token
import kotlinx.serialization.json.JsonObject

/**
 * How an app's own contract lets a client change its settings.
 *
 * [Document] replaces named settings through one route whose body declares each setting.
 * [Keyed] writes one setting at a time through a `{key}` route with a declared value field; the
 * key always comes from the settings the server returned, never from a client-made format.
 */
internal sealed interface SemanticSettingsWrite {
    val action: DynamicAction

    data class Document(
        override val action: DynamicAction,
        val properties: Map<String, JsonObject>,
    ) : SemanticSettingsWrite

    data class Keyed(
        override val action: DynamicAction,
        val keyParameter: String,
        val valueField: String,
        val valueSchema: JsonObject,
    ) : SemanticSettingsWrite
}

internal data class SemanticSettingsContract(
    val read: DynamicAction,
    val write: SemanticSettingsWrite?,
)

/** Resolves an app-level settings document from [descriptor], or null when none is described. */
internal fun resolveSemanticSettingsContract(
    descriptor: DynamicAppDescriptor,
    writesAuthorized: Boolean,
): SemanticSettingsContract? {
    val routes = descriptor.semanticRoutes()
    val read = routes.filter { (action, shape) ->
        action.isSemanticRead() && action.risk == ActionRisk.readOnly &&
            shape.tokens.singleOrNull()?.let { it is Token.Word && it.word in SETTINGS_WORDS } == true &&
            action.binding.queryParameters.none { it.required }
    }.sortedWith(semanticRoutePreference).firstOrNull() ?: return null
    val readWord = (read.second.tokens.single() as Token.Word).word
    val writes = routes.filter { (action, shape) ->
        action.binding.method in SETTINGS_WRITE_METHODS &&
            action.risk != ActionRisk.readOnly &&
            action.isAuthorizedSemanticWrite(writesAuthorized) &&
            (shape.tokens.firstOrNull() as? Token.Word)?.word == readWord
    }.sortedWith(semanticRoutePreference)
    val document = writes.firstNotNullOfOrNull { (action, shape) ->
        val properties = action.declaredBodyProperties()
        if (shape.tokens.size != 1 || properties.isEmpty() || action.binding.queryParameters.any { it.required }) {
            null
        } else {
            SemanticSettingsWrite.Document(action, properties)
        }
    }
    val keyed = writes.firstNotNullOfOrNull { (action, shape) ->
        val key = (shape.tokens.getOrNull(1) as? Token.Placeholder)?.takeIf { shape.tokens.size == 2 }
            ?: return@firstNotNullOfOrNull null
        val properties = action.declaredBodyProperties()
        val value = properties.entries.singleOrNull { it.key.semanticRouteId() == "value" }
            ?: return@firstNotNullOfOrNull null
        if (properties.size != 1 || action.binding.queryParameters.any { it.required }) return@firstNotNullOfOrNull null
        SemanticSettingsWrite.Keyed(action, key.name, value.key, value.value)
    }
    return SemanticSettingsContract(read = read.first, write = document ?: keyed)
}

private val SETTINGS_WORDS = setOf("settings", "setting", "config", "configuration", "preferences")
private val SETTINGS_WRITE_METHODS = setOf(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH)
