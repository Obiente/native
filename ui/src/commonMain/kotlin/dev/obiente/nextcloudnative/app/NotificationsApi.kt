package dev.obiente.nextcloudnative.app

import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/*
 * Reviewed adapter for the Nextcloud Notifications app OCS API v2.
 *
 * Protocol reference:
 * https://github.com/nextcloud/notifications/blob/master/docs/ocs-endpoint-v2.md
 *
 * The server advertises the supported endpoints in `capabilities.notifications.ocs-endpoints`.
 * Listing requires `list`; dismissing one requires `delete`; dismissing all requires `delete-all`.
 * The list endpoint has no cursor: the server returns the newest notifications it chooses to
 * return, so the client bounds the entries instead of paging. Action links are server-provided;
 * only same-account links without a query or fragment become requests, and nothing in a
 * notification can introduce another method, path, or payload.
 */

internal const val NOTIFICATIONS_APP_ID = "notifications"
internal const val NOTIFICATIONS_API_PATH = "/ocs/v2.php/apps/notifications/api/v2/notifications"
internal const val NOTIFICATIONS_CAPABILITIES_PATH = "/ocs/v2.php/cloud/capabilities"
internal const val MAX_NOTIFICATION_ENTRIES = 200
internal const val MAX_NOTIFICATIONS_RESPONSE_BYTES = 4L * 1024L * 1024L

/** Endpoints the server advertises for this account. Unknown entries are ignored. */
internal data class NotificationsCapabilities(val endpoints: Set<String>) {
    val canList: Boolean get() = "list" in endpoints
    val canDismiss: Boolean get() = "delete" in endpoints
    val canDismissAll: Boolean get() = "delete-all" in endpoints
}

/** What the capabilities document says about the Notifications app. */
internal sealed interface NotificationsCapabilitiesResult {
    data class Available(val capabilities: NotificationsCapabilities) : NotificationsCapabilitiesResult
    /** The app is disabled, not installed, or not enabled for this account. */
    data object NotEnabled : NotificationsCapabilitiesResult
    /** The app is present but does not advertise the v2 list endpoint this client reads. */
    data object Unsupported : NotificationsCapabilitiesResult
}

internal enum class NotificationActionMethod {
    Get,
    Post,
    Put,
    Delete,
    /** Navigation only: the link opens instead of being requested. */
    Web,
}

internal data class NotificationAction(
    val label: String,
    val link: String,
    val method: NotificationActionMethod,
    val primary: Boolean,
) {
    /** DELETE actions remove server state and always need a separate confirmation. */
    val destructive: Boolean get() = method == NotificationActionMethod.Delete
}

internal data class NextcloudNotification(
    val id: Long,
    val app: String,
    val publishedAtEpochSeconds: Long?,
    val subject: String,
    val message: String?,
    val link: String?,
    val actions: List<NotificationAction>,
)

internal sealed interface NotificationsListResult {
    /** [truncated] is true when the server sent more entries than this client keeps. */
    data class Loaded(
        val notifications: List<NextcloudNotification>,
        val truncated: Boolean,
    ) : NotificationsListResult

    /** HTTP 204: no installed app can create notifications for this account. */
    data object NoNotifierApps : NotificationsListResult
}

internal enum class NotificationsProtocolFailure {
    Malformed,
    Oversized,
}

internal class NotificationsProtocolException(
    val failure: NotificationsProtocolFailure,
    message: String,
) : Exception(message)

internal fun notificationsCapabilitiesRequest(): NextcloudApiRequest = NextcloudApiRequest(
    method = NextcloudApiMethod.GET,
    relativePath = NOTIFICATIONS_CAPABILITIES_PATH,
    ocsApiRequest = true,
    maximumResponseBytes = MAX_CAPABILITIES_RESPONSE_BYTES,
    cachePolicy = NextcloudApiCachePolicy.RefreshNetwork,
)

/**
 * Reads the newest notifications. [verification] reads bypass any shared in-flight read, so a
 * postcondition check after a write never accepts a response that was requested before it.
 */
internal fun notificationsListRequest(verification: Boolean = false): NextcloudApiRequest = NextcloudApiRequest(
    method = NextcloudApiMethod.GET,
    relativePath = NOTIFICATIONS_API_PATH,
    ocsApiRequest = true,
    maximumResponseBytes = MAX_NOTIFICATIONS_RESPONSE_BYTES,
    cachePolicy = if (verification) NextcloudApiCachePolicy.ForceNetwork else NextcloudApiCachePolicy.RefreshNetwork,
)

internal fun dismissNotificationRequest(notificationId: Long): NextcloudApiRequest {
    require(notificationId > 0L) { "The notification ID is invalid." }
    return NextcloudApiRequest(
        method = NextcloudApiMethod.DELETE,
        relativePath = "$NOTIFICATIONS_API_PATH/$notificationId",
        ocsApiRequest = true,
        maximumResponseBytes = MAX_NOTIFICATION_WRITE_RESPONSE_BYTES,
        cachePolicy = NextcloudApiCachePolicy.ForceNetwork,
    )
}

internal fun dismissAllNotificationsRequest(): NextcloudApiRequest = NextcloudApiRequest(
    method = NextcloudApiMethod.DELETE,
    relativePath = NOTIFICATIONS_API_PATH,
    ocsApiRequest = true,
    maximumResponseBytes = MAX_NOTIFICATION_WRITE_RESPONSE_BYTES,
    cachePolicy = NextcloudApiCachePolicy.ForceNetwork,
)

/**
 * Builds the request for a server-advertised action, or null when it cannot be performed safely
 * in the client: web actions open instead, and links outside the signed-in account, or with a
 * query or fragment the restricted transport cannot carry, are not requested.
 */
internal fun notificationActionRequest(serverUrl: String, action: NotificationAction): NextcloudApiRequest? {
    val method = when (action.method) {
        NotificationActionMethod.Get -> NextcloudApiMethod.GET
        NotificationActionMethod.Post -> NextcloudApiMethod.POST
        NotificationActionMethod.Put -> NextcloudApiMethod.PUT
        NotificationActionMethod.Delete -> NextcloudApiMethod.DELETE
        NotificationActionMethod.Web -> return null
    }
    val relative = sameAccountRelativeUrl(serverUrl, action.link) ?: return null
    if ('?' in relative || '#' in relative) return null
    val request = NextcloudApiRequest(
        method = method,
        relativePath = relative,
        ocsApiRequest = relative.startsWith("/ocs/"),
        maximumResponseBytes = MAX_NOTIFICATION_WRITE_RESPONSE_BYTES,
        cachePolicy = NextcloudApiCachePolicy.ForceNetwork,
    )
    return try {
        request.requireSafe()
    } catch (_: IllegalArgumentException) {
        null
    }
}

internal fun parseNotificationsCapabilities(response: NextcloudApiResponse): NotificationsCapabilitiesResult {
    val data = ocsData(response, maximumBytes = MAX_CAPABILITIES_RESPONSE_BYTES) as? JsonObject
        ?: throw malformed("The capabilities response has no data object.")
    val capabilities = data["capabilities"] as? JsonObject
        ?: throw malformed("The capabilities response has no capabilities.")
    val notifications = when (val value = capabilities[NOTIFICATIONS_APP_ID]) {
        null, JsonNull -> return NotificationsCapabilitiesResult.NotEnabled
        is JsonObject -> value
        // PHP encodes an empty capability object as an empty list.
        is JsonArray -> if (value.isEmpty()) JsonObject(emptyMap()) else throw malformed("The notifications capability is invalid.")
        else -> throw malformed("The notifications capability is invalid.")
    }
    val endpoints = (notifications["ocs-endpoints"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }
        ?.filter { it.length <= MAX_ENDPOINT_NAME_CHARACTERS }
        ?.toSet()
        .orEmpty()
    val parsed = NotificationsCapabilities(endpoints)
    return if (parsed.canList) {
        NotificationsCapabilitiesResult.Available(parsed)
    } else {
        NotificationsCapabilitiesResult.Unsupported
    }
}

/** Parses a successful list response. Callers map non-2xx statuses to failures first. */
internal fun parseNotificationsList(response: NextcloudApiResponse): NotificationsListResult {
    if (response.status == 204) return NotificationsListResult.NoNotifierApps
    val data = ocsData(response, maximumBytes = MAX_NOTIFICATIONS_RESPONSE_BYTES) as? JsonArray
        ?: throw malformed("The notifications data is not a list.")
    val notifications = data.asSequence()
        .take(MAX_NOTIFICATION_ENTRIES * 2)
        .mapNotNull { (it as? JsonObject)?.let(::parseNotification) }
        .distinctBy(NextcloudNotification::id)
        .toList()
    return NotificationsListResult.Loaded(
        notifications = notifications
            .sortedWith(compareByDescending<NextcloudNotification> { it.publishedAtEpochSeconds ?: 0L }.thenByDescending { it.id })
            .take(MAX_NOTIFICATION_ENTRIES),
        truncated = data.size > MAX_NOTIFICATION_ENTRIES,
    )
}

/**
 * Whether the server reports success for a write. OCS v1 routes answer HTTP 200 with the real
 * outcome in `meta.statuscode`, so a JSON body with a failing status code is a rejection.
 */
internal fun notificationWriteSucceeded(response: NextcloudApiResponse): Boolean {
    if (response.status !in 200..299) return false
    if (response.body.isEmpty()) return true
    val root = try {
        notificationsJson.parseToJsonElement(response.body.decodeToString()) as? JsonObject
    } catch (_: IllegalArgumentException) {
        null
    } ?: return true
    val meta = (root["ocs"] as? JsonObject)?.get("meta") as? JsonObject ?: return true
    val statusCode = (meta["statuscode"] as? JsonPrimitive)?.longOrNull ?: return true
    return statusCode == 100L || statusCode in 200L..299L
}

private fun parseNotification(entry: JsonObject): NextcloudNotification? {
    val id = (entry["notification_id"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
        ?.takeIf { it > 0L } ?: return null
    val app = entry.cleanText("app", MAX_APP_ID_CHARACTERS) ?: NOTIFICATIONS_APP_ID
    val subject = renderRichString(entry, "subjectRich", "subjectRichParameters")
        ?: entry.cleanText("subject", MAX_SUBJECT_CHARACTERS)
        ?: "New notification"
    val message = renderRichString(entry, "messageRich", "messageRichParameters")
        ?: entry.multilineText("message")
    return NextcloudNotification(
        id = id,
        app = app,
        publishedAtEpochSeconds = entry.cleanText("datetime", MAX_DATETIME_CHARACTERS)?.let(::parseEpochSeconds),
        subject = subject.take(MAX_SUBJECT_CHARACTERS),
        message = message?.takeUnless { it == subject },
        link = entry.cleanText("link", MAX_LINK_CHARACTERS)?.takeIf(String::isSafeIncomingNextcloudLink),
        actions = (entry["actions"] as? JsonArray).orEmpty()
            .asSequence()
            .mapNotNull { (it as? JsonObject)?.let(::parseAction) }
            .take(MAX_ACTIONS)
            .toList(),
    )
}

private fun parseAction(entry: JsonObject): NotificationAction? {
    val label = entry.cleanText("label", MAX_ACTION_LABEL_CHARACTERS) ?: return null
    val link = entry.cleanText("link", MAX_LINK_CHARACTERS)?.takeIf(String::isSafeIncomingNextcloudLink) ?: return null
    val method = when (entry.cleanText("type", 16)?.uppercase()) {
        "GET" -> NotificationActionMethod.Get
        "POST" -> NotificationActionMethod.Post
        "PUT" -> NotificationActionMethod.Put
        "DELETE" -> NotificationActionMethod.Delete
        "WEB" -> NotificationActionMethod.Web
        else -> return null
    }
    return NotificationAction(
        label = label,
        link = link,
        method = method,
        primary = (entry["primary"] as? JsonPrimitive)?.booleanOrNull ?: false,
    )
}

/**
 * Replaces `{name}` placeholders with each rich parameter's display name. Returns null, so the
 * plain text is used, when the rich text is empty or names a parameter that is missing.
 */
internal fun renderRichString(entry: JsonObject, textField: String, parametersField: String): String? {
    val template = (entry[textField] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
        ?.take(MAX_RICH_TEMPLATE_CHARACTERS)
        ?.takeIf(String::isNotBlank)
        ?: return null
    val parameters = entry[parametersField] as? JsonObject ?: JsonObject(emptyMap())
    val rendered = StringBuilder()
    var index = 0
    while (index < template.length) {
        val open = template.indexOf('{', index)
        if (open < 0) {
            rendered.append(template, index, template.length)
            break
        }
        val close = template.indexOf('}', open + 1)
        if (close < 0) {
            rendered.append(template, index, template.length)
            break
        }
        val key = template.substring(open + 1, close)
        if (key.isEmpty() || key.any { !it.isLetterOrDigit() && it != '_' && it != '-' && it != '.' }) {
            rendered.append(template, index, close + 1)
        } else {
            val name = (parameters[key] as? JsonObject)?.cleanText("name", MAX_SUBJECT_CHARACTERS) ?: return null
            rendered.append(template, index, open).append(name)
        }
        index = close + 1
    }
    return normalizeNotificationText(rendered.toString(), multiline = textField == "messageRich")
}

private fun ocsData(response: NextcloudApiResponse, maximumBytes: Long): JsonElement {
    if (response.body.size > maximumBytes) {
        throw NotificationsProtocolException(NotificationsProtocolFailure.Oversized, "The server response is too large.")
    }
    if (response.contentType?.substringBefore(';')?.trim()?.lowercase() != "application/json") {
        throw malformed("The server did not return JSON.")
    }
    val root = try {
        notificationsJson.parseToJsonElement(response.body.decodeToString())
    } catch (_: IllegalArgumentException) {
        throw malformed("The server returned an unreadable response.")
    } as? JsonObject ?: throw malformed("The server returned an unexpected response.")
    val ocs = root["ocs"] as? JsonObject ?: throw malformed("The response has no OCS envelope.")
    val meta = ocs["meta"] as? JsonObject ?: throw malformed("The response has no OCS metadata.")
    val statusCode = (meta["statuscode"] as? JsonPrimitive)?.longOrNull
    if (statusCode != null && statusCode != 100L && statusCode !in 200L..299L) {
        throw malformed("The server reported an OCS failure.")
    }
    return ocs["data"] ?: throw malformed("The response has no data.")
}

private fun parseEpochSeconds(value: String): Long? = try {
    Instant.parse(value).epochSeconds.takeIf { it in 0L..MAX_EPOCH_SECONDS }
} catch (_: IllegalArgumentException) {
    null
}

private fun JsonObject.cleanText(name: String, maximum: Int): String? =
    (get(name) as? JsonPrimitive)
        ?.takeIf(JsonPrimitive::isString)
        ?.contentOrNull
        ?.take(maximum * 2)
        ?.let { normalizeNotificationText(it, multiline = false) }
        ?.take(maximum)

private fun JsonObject.multilineText(name: String): String? =
    (get(name) as? JsonPrimitive)
        ?.takeIf(JsonPrimitive::isString)
        ?.contentOrNull
        ?.take(MAX_MESSAGE_CHARACTERS)
        ?.let { normalizeNotificationText(it, multiline = true) }

/** Server text is untrusted: drop control characters, keep line breaks only in messages. */
internal fun normalizeNotificationText(value: String, multiline: Boolean): String? {
    val cleaned = StringBuilder(value.length)
    var pendingSpace = false
    var pendingBreaks = 0
    for (character in value) {
        when {
            character == '\n' && multiline -> pendingBreaks = (pendingBreaks + 1).coerceAtMost(2)
            character.isWhitespace() || character.isISOControl() -> pendingSpace = true
            else -> {
                if (cleaned.isNotEmpty()) {
                    when {
                        pendingBreaks > 0 -> repeat(pendingBreaks) { cleaned.append('\n') }
                        pendingSpace -> cleaned.append(' ')
                    }
                }
                pendingSpace = false
                pendingBreaks = 0
                cleaned.append(character)
            }
        }
    }
    return cleaned.toString().takeIf(String::isNotEmpty)?.take(MAX_MESSAGE_CHARACTERS)
}

private fun malformed(message: String) =
    NotificationsProtocolException(NotificationsProtocolFailure.Malformed, message)

private val notificationsJson = Json { ignoreUnknownKeys = true }

private const val MAX_CAPABILITIES_RESPONSE_BYTES = 2L * 1024L * 1024L
private const val MAX_NOTIFICATION_WRITE_RESPONSE_BYTES = 256L * 1024L
private const val MAX_ENDPOINT_NAME_CHARACTERS = 64
private const val MAX_APP_ID_CHARACTERS = 64
private const val MAX_SUBJECT_CHARACTERS = 512
private const val MAX_MESSAGE_CHARACTERS = 4_000
private const val MAX_RICH_TEMPLATE_CHARACTERS = 4_000
private const val MAX_LINK_CHARACTERS = 2_048
private const val MAX_ACTION_LABEL_CHARACTERS = 64
private const val MAX_DATETIME_CHARACTERS = 64
private const val MAX_ACTIONS = 6
private const val MAX_EPOCH_SECONDS = 253_402_300_799L
