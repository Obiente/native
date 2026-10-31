package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.AppIdentity
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.DYNAMIC_APP_DESCRIPTOR_VERSION
import dev.obiente.nextcloudnative.nativeui.model.DynamicAction
import dev.obiente.nextcloudnative.nativeui.model.DynamicAppDescriptor
import dev.obiente.nextcloudnative.nativeui.model.DynamicResource
import dev.obiente.nextcloudnative.nativeui.model.EndpointPolicy
import dev.obiente.nextcloudnative.nativeui.model.HttpBody
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.HttpParameter
import dev.obiente.nextcloudnative.nativeui.model.ParameterSource
import dev.obiente.nextcloudnative.nativeui.model.Provenance
import dev.obiente.nextcloudnative.nativeui.model.ProvenanceKind
import dev.obiente.nextcloudnative.template.scanBracedTemplate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One synthetic contract route. Body fields map a name to its JSON type. */
internal data class FixtureRoute(
    val method: HttpMethod,
    val path: String,
    val body: Map<String, String> = emptyMap(),
    val required: Set<String> = emptySet(),
    val query: List<String> = emptyList(),
    val provenance: ProvenanceKind = ProvenanceKind.verifiedAppPackage,
    val contentType: String = "application/json",
)

internal fun get(path: String, query: List<String> = emptyList()) = FixtureRoute(HttpMethod.GET, path, query = query)

internal fun write(
    method: HttpMethod,
    path: String,
    body: Map<String, String> = emptyMap(),
    required: Set<String> = emptySet(),
) = FixtureRoute(method, path, body, required)

/** A synthetic descriptor whose actions come only from [routes]; no app-specific knowledge. */
internal fun semanticDescriptor(
    appId: String,
    version: String,
    routes: List<FixtureRoute>,
): DynamicAppDescriptor {
    val actions = routes.mapIndexed { index, route -> route.toAction("route$index") }
    return DynamicAppDescriptor(
        descriptorVersion = DYNAMIC_APP_DESCRIPTOR_VERSION,
        app = AppIdentity(appId, appId.replaceFirstChar(Char::uppercase), version),
        endpointPolicy = EndpointPolicy(
            serverOrigin = "https://cloud.example.test",
            approvedApiPrefixes = listOf("/apps/$appId", "/index.php/apps/$appId", "/ocs/v2.php/apps/$appId"),
        ),
        resources = actions.map { it.resourceId }.distinct().map { id ->
            DynamicResource(id = id, label = id, collection = true, confidence = Confidence.high)
        },
        actions = actions,
    )
}

private fun FixtureRoute.toAction(id: String): DynamicAction {
    val placeholders = path.scanBracedTemplate().tokens.map { it.name }
    val resource = path.split('/').filter { it.isNotBlank() && !it.startsWith('{') }.lastOrNull() ?: "root"
    val intent = when (method) {
        HttpMethod.GET -> if (path.trimEnd('/').endsWith("}")) ActionIntent.read else ActionIntent.list
        HttpMethod.POST -> ActionIntent.create
        HttpMethod.PUT, HttpMethod.PATCH -> ActionIntent.update
        HttpMethod.DELETE -> ActionIntent.delete
    }
    return DynamicAction(
        id = id,
        label = "$method $path",
        resourceId = resource,
        intent = intent,
        risk = when (method) {
            HttpMethod.GET -> ActionRisk.readOnly
            HttpMethod.DELETE -> ActionRisk.destructive
            else -> ActionRisk.mutating
        },
        requiresConfirmation = method == HttpMethod.DELETE,
        binding = dev.obiente.nextcloudnative.nativeui.model.DynamicHttpBinding(
            method = method,
            path = path,
            pathParameters = placeholders.map { name ->
                HttpParameter(name, required = true, schema = typeSchema("string"), source = ParameterSource.resourceField)
            },
            queryParameters = query.map { name ->
                HttpParameter(name, required = false, schema = typeSchema("string"), source = ParameterSource.userInput)
            },
            body = body.takeIf { it.isNotEmpty() }?.let { fields ->
                HttpBody(
                    contentType = contentType,
                    required = required.isNotEmpty(),
                    schema = buildJsonObject {
                        put("type", "object")
                        put("properties", JsonObject(fields.mapValues { (_, type) -> fieldSchema(type) }))
                        put("required", JsonArray(required.map(::JsonPrimitive)))
                    },
                )
            },
            apiRequestHeader = true,
        ),
        confidence = Confidence.verified,
        provenance = listOf(Provenance(provenance, "fixture", "synthetic contract")),
    )
}

private fun typeSchema(type: String) = buildJsonObject { put("type", type) }

/** `string?` declares a nullable string; `binary` a file part; `enum:a` a one-value enum. */
private fun fieldSchema(type: String): JsonObject = when {
    type == "binary" -> buildJsonObject {
        put("type", "string")
        put("format", "binary")
    }
    type.startsWith("enum:") -> buildJsonObject {
        put("type", "string")
        put("enum", JsonArray(listOf(JsonPrimitive(type.removePrefix("enum:")))))
    }
    type.endsWith("?") -> buildJsonObject {
        put("type", JsonArray(listOf(JsonPrimitive(type.removeSuffix("?")), JsonPrimitive("null"))))
    }
    else -> typeSchema(type)
}

/** A complete board-shaped contract; [lane] and [card] name the collections. */
internal fun boardRoutes(
    lane: String = "lists",
    card: String = "cards",
    laneId: String = "listId",
    includeMove: Boolean = true,
): List<FixtureRoute> {
    val base = "/apps/APP/api/v2"
    val board = "$base/boards/{boardId}"
    val laneItem = "$board/$lane/{$laneId}"
    val cardItem = "$laneItem/$card/{cardId}"
    return buildList {
        add(get("$base/boards"))
        add(get(board))
        add(write(HttpMethod.POST, "$base/boards", mapOf("title" to "string", "color" to "string"), setOf("title")))
        add(write(HttpMethod.PUT, board, mapOf("title" to "string", "color" to "string", "archived" to "boolean")))
        add(write(HttpMethod.DELETE, board))
        add(get("$board/$lane"))
        add(get("$board/$lane/archived"))
        add(write(HttpMethod.POST, "$board/$lane", mapOf("title" to "string", "order" to "integer"), setOf("title")))
        add(write(HttpMethod.PUT, laneItem, mapOf("title" to "string", "order" to "integer"), setOf("title")))
        add(write(HttpMethod.DELETE, laneItem))
        add(
            write(
                HttpMethod.POST,
                "$laneItem/$card",
                mapOf("title" to "string", "description" to "string", "order" to "integer", "duedate" to "string?"),
                setOf("title"),
            ),
        )
        add(get(cardItem))
        add(
            write(
                HttpMethod.PUT,
                cardItem,
                mapOf(
                    "title" to "string", "type" to "string", "owner" to "string", "description" to "string",
                    "order" to "integer", "duedate" to "string?", "archived" to "boolean", "done" to "string?",
                ),
                setOf("title", "type", "owner"),
            ),
        )
        add(write(HttpMethod.DELETE, cardItem))
        add(write(HttpMethod.PUT, "$cardItem/archive"))
        add(write(HttpMethod.PUT, "$cardItem/unarchive"))
        if (includeMove) {
            add(write(HttpMethod.PUT, "$base/$card/{cardId}/reorder", mapOf(laneId to "integer", "order" to "integer"), setOf(laneId, "order")))
        }
        add(write(HttpMethod.PUT, "$base/$card/{cardId}/assignLabel", mapOf("labelId" to "integer"), setOf("labelId")))
        add(write(HttpMethod.PUT, "$base/$card/{cardId}/removeLabel", mapOf("labelId" to "integer"), setOf("labelId")))
        add(write(HttpMethod.PUT, "$base/$card/{cardId}/assignUser", mapOf("userId" to "string"), setOf("userId")))
        add(write(HttpMethod.PUT, "$base/$card/{cardId}/unassignUser", mapOf("userId" to "string"), setOf("userId")))
        add(write(HttpMethod.POST, "$board/labels", mapOf("title" to "string", "color" to "string"), setOf("title")))
        add(write(HttpMethod.PUT, "$board/labels/{labelId}", mapOf("title" to "string", "color" to "string"), setOf("title")))
        add(write(HttpMethod.DELETE, "$board/labels/{labelId}"))
        add(write(HttpMethod.PUT, "$base/$card/{cardId}/comments/{commentId}", mapOf("message" to "string"), setOf("message")))
        add(write(HttpMethod.DELETE, "$base/$card/{cardId}/comments/{commentId}"))
        add(get("$cardItem/attachments"))
        add(get("$cardItem/attachments/{type}/{attachmentId}"))
        add(write(HttpMethod.DELETE, "$cardItem/attachments/{type}/{attachmentId}"))
        add(write(HttpMethod.POST, "$board/undo_delete"))
        add(get("$base/$card/{cardId}/comments", query = listOf("limit", "offset")))
        add(write(HttpMethod.POST, "$base/$card/{cardId}/comments", mapOf("message" to "string", "parentId" to "integer?"), setOf("message")))
        val sharePermissions = mapOf("permissionEdit" to "boolean", "permissionShare" to "boolean", "permissionManage" to "boolean")
        add(write(HttpMethod.POST, "$board/acl", mapOf("type" to "integer", "participant" to "string") + sharePermissions))
        add(write(HttpMethod.PUT, "$board/acl/{aclId}", sharePermissions))
        add(write(HttpMethod.DELETE, "$board/acl/{aclId}"))
        add(get("$base/config"))
        add(write(HttpMethod.POST, "$base/config/{key}", mapOf("value" to "boolean")))
    }
}

internal fun List<FixtureRoute>.forApp(appId: String) = map { it.copy(path = it.path.replace("APP", appId)) }

internal fun notesRoutes(collection: String = "notes", includeUpdate: Boolean = true): List<FixtureRoute> {
    val base = "/apps/APP/api/v1/$collection"
    return buildList {
        add(get(base, query = listOf("exclude")))
        add(get("$base/{id}"))
        add(write(HttpMethod.POST, base, mapOf("title" to "string", "content" to "string", "category" to "string")))
        if (includeUpdate) {
            add(
                write(
                    HttpMethod.PUT,
                    "$base/{id}",
                    mapOf("title" to "string", "content" to "string", "category" to "string", "favorite" to "boolean"),
                ),
            )
        }
        add(write(HttpMethod.DELETE, "$base/{id}"))
        add(get("/apps/APP/api/v1/settings"))
        add(write(HttpMethod.PUT, "/apps/APP/api/v1/settings", mapOf("notesPath" to "string", "fileSuffix" to "string")))
    }
}
