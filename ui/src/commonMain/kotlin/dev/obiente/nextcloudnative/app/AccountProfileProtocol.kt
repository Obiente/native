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
)

data class AccountAdditionalEmail(val address: String, val scope: AccountPropertyScope?)

data class AccountQuota(val usedBytes: Long?, val totalBytes: Long?, val relativePercent: Double?, val quotaBytes: Long?)

data class AccountProfile(
    val userId: String,
    val values: List<AccountProfileValue>,
    val additionalEmails: List<AccountAdditionalEmail>,
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

object AccountProfileRequests {
    fun currentUser(): NextcloudApiRequest = NextcloudApiRequest(
        method = NextcloudApiMethod.GET,
        relativePath = "/ocs/v2.php/cloud/user",
        queryParameters = mapOf("format" to "json"),
        ocsApiRequest = true,
        maximumResponseBytes = PROFILE_RESPONSE_BYTES,
        cachePolicy = NextcloudApiCachePolicy.ForceNetwork,
    )
}

/** Parses `GET cloud/user`, which includes visibility scopes only for the signed-in account. */
fun parseAccountProfile(profileResponse: NextcloudApiResponse): AccountProfileLoadResult {
    when (profileResponse.status) {
        401 -> return AccountProfileLoadResult.Unauthorized
        !in 200..299 -> return AccountProfileLoadResult.Unavailable
    }
    val data = profileResponse.successfulOcsData() as? JsonObject ?: return AccountProfileLoadResult.Malformed
    val userId = data.text("id")?.takeIf(String::isProvisioningIdentifier) ?: return AccountProfileLoadResult.Malformed
    val values = AccountProfileField.entries.mapNotNull { field ->
        val raw = data[field.key] ?: return@mapNotNull null
        AccountProfileValue(
            field = field,
            value = (raw as? JsonPrimitive)?.contentOrNull.orEmpty().take(field.maximumLength),
            scope = AccountPropertyScope.fromWire(data.text(field.key + "Scope")),
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
private const val MAX_ADDITIONAL_EMAILS = 32
private const val MAX_PROFILE_GROUPS = 256
