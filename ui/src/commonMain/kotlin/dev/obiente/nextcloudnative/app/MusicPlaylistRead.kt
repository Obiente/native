package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.*
import kotlinx.serialization.json.*

/** Reviewed Music 3.2.1 read option; unsupported versions retain their declared default behavior. */
internal fun reviewedPlaylistReadValues(
    descriptor: DynamicAppDescriptor,
    action: DynamicAction,
    values: Map<String, String>,
): Map<String, String> {
    if (descriptor.app.id != "music" || descriptor.app.version != "3.2.1" ||
        action.binding.method != HttpMethod.GET || action.risk != ActionRisk.readOnly ||
        action.intent != ActionIntent.read || action.confidence < Confidence.high ||
        action.binding.path.removePrefix("/index.php") != "/apps/music/api/playlists/{id}" ||
        !action.binding.path.isApproved(descriptor.endpointPolicy) ||
        action.provenance.none { it.kind == ProvenanceKind.verifiedAppPackage } ||
        action.binding.pathParameters.singleOrNull()?.name != "id" || "fulltree" in values) return values
    val parameter = action.binding.queryParameters.singleOrNull { it.name == "fulltree" } ?: return values
    val schema = parameter.schema as? JsonObject ?: return values
    val options = schema["anyOf"] as? JsonArray ?: schema["oneOf"] as? JsonArray ?: return values
    val types = options.map { ((it as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull }
    if (types.any { it !in setOf("boolean", "integer", "string", "null") } || "boolean" !in types) return values
    return values + ("fulltree" to "true")
}
