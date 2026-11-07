package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.*
import kotlinx.serialization.json.Json

/** Typed E2 fixture, deliberately independent of app names and exact versions. */
internal fun membershipContract(app: String = "planning-app", nested: Boolean = false,
    memberType: String = "integer", targetType: String = "integer", renameParents: Boolean = false,
    includeInverse: Boolean = true): DynamicAppDescriptor {
    val root = "/apps/$app/api"
    val target = if (nested) "$root/boards/{boardId}/stacks/{stackId}/cards/{cardId}" else "$root/cards/{cardId}"
    val read = target.replace("{cardId}", "{id}").let {
        if (renameParents) it.replace("{boardId}", "{workspaceId}").replace("{stackId}", "{laneId}") else it
    }
    fun parameters(path: String) = path.split('/').filter { it.startsWith('{') }.joinToString(",") {
        val name = it.removeSurrounding("{", "}")
        val type = if (name in setOf("id", "cardId")) targetType else "integer"
        """{"name":"$name","in":"path","required":true,"schema":{"type":"$type"}}"""
    }
    val card = """{"type":"object","properties":{"id":{"type":"$targetType"},"title":{"type":"string"},"labels":{"type":"array","items":{"type":"object","properties":{"id":{"type":"$memberType"},"title":{"type":"string"}}}}}}"""
    fun responses(schema: String) = """{"200":{"description":"OK","content":{"application/json":{"schema":$schema}}}}"""
    fun get(path: String, id: String, schema: String) = """"$path":{"get":{"operationId":"$id","parameters":[${parameters(path)}],"responses":${responses(schema)}}}"""
    val paths = mutableListOf(get(read, "get-card", card),
        get("$root/boards", "list-boards", """{"type":"array","items":{"type":"object","properties":{"id":{"type":"integer"},"title":{"type":"string"}}}}"""),
        get("$root/boards/{boardId}/stacks", "list-stacks", """{"type":"array","items":{"type":"object","properties":{"id":{"type":"integer"},"title":{"type":"string"}}}}"""))
    for (command in if (includeInverse) listOf("assignLabel", "removeLabel") else listOf("assignLabel")) paths += """"$target/$command":{"post":{"operationId":"$command","parameters":[${parameters(target)}],"requestBody":{"required":true,"content":{"application/json":{"schema":{"type":"object","required":["labelId"],"properties":{"labelId":{"type":"$memberType"}}}}}},"responses":{"200":{"description":"OK"}}}}"""
    return DynamicAppDescriptorCompiler().compile(DynamicDiscoveryInput(AppIdentity(app, app, "909.72-unknown"),
        EndpointPolicy("https://cloud.example.test", listOf("/apps/$app")),
        advertisedOpenApi = AdvertisedOpenApi("/apps/$app/openapi.json", Json.parseToJsonElement(
            """{"openapi":"3.0.3","paths":{${paths.joinToString(",")}}}"""), OpenApiTrust.sameOriginAdvertisement)))
}
