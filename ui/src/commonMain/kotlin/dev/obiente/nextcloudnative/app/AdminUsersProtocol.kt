package dev.obiente.nextcloudnative.app

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Users and groups through the provisioning API (`apps/provisioning_api/appinfo/routes.php`).
 * Every user and group write there carries `PasswordConfirmationRequired`, which an app-password
 * sign-in cannot satisfy, so this adapter is read-only.
 */
data class AdminUser(
    val id: String,
    val displayName: String,
    val email: String?,
    val enabled: Boolean?,
    val groups: List<String>,
    val subadminGroups: List<String>,
    val quota: AccountQuota?,
    val lastLoginEpochMillis: Long?,
    val backend: String?,
    /** False when the server returned only the ID because this account cannot see the details. */
    val detailsVisible: Boolean = true,
)

data class AdminGroup(
    val id: String,
    val displayName: String,
    val userCount: Int?,
    val disabledCount: Int?,
    val canAdd: Boolean?,
    val canRemove: Boolean?,
)

data class AdminUserPage(val users: List<AdminUser>, val offset: Int, val mayHaveMore: Boolean)

sealed interface AdminReadResult<out T> {
    data class Loaded<T>(val value: T) : AdminReadResult<T>
    data object Forbidden : AdminReadResult<Nothing>
    data object NotFound : AdminReadResult<Nothing>
    data object Unavailable : AdminReadResult<Nothing>
    data object Malformed : AdminReadResult<Nothing>
}

object AdminUserRequests {
    fun usersPage(search: String, offset: Int, limit: Int = ADMIN_USER_PAGE_SIZE): NextcloudApiRequest {
        require(offset >= 0 && limit in 1..MAX_ADMIN_PAGE_SIZE)
        return read(
            "$USERS_PATH/details",
            mapOf("search" to search.take(MAX_SEARCH_LENGTH), "offset" to offset.toString(), "limit" to limit.toString()),
            ADMIN_LIST_RESPONSE_BYTES,
        )
    }

    fun user(userId: String): NextcloudApiRequest = read("$USERS_PATH/${userId.requireIdentifier()}", emptyMap(), ADMIN_DETAIL_RESPONSE_BYTES)

    fun groups(search: String = "", offset: Int = 0, limit: Int = MAX_ADMIN_PAGE_SIZE): NextcloudApiRequest {
        require(offset >= 0 && limit in 1..MAX_ADMIN_PAGE_SIZE)
        return read(
            "$GROUPS_PATH/details",
            mapOf("search" to search.take(MAX_SEARCH_LENGTH), "offset" to offset.toString(), "limit" to limit.toString()),
            ADMIN_LIST_RESPONSE_BYTES,
        )
    }

    private fun read(path: String, query: Map<String, String>, limit: Long) = NextcloudApiRequest(
        method = NextcloudApiMethod.GET,
        relativePath = path,
        queryParameters = query + ("format" to "json"),
        ocsApiRequest = true,
        maximumResponseBytes = limit,
        cachePolicy = NextcloudApiCachePolicy.ForceNetwork,
    )
}

fun parseAdminUserPage(response: NextcloudApiResponse, offset: Int, requestedLimit: Int = ADMIN_USER_PAGE_SIZE): AdminReadResult<AdminUserPage> {
    response.adminFailure()?.let { return it }
    val data = response.successfulOcsData() as? JsonObject ?: return AdminReadResult.Malformed
    val users = when (val entries = data["users"]) {
        is JsonObject -> entries.entries.mapNotNull { (id, value) ->
            (value as? JsonObject)?.let { parseAdminUser(it, fallbackId = id) }
        }
        // PHP encodes an empty map as an empty list.
        is JsonArray -> if (entries.isEmpty()) emptyList() else return AdminReadResult.Malformed
        else -> return AdminReadResult.Malformed
    }
    return AdminReadResult.Loaded(AdminUserPage(users, offset, mayHaveMore = users.size >= requestedLimit))
}

fun parseAdminUserDetail(response: NextcloudApiResponse): AdminReadResult<AdminUser> {
    response.adminFailure()?.let { return it }
    val data = response.successfulOcsData() as? JsonObject ?: return AdminReadResult.Malformed
    return parseAdminUser(data, fallbackId = null)?.let { AdminReadResult.Loaded(it) } ?: AdminReadResult.Malformed
}

fun parseAdminGroups(response: NextcloudApiResponse): AdminReadResult<List<AdminGroup>> {
    response.adminFailure()?.let { return it }
    val data = response.successfulOcsData() as? JsonObject ?: return AdminReadResult.Malformed
    val groups = data["groups"] as? JsonArray ?: return AdminReadResult.Malformed
    return AdminReadResult.Loaded(
        groups.mapNotNull { element ->
            val group = element as? JsonObject ?: return@mapNotNull null
            val id = group.text("id")?.takeIf(String::isGroupIdentifier) ?: return@mapNotNull null
            AdminGroup(
                id = id,
                displayName = group.text("displayname")?.takeIf { it.isNotBlank() && it.length <= 255 } ?: id,
                userCount = group.number("usercount")?.toInt(),
                disabledCount = group.number("disabled")?.toInt(),
                canAdd = group.flag("canAdd"),
                canRemove = group.flag("canRemove"),
            )
        }.distinctBy(AdminGroup::id),
    )
}

private fun parseAdminUser(data: JsonObject, fallbackId: String?): AdminUser? {
    val id = (data.text("id") ?: fallbackId)?.takeIf(String::isProvisioningIdentifier) ?: return null
    if (fallbackId != null && data.text("id") != null && data.text("id") != fallbackId) return null
    val visible = data.size > 1
    return AdminUser(
        id = id,
        displayName = (data.text("displayname") ?: data.text("display-name"))?.takeIf { it.isNotBlank() && it.length <= 255 } ?: id,
        email = data.text("email")?.takeIf { it.isNotBlank() && it.length <= 255 },
        enabled = data.flag("enabled"),
        groups = data.stringList("groups"),
        subadminGroups = data.stringList("subadmin"),
        quota = parseAccountQuota(data["quota"]),
        lastLoginEpochMillis = data.number("lastLogin")?.takeIf { it > 0L },
        backend = data.text("backend")?.takeIf { it.length <= 64 },
        detailsVisible = visible,
    )
}

private fun NextcloudApiResponse.adminFailure(): AdminReadResult<Nothing>? = when (status) {
    in 200..299 -> null
    401, 403 -> AdminReadResult.Forbidden
    404 -> AdminReadResult.NotFound
    else -> AdminReadResult.Unavailable
}

private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.number(name: String): Long? = (this[name] as? JsonPrimitive)?.let { it.longOrNull ?: it.intOrNull?.toLong() }

private fun JsonObject.flag(name: String): Boolean? = (this[name] as? JsonPrimitive)?.let { primitive ->
    primitive.booleanOrNull ?: when (primitive.contentOrNull) {
        "1", "true" -> true
        "0", "false" -> false
        else -> null
    }
}

private fun JsonObject.stringList(name: String): List<String> = (this[name] as? JsonArray)
    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isGroupIdentifier) }
    ?.distinct()?.take(MAX_LIST_VALUES).orEmpty()

private fun String.requireIdentifier(): String {
    require(isProvisioningIdentifier()) { "The account ID is invalid." }
    return encodeUrlComponent(this)
}

internal fun String.isGroupIdentifier(): Boolean = isNotBlank() && length <= 255 && none { it.isISOControl() }

private const val USERS_PATH = "/ocs/v2.php/cloud/users"
private const val GROUPS_PATH = "/ocs/v2.php/cloud/groups"
const val ADMIN_USER_PAGE_SIZE = 25
private const val MAX_ADMIN_PAGE_SIZE = 200
private const val MAX_SEARCH_LENGTH = 120
private const val MAX_LIST_VALUES = 512
private const val ADMIN_LIST_RESPONSE_BYTES = 4L * 1024L * 1024L
private const val ADMIN_DETAIL_RESPONSE_BYTES = 512L * 1024L
