package dev.obiente.nextcloudnative.contracts

import org.json.JSONArray
import org.json.JSONObject

// The 0.1.0 and 0.2.0 packages have byte-identical API controllers and route/response contracts.
// The 0.2.0 server mapper only changes database-specific due-date serialization.
internal fun isVerifiedChoresControllerDigest(appVersion: String, controllerSha256: String): Boolean =
    appVersion in setOf("0.1.0", "0.2.0") && controllerSha256 == CHORES_API_CONTROLLER_SHA256

private const val CHORES_API_CONTROLLER_SHA256 =
    "146286dcb68bddd025e0a47e7edc134fbc94f0e9f594e9030663bb0f217f3cc6"

internal fun isVerifiedChoresController(
    appId: String,
    appVersion: String,
    controller: StaticApiController,
    controllerSource: String?,
): Boolean =
    appId == "chores" && controller.normalizedName == "api" && controllerSource != null &&
        isVerifiedChoresControllerDigest(appVersion, controllerSource.sha256())

/**
 * Declares the exact identities and invitation bindings serialized by the pinned Chores
 * controller. These response shapes are coupled to the same signed-source digest as the write
 * adapter so record values can authorize mutations only while both sides of the contract match.
 */
internal fun verifiedChoresResponseSchema(
    appId: String,
    appVersion: String,
    route: StaticRoute,
    fullPath: String,
    controller: StaticApiController,
    controllerSource: String?,
): JSONObject? {
    if (route.verb != "GET" || !isVerifiedChoresController(
            appId = appId,
            appVersion = appVersion,
            controller = controller,
            controllerSource = controllerSource,
        )
    ) {
        return null
    }
    val inviteSchema = closedObjectSchema(
        properties = mapOf(
            "inviteId" to stringSchema(title = "Invitation"),
            "teamId" to integerSchema(title = "Team"),
            "teamName" to stringSchema(title = "Team name"),
            "userId" to stringSchema(title = "User"),
        ),
        required = listOf("inviteId", "teamId", "teamName", "userId"),
    )
    return when (fullPath) {
        "/apps/chores/api/v1.0/team",
        "/apps/chores/api/v1.0/account/team",
        -> closedObjectSchema(
            properties = mapOf(
                "id" to integerSchema(title = "Team"),
                "name" to stringSchema(title = "Team name"),
                "owner" to stringSchema(title = "Owner"),
                "members" to JSONObject()
                    .put("type", "array")
                    .put(
                        "items",
                        closedObjectSchema(
                            properties = mapOf(
                                "team_id" to integerSchema(title = "Team"),
                                "member" to stringSchema(title = "Member"),
                                "displayName" to stringSchema(title = "Display name"),
                                "points" to integerSchema(title = "Points"),
                            ),
                            required = listOf("team_id", "member", "displayName", "points"),
                        ),
                    ),
                "invites" to JSONObject().put("type", "array").put("items", inviteSchema),
            ),
            required = listOf("id", "name", "owner", "members", "invites"),
        )
        "/apps/chores/api/v1.0/account/invites" ->
            JSONObject().put("type", "array").put("items", inviteSchema)
        "/apps/chores/api/v1.0/team/{teamId}/chores" ->
            JSONObject().put(
                "type",
                "array",
            ).put(
                "items",
                closedObjectSchema(
                    properties = mapOf(
                        "id" to integerSchema(title = "Chore"),
                        "name" to stringSchema(title = "Chore name"),
                        "assignee" to nullableStringSchema(title = "Assignee"),
                        "points" to integerSchema(title = "Points"),
                        "due" to stringSchema(title = "Due", format = "date-time"),
                        "repeat" to choresRepeatScheduleSchema(),
                    ),
                    required = listOf("id", "name", "points", "due", "repeat"),
                ),
            )
        "/apps/chores/api/v1.0/team/{teamId}/work" ->
            JSONObject().put(
                "type",
                "array",
            ).put(
                "items",
                closedObjectSchema(
                    properties = mapOf(
                        "id" to stringSchema(title = "Completion id", format = "uuid"),
                        "work_time" to stringSchema(title = "Completed at", format = "date-time"),
                        "chore_id" to integerSchema(title = "Chore"),
                        "member" to stringSchema(title = "Member"),
                    ),
                    required = listOf("id", "work_time", "chore_id", "member"),
                ),
            )
        else -> null
    }
}

/**
 * Imports the mutation shapes from the exact signed Chores 0.1.0 controller audited alongside its
 * web client. These routes hide their JSON inputs behind IRequest, so the general scalar verifier
 * correctly rejects them. Pinning the controller digest keeps this exception fail-closed: a future
 * Chores release must be audited before its changed write contract becomes callable.
 */
internal fun verifiedChoresWrite(
    appId: String,
    appVersion: String,
    route: StaticRoute,
    fullPath: String,
    controller: StaticApiController,
    controllerSource: String?,
): VerifiedExactWrite? {
    if (!isVerifiedChoresController(appId, appVersion, controller, controllerSource)) {
        return null
    }
    return when (route.verb to fullPath) {
        "POST" to "/apps/chores/api/v1.0/team" -> VerifiedExactWrite(
            label = "Create team",
            resourceId = "team",
            bodySchema = closedObjectSchema(
                properties = mapOf("name" to stringSchema(title = "Team name")),
                required = listOf("name"),
            ),
        )
        "POST" to "/apps/chores/api/v1.0/team/{teamId}/invites" -> VerifiedExactWrite(
            label = "Invite member",
            resourceId = "team",
            bodySchema = closedObjectSchema(
                properties = mapOf("userId" to stringSchema(title = "Nextcloud user")),
                required = listOf("userId"),
            ),
        )
        "POST" to "/apps/chores/api/v1.0/account/invites/accept" -> VerifiedExactWrite(
            label = "Accept invitation",
            resourceId = "invitations",
            bodySchema = closedObjectSchema(
                properties = mapOf("teamId" to JSONObject().put("type", "integer").put("title", "Team")),
                required = listOf("teamId"),
            ),
        )
        "POST" to "/apps/chores/api/v1.0/team/{teamId}/chores" -> VerifiedExactWrite(
            label = "Add chore",
            resourceId = "chores",
            bodySchema = closedObjectSchema(
                properties = mapOf(
                    "chores" to JSONObject()
                        .put("type", "array")
                        .put("format", "nextcloud-repeatable-object-array")
                        .put("minItems", 1)
                        .put("maxItems", 1)
                        .put(
                            "items",
                            closedObjectSchema(
                                properties = mapOf(
                                    "name" to stringSchema(title = "Chore name"),
                                    "points" to JSONObject().put("type", "integer").put("minimum", 1)
                                        .put("maximum", 6).put("title", "Points (1-6)"),
                                    "due" to stringSchema(title = "Due", format = "date-time"),
                                    "repeat" to choresRepeatScheduleSchema(),
                                ),
                                required = listOf("name", "points", "due", "repeat"),
                            ),
                        ),
                ),
                required = listOf("chores"),
            ),
        )
        "PATCH" to "/apps/chores/api/v1.0/team/{teamId}/chores/{choreId}" -> VerifiedExactWrite(
            label = "Edit chore",
            resourceId = "chores",
            bodySchema = closedObjectSchema(
                properties = mapOf(
                    "name" to stringSchema(title = "Chore name"),
                    "assignee" to stringSchema(title = "Assignee", format = "nextcloud-user-id"),
                    "points" to JSONObject().put("type", "integer").put("minimum", 0).put("title", "Points"),
                    "due" to stringSchema(title = "Due", format = "date-time"),
                    "repeat" to choresRepeatScheduleSchema(),
                ),
                required = emptyList(),
            ),
            required = false,
        )
        "POST" to "/apps/chores/api/v1.0/team/{teamId}/work" -> VerifiedExactWrite(
            label = "Mark as done",
            resourceId = "chores",
            bodySchema = closedObjectSchema(
                properties = mapOf(
                    "work" to JSONObject()
                        .put("type", "array")
                        .put("format", "nextcloud-repeatable-object-array")
                        .put("minItems", 1)
                        .put("maxItems", 1)
                        .put(
                            "items",
                            closedObjectSchema(
                                properties = mapOf(
                                    "id" to stringSchema(title = "Completion id", format = "uuid"),
                                    "work_time" to stringSchema(title = "Completed at", format = "date-time"),
                                    "chore_id" to JSONObject().put("type", "integer").put("title", "Chore"),
                                    "member" to stringSchema(title = "Member"),
                                ),
                                required = listOf("id", "work_time", "chore_id", "member"),
                            ),
                        ),
                ),
                required = listOf("work"),
            ),
        )
        "DELETE" to "/apps/chores/api/v1.0/team/{teamId}/members/{userIdToRemove}" ->
            VerifiedExactWrite(
                label = "Remove member",
                resourceId = "team",
                bodySchema = null,
                required = false,
            )
        else -> null
    }
}

internal fun closedObjectSchema(
    properties: Map<String, JSONObject>,
    required: List<String>,
): JSONObject = JSONObject()
    .put("type", "object")
    .put("additionalProperties", false)
    .put("properties", JSONObject(properties))
    .also { schema ->
        if (required.isNotEmpty()) schema.put("required", JSONArray(required))
    }

private fun integerSchema(title: String): JSONObject = JSONObject()
    .put("type", "integer")
    .put("title", title)

private fun nullableStringSchema(title: String): JSONObject = JSONObject()
    .put("type", JSONArray().put("string").put("null"))
    .put("title", title)

private fun choresRepeatScheduleSchema(): JSONObject {
    val choices = linkedMapOf(
        "s:1:-" to "Does not repeat",
        "o:1" to "On demand",
        "d:1" to "Every day",
        "d:2" to "Every 2 days",
        "d:3" to "Every 3 days",
        "d:4" to "Every 4 days",
        "d:5" to "Every 5 days",
        "d:6" to "Every 6 days",
        "w:1" to "Every week",
        "w:2" to "Every 2 weeks",
        "w:3" to "Every 3 weeks",
        "w:4" to "Every 4 weeks",
        "m:1" to "Every month",
        "m:2" to "Every 2 months",
        "m:3" to "Every 3 months",
        "m:4" to "Every 4 months",
        "m:6" to "Every 6 months",
        "m:12" to "Every year",
    )
    return stringSchema(title = "Repeat")
        .put("enum", JSONArray(choices.keys))
        .put("x-nextcloud-native-enum-labels", JSONObject(choices))
}
