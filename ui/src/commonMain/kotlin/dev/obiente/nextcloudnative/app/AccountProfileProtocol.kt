package dev.obiente.nextcloudnative.app

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Account property visibility from Nextcloud's `IAccountManager` scopes.
 * See https://github.com/nextcloud/server/blob/master/lib/public/Accounts/IAccountManager.php
 */
enum class AccountPropertyScope(val wireValue: String, val label: String, val description: String) {
    Private("v2-private", "Private", "Only visible to people matched through phone number integration"),
    Local("v2-local", "Local", "Only visible to people on this server and guests"),
    Federated("v2-federated", "Federated", "Only synchronized to trusted servers"),
    Published("v2-published", "Published", "Synchronized to trusted and public servers and the address book"),
    ;

    companion object {
        fun fromWire(value: String?): AccountPropertyScope? = when (value) {
            // Legacy scope names are still returned by some upgraded accounts.
            "private" -> Private
            "contacts" -> Local
            "public" -> Published
            else -> entries.firstOrNull { it.wireValue == value }
        }
    }
}

/** Account properties that the provisioning API exposes for the signed-in account. */
enum class AccountProfileField(
    val key: String,
    val label: String,
    val multiline: Boolean = false,
    val maximumLength: Int = 2_048,
) {
    DisplayName("displayname", "Full name", maximumLength = 64),
    Email("email", "Email", maximumLength = 255),
    Phone("phone", "Phone number", maximumLength = 64),
    Address("address", "Location"),
    Website("website", "Website"),
    Twitter("twitter", "X (formerly Twitter)", maximumLength = 255),
    Bluesky("bluesky", "Bluesky", maximumLength = 255),
    Fediverse("fediverse", "Fediverse", maximumLength = 255),
    Organisation("organisation", "Organisation"),
    Role("role", "Role"),
    Headline("headline", "Headline"),
    Biography("biography", "About", multiline = true, maximumLength = 4_096),
    Pronouns("pronouns", "Pronouns", maximumLength = 64),
}

data class AccountProfileValue(
    val field: AccountProfileField,
    val value: String,
    val scope: AccountPropertyScope?,
    val editable: Boolean,
)

data class AccountAdditionalEmail(val address: String, val scope: AccountPropertyScope?)

data class AccountQuota(val usedBytes: Long?, val totalBytes: Long?, val relativePercent: Double?, val quotaBytes: Long?)

data class AccountProfile(
    val userId: String,
    val values: List<AccountProfileValue>,
    val additionalEmails: List<AccountAdditionalEmail>,
    val additionalEmailsEditable: Boolean,
    val profileEnabled: Boolean?,
    val language: String?,
    val locale: String?,
    val timezone: String?,
    val groups: List<String>,
    val quota: AccountQuota?,
) {
    fun value(field: AccountProfileField): AccountProfileValue? = values.firstOrNull { it.field == field }
}

sealed interface AccountProfileLoadResult {
    data class Loaded(val profile: AccountProfile) : AccountProfileLoadResult
    data object Unauthorized : AccountProfileLoadResult
    data object Unavailable : AccountProfileLoadResult
    data object Malformed : AccountProfileLoadResult
}

/** One requested change to the signed-in account, sent through `PUT cloud/users/{userId}`. */
sealed interface AccountProfileEdit {
    val key: String
    val value: String

    data class Value(val field: AccountProfileField, override val value: String) : AccountProfileEdit {
        override val key: String get() = this.field.key
    }

    data class Scope(val field: AccountProfileField, val scope: AccountPropertyScope) : AccountProfileEdit {
        override val key: String get() = this.field.key + "Scope"
        override val value: String get() = scope.wireValue
    }

    data class ProfileEnabled(val enabled: Boolean) : AccountProfileEdit {
        override val key: String get() = "profile_enabled"
        override val value: String get() = if (enabled) "1" else "0"
    }
}

object AccountProfileRequests {
    fun currentUser(): NextcloudApiRequest = NextcloudApiRequest(
        method = NextcloudApiMethod.GET,
        relativePath = "/ocs/v2.php/cloud/user",
        queryParameters = mapOf("format" to "json"),
        ocsApiRequest = true,
        maximumResponseBytes = PROFILE_RESPONSE_BYTES,
        cachePolicy = NextcloudApiCachePolicy.ForceNetwork,
    )

    fun editableFields(): NextcloudApiRequest = NextcloudApiRequest(
        method = NextcloudApiMethod.GET,
        relativePath = "/ocs/v2.php/cloud/user/fields",
        queryParameters = mapOf("format" to "json"),
        ocsApiRequest = true,
        maximumResponseBytes = PROFILE_RESPONSE_BYTES,
        cachePolicy = NextcloudApiCachePolicy.ForceNetwork,
    )

    /** Password-confirmed edit of one key for [userId], the exact signed-in account. */
    fun edit(userId: String, edit: AccountProfileEdit): NextcloudApiRequest {
        require(userId.isProvisioningIdentifier()) { "The account ID is invalid." }
        val multiline = edit is AccountProfileEdit.Value && edit.field.multiline
        require(
            edit.value.length <= MAX_PROFILE_VALUE_LENGTH &&
                edit.value.all { !it.isISOControl() || (multiline && it == '\n') },
        ) { "The profile value is invalid." }
        return confirmedOcsFormRequest(
            NextcloudApiMethod.PUT,
            "/ocs/v2.php/cloud/users/${encodeUrlComponent(userId)}",
            listOf("key" to edit.key, "value" to edit.value),
        )
    }
}

fun parseAccountProfile(profileResponse: NextcloudApiResponse, fieldsResponse: NextcloudApiResponse?): AccountProfileLoadResult {
    when (profileResponse.status) {
        401 -> return AccountProfileLoadResult.Unauthorized
        !in 200..299 -> return AccountProfileLoadResult.Unavailable
    }
    val data = profileResponse.successfulOcsData() as? JsonObject ?: return AccountProfileLoadResult.Malformed
    val userId = data.text("id")?.takeIf(String::isProvisioningIdentifier) ?: return AccountProfileLoadResult.Malformed
    // Without the editable-field list every value stays read-only; that is the safe default.
    val editable = fieldsResponse?.successfulOcsData()?.let { element ->
        (element as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet()
    }.orEmpty()
    val values = AccountProfileField.entries.mapNotNull { field ->
        val raw = data[field.key] ?: return@mapNotNull null
        AccountProfileValue(
            field = field,
            value = (raw as? JsonPrimitive)?.contentOrNull.orEmpty().take(field.maximumLength),
            scope = AccountPropertyScope.fromWire(data.text(field.key + "Scope")),
            editable = field.key in editable,
        )
    }
    val additionalAddresses = (data["additional_mail"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { address -> address.length <= 255 } }
        .orEmpty()
    val additionalScopes = (data["additional_mailScope"] as? JsonArray)
        ?.map { (it as? JsonPrimitive)?.contentOrNull }
        .orEmpty()
    return AccountProfileLoadResult.Loaded(
        AccountProfile(
            userId = userId,
            values = values,
            additionalEmails = additionalAddresses.take(MAX_ADDITIONAL_EMAILS).mapIndexed { index, address ->
                AccountAdditionalEmail(address, AccountPropertyScope.fromWire(additionalScopes.getOrNull(index)))
            },
            additionalEmailsEditable = "additional_mail" in editable,
            profileEnabled = data.flag("profile_enabled"),
            language = data.text("language")?.takeIf { it.isNotBlank() && it.length <= 32 },
            locale = data.text("locale")?.takeIf { it.isNotBlank() && it.length <= 32 },
            timezone = data.text("timezone")?.takeIf { it.isNotBlank() && it.length <= 64 },
            groups = (data["groups"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?.filter { it.isNotBlank() && it.length <= 255 }?.take(MAX_PROFILE_GROUPS).orEmpty(),
            quota = parseAccountQuota(data["quota"]),
        ),
    )
}

/** The edits that turn [profile] into [draft], one key per request, scopes after values. */
fun accountProfileEdits(
    profile: AccountProfile,
    draftValues: Map<AccountProfileField, String>,
    draftScopes: Map<AccountProfileField, AccountPropertyScope>,
): List<AccountProfileEdit> = buildList {
    profile.values.forEach { current ->
        val draft = draftValues[current.field]
        if (current.editable && draft != null && draft.trim() != current.value) {
            add(AccountProfileEdit.Value(current.field, draft.trim()))
        }
    }
    profile.values.forEach { current ->
        val scope = draftScopes[current.field]
        if (scope != null && current.scope != null && scope != current.scope) {
            add(AccountProfileEdit.Scope(current.field, scope))
        }
    }
}

/** True when a fresh read shows [edit] applied to [profile]. */
fun AccountProfile.reflects(edit: AccountProfileEdit): Boolean = when (edit) {
    is AccountProfileEdit.Value -> value(edit.field)?.value == edit.value ||
        // The server lowercases and trims email addresses.
        edit.field == AccountProfileField.Email && value(edit.field)?.value.equals(edit.value, ignoreCase = true)
    is AccountProfileEdit.Scope -> value(edit.field)?.scope == edit.scope
    is AccountProfileEdit.ProfileEnabled -> profileEnabled == edit.enabled
}

internal fun parseAccountQuota(element: JsonElement?): AccountQuota? {
    val quota = element as? JsonObject ?: return null
    fun number(name: String): Long? = (quota[name] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
        ?.takeIf { it >= 0L }
    return AccountQuota(
        usedBytes = number("used"),
        totalBytes = number("total"),
        relativePercent = (quota["relative"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it in 0.0..100.0 },
        // Negative quota values mean "unlimited" or "not computed" on the server.
        quotaBytes = number("quota"),
    )
}

internal fun String.isProvisioningIdentifier(): Boolean =
    isNotBlank() && length <= 255 && none { it.isISOControl() } && this != "." && this != ".." && '/' !in this

private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.flag(name: String): Boolean? = (this[name] as? JsonPrimitive)?.let { primitive ->
    primitive.booleanOrNull ?: when (primitive.contentOrNull) {
        "1", "true" -> true
        "0", "false", "" -> false
        else -> null
    }
}

private const val PROFILE_RESPONSE_BYTES = 512L * 1024L
private const val MAX_PROFILE_VALUE_LENGTH = 4_096
private const val MAX_ADDITIONAL_EMAILS = 32
private const val MAX_PROFILE_GROUPS = 256
