package dev.obiente.nextcloudnative.contracts

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

internal data class VerifiedExactWrite(
    val label: String,
    val resourceId: String,
    val bodySchema: JSONObject?,
    val required: Boolean = true,
)

internal fun stringSchema(title: String, format: String? = null): JSONObject = JSONObject()
    .put("type", "string")
    .put("title", title)
    .also { schema -> format?.let { schema.put("format", it) } }

internal fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(encodeToByteArray())
    .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

private fun isVerifiedBudgetAccountController(
    appId: String,
    appVersion: String,
    controller: StaticApiController,
    controllerSource: String?,
): Boolean =
    appId == "budget" && appVersion == "2.44.0" &&
        controller.normalizedName == "account" && controllerSource != null &&
        controllerSource.sha256() == BUDGET_2_44_0_ACCOUNT_CONTROLLER_SHA256

/**
 * Imports the deliberately small account mutation surface proven by Budget 2.44.0's signed
 * AccountController. The controller reads IRequest directly, so its safe body cannot be derived
 * from the PHP signature. The digest makes the exception fail closed when that implementation
 * changes; optional banking and liability fields remain out of the generic editor.
 */
internal fun verifiedBudgetAccountWrite(
    appId: String,
    appVersion: String,
    route: StaticRoute,
    fullPath: String,
    controller: StaticApiController,
    controllerSource: String?,
): VerifiedExactWrite? {
    if (!isVerifiedBudgetAccountController(appId, appVersion, controller, controllerSource)) {
        return null
    }
    val editableProperties = mapOf(
        "name" to stringSchema(title = "Account name"),
        "type" to stringSchema(title = "Account type").put(
            "enum",
            JSONArray(
                listOf(
                    "checking", "savings", "credit_card", "investment", "loan", "cash",
                    "money_market", "cryptocurrency", "mortgage", "line_of_credit",
                ),
            ),
        ),
        "currency" to stringSchema(title = "Currency code"),
        "institution" to stringSchema(title = "Institution"),
    )
    return when (Triple(route.method.normalizedPhpName(), route.verb, fullPath)) {
        Triple("create", "POST", "/apps/budget/api/accounts") -> VerifiedExactWrite(
            label = "Create account",
            resourceId = "accounts",
            bodySchema = closedObjectSchema(
                properties = editableProperties +
                    ("balance" to JSONObject().put("type", "number").put("title", "Opening balance")),
                required = listOf("name", "type"),
            ),
        )
        Triple("update", "PUT", "/apps/budget/api/accounts/{id}") -> VerifiedExactWrite(
            label = "Edit account",
            resourceId = "accounts",
            bodySchema = closedObjectSchema(editableProperties, emptyList()),
            required = false,
        )
        Triple("destroy", "DELETE", "/apps/budget/api/accounts/{id}") -> VerifiedExactWrite(
            label = "Delete account",
            resourceId = "accounts",
            bodySchema = null,
            required = false,
        )
        else -> null
    }
}

private fun isVerifiedMusicPlaylistController(
    appId: String,
    appVersion: String,
    controller: StaticApiController,
    controllerSource: String?,
): Boolean =
    appId == "music" && appVersion == "3.1.1" &&
        controller.normalizedName == "playlistapi" && controllerSource != null &&
        controllerSource.sha256() == MUSIC_3_1_1_PLAYLIST_API_CONTROLLER_SHA256

/** Imports the scalar playlist lifecycle verified against Music 3.1.1's signed controller. */
internal fun verifiedMusicPlaylistWrite(
    appId: String,
    appVersion: String,
    route: StaticRoute,
    fullPath: String,
    controller: StaticApiController,
    controllerSource: String?,
): VerifiedExactWrite? {
    if (!isVerifiedMusicPlaylistController(appId, appVersion, controller, controllerSource)) {
        return null
    }
    return when (Triple(route.method.normalizedPhpName(), route.verb, fullPath)) {
        Triple("create", "POST", "/apps/music/api/playlists") -> VerifiedExactWrite(
            label = "Create playlist",
            resourceId = "playlists",
            bodySchema = closedObjectSchema(
                properties = mapOf("name" to stringSchema(title = "Playlist name")),
                required = listOf("name"),
            ),
        )
        Triple("update", "PUT", "/apps/music/api/playlists/{id}") -> VerifiedExactWrite(
            label = "Edit playlist",
            resourceId = "playlists",
            bodySchema = closedObjectSchema(
                properties = mapOf(
                    "name" to stringSchema(title = "Playlist name"),
                    "comment" to stringSchema(title = "Comment"),
                ),
                required = emptyList(),
            ),
            required = false,
        )
        Triple("delete", "DELETE", "/apps/music/api/playlists/{id}") -> VerifiedExactWrite(
            label = "Delete playlist",
            resourceId = "playlists",
            bodySchema = null,
            required = false,
        )
        else -> null
    }
}

private const val BUDGET_2_44_0_ACCOUNT_CONTROLLER_SHA256 =
    "f873d3d8dc640c8baa05d85c34e4a08e0eb62846c5dd9ca7f8e07e495d0caf7d"
private const val MUSIC_3_1_1_PLAYLIST_API_CONTROLLER_SHA256 =
    "f8146b8521487e79a8ae2ba4ce6eec5556d3bf4e596d00dfcfbe2b5d5bd5877b"
