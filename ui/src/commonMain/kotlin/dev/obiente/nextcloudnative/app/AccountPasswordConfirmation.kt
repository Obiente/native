package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The account password typed for one confirmation, held only in memory.
 *
 * Nextcloud's `PasswordConfirmationRequired` middleware refuses app-password sessions: strict
 * routes read the real password from Basic authentication, and non-strict routes require a login
 * with the real password in the same server session. nati.ve therefore sends the typed password as
 * the Basic credential of the confirmed requests only. It is never stored, saved for restoration,
 * logged, or reused after [clear].
 */
class AccountPasswordConfirmationSecret(password: CharArray) {
    private val characters = password.copyOf()

    init {
        require(characters.isNotEmpty() && characters.size <= MAX_ACCOUNT_PASSWORD_LENGTH) {
            "The account password is empty or too long."
        }
    }

    @Volatile
    private var cleared = false

    val isCleared: Boolean get() = cleared

    /** Gives [block] the password; the caller must not retain the array. */
    fun <T> read(block: (CharArray) -> T): T {
        check(!cleared) { "The account password confirmation was already used." }
        return block(characters)
    }

    fun clear() {
        characters.fill('\u0000')
        cleared = true
    }

    override fun toString(): String = "AccountPasswordConfirmationSecret(<redacted>)"
}

/** Network outcome of one password-confirmed request, before any protocol interpretation. */
sealed interface PasswordConfirmedApiResult {
    data class Delivered(val response: NextcloudApiResponse) : PasswordConfirmedApiResult

    /** The request never left this device, so nothing changed on the server. */
    data object NotSent : PasswordConfirmedApiResult

    /** The request may have reached the server. Verify the postcondition before any retry. */
    data object Ambiguous : PasswordConfirmedApiResult
}

/**
 * Platform transport for password-confirmed OCS requests.
 *
 * Implementations attach only `loginName:password` Basic credentials and the `OCS-APIRequest`
 * header, keep the account origin and base path, send no cookies, bound the response, and never
 * retry automatically.
 */
fun interface AccountPasswordConfirmedTransport {
    suspend fun execute(
        session: NextcloudSession,
        request: NextcloudApiRequest,
        secret: AccountPasswordConfirmationSecret,
    ): PasswordConfirmedApiResult
}

/** Null on platforms or tests without a password-confirmed transport; writes then stay read-only. */
val LocalAccountPasswordConfirmedTransport = staticCompositionLocalOf<AccountPasswordConfirmedTransport?> { null }

/** Why a confirmed write was not applied. UI copy derives from this value only. */
enum class ConfirmedWriteRejection(val message: String) {
    PasswordNotAccepted(
        "Nextcloud did not accept the password. Check it and try again. Accounts that require " +
            "two-factor authentication or app tokens cannot confirm administrator changes from a client app.",
    ),
    Throttled("Nextcloud asked to slow down after several attempts. Wait a few minutes before trying again."),
    Forbidden("This account is not allowed to make that change."),
    NotFound("The item no longer exists on the server. Refresh and try again."),
    Invalid("Nextcloud rejected the value. Check it and try again."),
    Unavailable("The server could not complete the change."),
    NotSent("Can't reach your server. Nothing was changed."),
    TransportMissing("Password confirmation is not available in this build."),
}

sealed interface ConfirmedWriteOutcome {
    /** The server reported success and a fresh read confirmed the requested state. */
    data object Applied : ConfirmedWriteOutcome

    /** The server said no. Nothing changed for this request. */
    data class Rejected(val reason: ConfirmedWriteRejection, val serverMessage: String? = null) : ConfirmedWriteOutcome

    /**
     * The result is unknown or a fresh read did not show the change. Retrying blindly could
     * repeat a change, so the UI refreshes and lets the person decide.
     */
    data object Unverified : ConfirmedWriteOutcome
}

/**
 * Runs one password-confirmed write and verifies its postcondition with a fresh authoritative
 * read. [verify] returns true only when the server state matches the requested change.
 */
suspend fun runPasswordConfirmedWrite(
    transport: AccountPasswordConfirmedTransport,
    session: NextcloudSession,
    request: NextcloudApiRequest,
    secret: AccountPasswordConfirmationSecret,
    verify: suspend () -> Boolean,
): ConfirmedWriteOutcome {
    require(request.method != NextcloudApiMethod.GET) { "Password confirmation is only for writes." }
    require(request.ocsApiRequest) { "Password confirmation is limited to OCS requests." }
    val result = transport.execute(session, request, secret)
    return when (result) {
        PasswordConfirmedApiResult.NotSent -> ConfirmedWriteOutcome.Rejected(ConfirmedWriteRejection.NotSent)
        PasswordConfirmedApiResult.Ambiguous -> verifiedOrUnknown(verify)
        is PasswordConfirmedApiResult.Delivered -> classifyConfirmedResponse(result.response, verify)
    }
}

/** Classifies a delivered OCS write response; 2xx success still needs a postcondition read. */
internal suspend fun classifyConfirmedResponse(
    response: NextcloudApiResponse,
    verify: suspend () -> Boolean,
): ConfirmedWriteOutcome {
    val meta = response.ocsMeta()
    val serverMessage = meta?.message
    return when (response.status) {
        in 200..299 -> when {
            meta == null -> verifiedOrUnknown(verify)
            meta.statusCode in OCS_SUCCESS_CODES -> verifiedOrUnknown(verify)
            // OCS v1 reports failures inside HTTP 200; these codes are documented decisions.
            meta.statusCode == 997 -> ConfirmedWriteOutcome.Rejected(ConfirmedWriteRejection.PasswordNotAccepted)
            meta.statusCode == 998 -> ConfirmedWriteOutcome.Rejected(ConfirmedWriteRejection.NotFound, serverMessage)
            meta.statusCode in 100..199 || meta.statusCode in 400..499 ->
                ConfirmedWriteOutcome.Rejected(ConfirmedWriteRejection.Invalid, serverMessage)
            else -> verifiedOrUnknown(verify)
        }
        401 -> ConfirmedWriteOutcome.Rejected(ConfirmedWriteRejection.PasswordNotAccepted)
        403 -> ConfirmedWriteOutcome.Rejected(ConfirmedWriteRejection.Forbidden, serverMessage)
        404 -> ConfirmedWriteOutcome.Rejected(ConfirmedWriteRejection.NotFound, serverMessage)
        429 -> ConfirmedWriteOutcome.Rejected(ConfirmedWriteRejection.Throttled)
        in 400..499 -> ConfirmedWriteOutcome.Rejected(ConfirmedWriteRejection.Invalid, serverMessage)
        // A gateway or server error can follow a change that was already stored.
        else -> verifiedOrUnknown(verify)
    }
}

private suspend fun verifiedOrUnknown(verify: suspend () -> Boolean): ConfirmedWriteOutcome = try {
    if (verify()) ConfirmedWriteOutcome.Applied else ConfirmedWriteOutcome.Unverified
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    // Verification is a fault boundary: an unreadable postcondition is reported as unknown.
    ConfirmedWriteOutcome.Unverified
}

/** Builds an OCS v2 form request for a password-confirmed write. */
internal fun confirmedOcsFormRequest(
    method: NextcloudApiMethod,
    relativePath: String,
    fields: List<Pair<String, String>> = emptyList(),
): NextcloudApiRequest {
    require(relativePath.startsWith("/ocs/v2.php/")) { "Confirmed writes use OCS v2 status codes." }
    val body = fields.takeIf(List<*>::isNotEmpty)?.joinToString("&") { (name, value) ->
        "${encodeUrlComponent(name)}=${encodeUrlComponent(value)}"
    }?.encodeToByteArray()
    return NextcloudApiRequest(
        method = method,
        relativePath = relativePath,
        queryParameters = mapOf("format" to "json"),
        contentType = body?.let { FORM_CONTENT_TYPE },
        body = body,
        ocsApiRequest = true,
        maximumResponseBytes = CONFIRMED_WRITE_RESPONSE_BYTES,
        cachePolicy = NextcloudApiCachePolicy.ForceNetwork,
    )
}

internal data class OcsMeta(val statusCode: Int, val status: String?, val message: String?)

internal fun NextcloudApiResponse.ocsMeta(): OcsMeta? = ocsRoot()?.let { ocs ->
    val meta = ocs["meta"] as? JsonObject ?: return null
    val code = (meta["statuscode"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: return null
    OcsMeta(
        statusCode = code,
        status = (meta["status"] as? JsonPrimitive)?.contentOrNull,
        message = (meta["message"] as? JsonPrimitive)?.contentOrNull
            ?.filterNot(Char::isISOControl)?.trim()?.take(MAX_SERVER_MESSAGE_LENGTH)?.takeIf(String::isNotBlank),
    )
}

/** The `ocs.data` element of a successful OCS JSON response, or null when the response is unusable. */
internal fun NextcloudApiResponse.successfulOcsData(): JsonElement? {
    if (status !in 200..299) return null
    val ocs = ocsRoot() ?: return null
    val meta = ocsMeta() ?: return null
    if (meta.statusCode !in OCS_SUCCESS_CODES) return null
    return ocs["data"]
}

private fun NextcloudApiResponse.ocsRoot(): JsonObject? {
    if (contentType?.contains("json", ignoreCase = true) != true) return null
    val root = try {
        ocsJson.parseToJsonElement(body.decodeToString()) as? JsonObject
    } catch (_: IllegalArgumentException) {
        null
    } ?: return null
    return root["ocs"] as? JsonObject
}

private val ocsJson = Json { ignoreUnknownKeys = true }
private val OCS_SUCCESS_CODES = setOf(100, 200)
private const val FORM_CONTENT_TYPE = "application/x-www-form-urlencoded"
private const val MAX_SERVER_MESSAGE_LENGTH = 300
internal const val CONFIRMED_WRITE_RESPONSE_BYTES = 1L * 1024L * 1024L
internal const val MAX_ACCOUNT_PASSWORD_LENGTH = 1_024
