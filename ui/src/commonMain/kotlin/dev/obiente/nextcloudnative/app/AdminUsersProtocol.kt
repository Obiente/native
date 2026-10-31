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
 * Every write carries `PasswordConfirmationRequired` upstream, so writes are request plans for the
 * password-confirmed transport, never for the stored app password.
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

/** Quota presets accepted by `UsersController::parseAndValidateQuota`. */
enum class AdminQuotaPreset(val wireValue: String, val label: String, val expectedQuotaBytes: Long? = null) {
    Default("default", "Default quota"),
    Unlimited("none", "Unlimited"),
    OneGigabyte("1 GB", "1 GB", GIBIBYTE),
    FiveGigabytes("5 GB", "5 GB", 5 * GIBIBYTE),
    TenGigabytes("10 GB", "10 GB", 10 * GIBIBYTE),
    FiftyGigabytes("50 GB", "50 GB", 50 * GIBIBYTE),
    OneHundredGigabytes("100 GB", "100 GB", 100 * GIBIBYTE),
}

/** One administrator change. Each maps to exactly one reviewed provisioning route. */
sealed interface AdminUserMutation {
    val userId: String
    val destructive: Boolean get() = false

    data class SetEnabled(override val userId: String, val enabled: Boolean) : AdminUserMutation
    data class AddToGroup(override val userId: String, val groupId: String) : AdminUserMutation
    data class RemoveFromGroup(override val userId: String, val groupId: String) : AdminUserMutation
    data class AddSubadmin(override val userId: String, val groupId: String) : AdminUserMutation
    data class RemoveSubadmin(override val userId: String, val groupId: String) : AdminUserMutation
    data class SetQuota(override val userId: String, val quota: AdminQuotaPreset) : AdminUserMutation
    data class SetDisplayName(override val userId: String, val displayName: String) : AdminUserMutation
    data class SetEmail(override val userId: String, val email: String) : AdminUserMutation
    data class Delete(override val userId: String) : AdminUserMutation {
        override val destructive: Boolean get() = true
    }
}

/** A new account. Without a password the server sends a welcome email, so an email is then required. */
data class AdminNewUser(
    val userId: String,
    val displayName: String,
    val email: String,
    val password: String,
    val groups: List<String>,
    val quota: AdminQuotaPreset?,
) {
    override fun toString(): String = "AdminNewUser(userId=$userId, groups=${groups.size}, password=<redacted>)"
}

sealed interface AdminGroupMutation {
    val groupId: String

    data class Create(override val groupId: String, val displayName: String) : AdminGroupMutation
    data class Delete(override val groupId: String) : AdminGroupMutation
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

    fun mutation(mutation: AdminUserMutation): NextcloudApiRequest {
        val user = "$USERS_PATH/${mutation.userId.requireIdentifier()}"
        return when (mutation) {
            is AdminUserMutation.SetEnabled ->
                confirmedOcsFormRequest(NextcloudApiMethod.PUT, if (mutation.enabled) "$user/enable" else "$user/disable")
            is AdminUserMutation.AddToGroup ->
                confirmedOcsFormRequest(NextcloudApiMethod.POST, "$user/groups", listOf("groupid" to mutation.groupId.requireGroup()))
            is AdminUserMutation.RemoveFromGroup -> deleteWithQuery("$user/groups", "groupid" to mutation.groupId.requireGroup())
            is AdminUserMutation.AddSubadmin ->
                confirmedOcsFormRequest(NextcloudApiMethod.POST, "$user/subadmins", listOf("groupid" to mutation.groupId.requireGroup()))
            is AdminUserMutation.RemoveSubadmin -> deleteWithQuery("$user/subadmins", "groupid" to mutation.groupId.requireGroup())
            is AdminUserMutation.SetQuota ->
                confirmedOcsFormRequest(NextcloudApiMethod.PUT, user, listOf("key" to "quota", "value" to mutation.quota.wireValue))
            is AdminUserMutation.SetDisplayName -> {
                require(mutation.displayName.isNotBlank() && mutation.displayName.length <= 64 && mutation.displayName.none(Char::isISOControl))
                confirmedOcsFormRequest(NextcloudApiMethod.PUT, user, listOf("key" to "displayname", "value" to mutation.displayName.trim()))
            }
            is AdminUserMutation.SetEmail -> {
                require(mutation.email.isEmpty() || mutation.email.isPlausibleEmail())
                confirmedOcsFormRequest(NextcloudApiMethod.PUT, user, listOf("key" to "email", "value" to mutation.email.trim()))
            }
            is AdminUserMutation.Delete -> confirmedOcsFormRequest(NextcloudApiMethod.DELETE, user)
        }
    }

    fun createUser(newUser: AdminNewUser): NextcloudApiRequest {
        newUser.userId.requireNewUserId()
        require(newUser.displayName.length <= 64 && newUser.displayName.none(Char::isISOControl))
        require(newUser.email.isEmpty() || newUser.email.isPlausibleEmail()) { "The email address is invalid." }
        require(newUser.password.isNotEmpty() || newUser.email.isNotEmpty()) {
            "A new account needs a password or an email address for the welcome message."
        }
        require(newUser.password.length <= MAX_ACCOUNT_PASSWORD_LENGTH)
        val fields = buildList {
            add("userid" to newUser.userId)
            if (newUser.password.isNotEmpty()) add("password" to newUser.password)
            if (newUser.displayName.isNotBlank()) add("displayName" to newUser.displayName.trim())
            if (newUser.email.isNotEmpty()) add("email" to newUser.email.trim())
            newUser.groups.forEach { add("groups[]" to it.requireGroup()) }
            newUser.quota?.let { add("quota" to it.wireValue) }
        }
        return confirmedOcsFormRequest(NextcloudApiMethod.POST, USERS_PATH, fields)
    }

    fun groupMutation(mutation: AdminGroupMutation): NextcloudApiRequest = when (mutation) {
        is AdminGroupMutation.Create -> {
            require(mutation.displayName.length <= 255 && mutation.displayName.none(Char::isISOControl))
            confirmedOcsFormRequest(
                NextcloudApiMethod.POST,
                GROUPS_PATH,
                buildList {
                    add("groupid" to mutation.groupId.requireGroup())
                    if (mutation.displayName.isNotBlank()) add("displayname" to mutation.displayName.trim())
                },
            )
        }
        is AdminGroupMutation.Delete -> {
            require(mutation.groupId != ADMIN_GROUP_ID) { "The admin group cannot be deleted." }
            confirmedOcsFormRequest(NextcloudApiMethod.DELETE, "$GROUPS_PATH/${encodeUrlComponent(mutation.groupId.requireGroup())}")
        }
    }

    private fun read(path: String, query: Map<String, String>, limit: Long) = NextcloudApiRequest(
        method = NextcloudApiMethod.GET,
        relativePath = path,
        queryParameters = query + ("format" to "json"),
        ocsApiRequest = true,
        maximumResponseBytes = limit,
        cachePolicy = NextcloudApiCachePolicy.ForceNetwork,
    )

    /** Nextcloud merges query parameters into DELETE parameters; some proxies drop DELETE bodies. */
    private fun deleteWithQuery(path: String, field: Pair<String, String>): NextcloudApiRequest =
        confirmedOcsFormRequest(NextcloudApiMethod.DELETE, path).let { request ->
            request.copy(queryParameters = request.queryParameters + field)
        }
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

/** True when a fresh [user] read shows [mutation] applied. Deletion is verified by a 404 instead. */
fun AdminUser.reflects(mutation: AdminUserMutation): Boolean = id == mutation.userId && when (mutation) {
    is AdminUserMutation.SetEnabled -> enabled == mutation.enabled
    is AdminUserMutation.AddToGroup -> mutation.groupId in groups
    is AdminUserMutation.RemoveFromGroup -> mutation.groupId !in groups
    is AdminUserMutation.AddSubadmin -> mutation.groupId in subadminGroups
    is AdminUserMutation.RemoveSubadmin -> mutation.groupId !in subadminGroups
    // Default and unlimited quotas have no stable byte value in the read model.
    is AdminUserMutation.SetQuota -> mutation.quota.expectedQuotaBytes?.let { quota?.quotaBytes == it } ?: true
    is AdminUserMutation.SetDisplayName -> displayName == mutation.displayName.trim()
    is AdminUserMutation.SetEmail -> email.orEmpty().equals(mutation.email.trim(), ignoreCase = true)
    is AdminUserMutation.Delete -> false
}

/** Actions the signed-in administrator may offer for [target]; never self-disable or self-delete. */
fun adminUserActionsAllowed(target: AdminUser, signedInUserId: String): Boolean =
    target.detailsVisible && target.id != signedInUserId

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

private fun String.requireGroup(): String {
    require(isGroupIdentifier()) { "The group ID is invalid." }
    return this
}

/** Nextcloud user IDs allow letters, digits, spaces, and `_.@-'` (see `Manager::validateUserId`). */
private fun String.requireNewUserId() {
    require(length in 1..64 && trim() == this && all { it.isLetterOrDigit() || it in " _.@-'" } && this != "." && this != "..") {
        "The account ID may contain letters, digits, spaces, and _ . @ - ' only."
    }
}

internal fun String.isGroupIdentifier(): Boolean = isNotBlank() && length <= 255 && none { it.isISOControl() }

internal fun String.isPlausibleEmail(): Boolean {
    val at = indexOf('@')
    return length in 3..255 && at > 0 && at == lastIndexOf('@') && '.' in substring(at + 1) &&
        none { it.isWhitespace() || it.isISOControl() }
}

private const val GIBIBYTE = 1_024L * 1_024L * 1_024L
private const val USERS_PATH = "/ocs/v2.php/cloud/users"
private const val GROUPS_PATH = "/ocs/v2.php/cloud/groups"
private const val ADMIN_GROUP_ID = "admin"
const val ADMIN_USER_PAGE_SIZE = 25
private const val MAX_ADMIN_PAGE_SIZE = 200
private const val MAX_SEARCH_LENGTH = 120
private const val MAX_LIST_VALUES = 512
private const val ADMIN_LIST_RESPONSE_BYTES = 4L * 1024L * 1024L
private const val ADMIN_DETAIL_RESPONSE_BYTES = 512L * 1024L
