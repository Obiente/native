package dev.obiente.nextcloudnative.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** Spreed notification levels from `docs/constants.md`. */
enum class TalkNotificationLevel(val wireValue: Int, val label: String) {
    Default(0, "Default"),
    Always(1, "All messages"),
    Mentions(2, "Mentions only"),
    Never(3, "Off"),
}

/** One conversation's settings as the signed-in participant sees them (`GET room/{token}`). */
data class TalkConversationSettings(
    val token: String,
    val name: String,
    val description: String,
    val type: TalkConversationType,
    val readOnly: Boolean,
    val lobbyEnabled: Boolean,
    val notificationLevel: TalkNotificationLevel?,
    val hasPassword: Boolean,
    val participantType: Int?,
) {
    /** Owner (1), moderator (2), and guest moderator (6) may change conversation settings. */
    val canModerate: Boolean get() = participantType == 1 || participantType == 2 || participantType == 6

    /** Only logged-in owners and moderators may open a conversation to guests. */
    val canChangeGuestAccess: Boolean get() = (participantType == 1 || participantType == 2) &&
        (type == TalkConversationType.Group || type == TalkConversationType.Public)

    val canRename: Boolean get() = canModerate && type in RENAMEABLE_TYPES
    val guestsAllowed: Boolean get() = type == TalkConversationType.Public
}

data class TalkParticipant(
    val attendeeId: Long,
    val actorType: String,
    val actorId: String,
    val displayName: String,
    val participantType: Int?,
) {
    val isModerator: Boolean get() = participantType == 1 || participantType == 2 || participantType == 6
    val isOwner: Boolean get() = participantType == 1
}

/** Talk user preferences from `config.chat` capabilities and `POST settings/user`. */
data class TalkUserPrivacySettings(
    /** True when read receipts are shared (`read-privacy` 0). Null when the server lacks the feature. */
    val shareReadStatus: Boolean?,
    /** True when typing indicators are shared (`typing-privacy` 0). */
    val shareTyping: Boolean?,
)

/** One conversation change. Each maps to one reviewed Spreed route. */
sealed interface TalkConversationChange {
    data class Rename(val name: String) : TalkConversationChange
    data class Describe(val description: String) : TalkConversationChange
    data class Notifications(val level: TalkNotificationLevel) : TalkConversationChange
    data class ReadOnly(val readOnly: Boolean) : TalkConversationChange
    data class Lobby(val enabled: Boolean) : TalkConversationChange
    data class GuestAccess(val allowed: Boolean) : TalkConversationChange
    data class RemoveParticipant(val attendeeId: Long, val displayName: String) : TalkConversationChange
    data class Moderator(val attendeeId: Long, val promote: Boolean) : TalkConversationChange
}

internal object TalkSettingsRequests {
    fun participants(token: String): TalkTransportRequest = TalkTransportRequest(
        TalkTransportMethod.GET, "${room(token)}/participants", emptyList(), emptyList(), TALK_SETTINGS_LIST_BYTES,
    )

    fun change(token: String, change: TalkConversationChange): TalkTransportRequest {
        val path = room(token)
        return when (change) {
            is TalkConversationChange.Rename -> {
                require(change.name.isNotBlank() && change.name.length <= MAX_TALK_NAME_LENGTH && change.name.none(Char::isISOControl))
                write(TalkTransportMethod.PUT, path, "roomName" to change.name.trim())
            }
            is TalkConversationChange.Describe -> {
                require(change.description.length <= MAX_TALK_DESCRIPTION_LENGTH &&
                    change.description.none { it.isISOControl() && it != '\n' })
                write(TalkTransportMethod.PUT, "$path/description", "description" to change.description.trim())
            }
            is TalkConversationChange.Notifications ->
                write(TalkTransportMethod.POST, "$path/notify", "level" to change.level.wireValue.toString())
            is TalkConversationChange.ReadOnly ->
                write(TalkTransportMethod.PUT, "$path/read-only", "state" to if (change.readOnly) "1" else "0")
            is TalkConversationChange.Lobby ->
                write(TalkTransportMethod.PUT, "$path/webinar/lobby", "state" to if (change.enabled) "1" else "0")
            is TalkConversationChange.GuestAccess -> TalkTransportRequest(
                if (change.allowed) TalkTransportMethod.POST else TalkTransportMethod.DELETE,
                "$path/public", emptyList(), emptyList(), TALK_SETTINGS_WRITE_BYTES,
            )
            is TalkConversationChange.RemoveParticipant -> TalkTransportRequest(
                TalkTransportMethod.DELETE, "$path/attendees",
                listOf("attendeeId" to change.attendeeId.requireAttendee().toString()), emptyList(), TALK_SETTINGS_WRITE_BYTES,
            )
            is TalkConversationChange.Moderator -> if (change.promote) {
                write(TalkTransportMethod.POST, "$path/moderators", "attendeeId" to change.attendeeId.requireAttendee().toString())
            } else {
                TalkTransportRequest(
                    TalkTransportMethod.DELETE, "$path/moderators",
                    listOf("attendeeId" to change.attendeeId.requireAttendee().toString()), emptyList(), TALK_SETTINGS_WRITE_BYTES,
                )
            }
        }
    }

    fun userSetting(key: TalkUserSettingKey, shared: Boolean): TalkTransportRequest = TalkTransportRequest(
        TalkTransportMethod.POST, "$TALK_API_PREFIX/settings/user", emptyList(),
        // Spreed stores privacy: 0 shares the signal, 1 keeps it private.
        listOf("key" to key.wireValue, "value" to if (shared) "0" else "1"), TALK_SETTINGS_WRITE_BYTES,
    )

    private fun write(method: TalkTransportMethod, path: String, field: Pair<String, String>) =
        TalkTransportRequest(method, path, emptyList(), listOf(field), TALK_SETTINGS_WRITE_BYTES)

    private fun room(token: String): String {
        require(token.isNotBlank() && token.length <= 64 && token.none { it.isWhitespace() || it.isISOControl() }) {
            "The Talk conversation token is invalid."
        }
        return "$TALK_ROOM_SETTINGS_PREFIX/${encodeUrlComponent(token)}"
    }
}

enum class TalkUserSettingKey(val wireValue: String) {
    ReadStatusPrivacy("read_status_privacy"),
    TypingPrivacy("typing_privacy"),
}

internal fun parseTalkConversationSettings(response: TalkTransportResponse): TalkConversationSettings? {
    if (response.status !in 200..299) return null
    val room = response.talkSettingsData() as? JsonObject ?: return null
    val token = room.text("token")?.takeIf(String::isNotBlank) ?: return null
    return TalkConversationSettings(
        token = token,
        name = room.text("displayName") ?: room.text("name").orEmpty(),
        description = room.text("description").orEmpty().take(MAX_TALK_DESCRIPTION_LENGTH),
        type = talkConversationType(room.int("type")),
        // An unknown state keeps writes conservative.
        readOnly = room.int("readOnly") != 0,
        lobbyEnabled = room.int("lobbyState") == 1,
        notificationLevel = TalkNotificationLevel.entries.firstOrNull { it.wireValue == room.int("notificationLevel") },
        hasPassword = room.text("hasPassword") == "true",
        participantType = room.int("participantType"),
    )
}

internal fun parseTalkParticipants(response: TalkTransportResponse): List<TalkParticipant>? {
    if (response.status !in 200..299) return null
    val entries = response.talkSettingsData() as? JsonArray ?: return null
    return entries.mapNotNull { element ->
        val participant = element as? JsonObject ?: return@mapNotNull null
        val attendeeId = (participant["attendeeId"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0L } ?: return@mapNotNull null
        TalkParticipant(
            attendeeId = attendeeId,
            actorType = participant.text("actorType").orEmpty(),
            actorId = participant.text("actorId").orEmpty(),
            displayName = participant.text("displayName")?.takeIf(String::isNotBlank) ?: participant.text("actorId").orEmpty(),
            participantType = participant.int("participantType"),
        )
    }.distinctBy(TalkParticipant::attendeeId).sortedWith(compareBy({ !it.isModerator }, { it.displayName.lowercase() }))
}

internal fun parseTalkUserPrivacy(response: TalkTransportResponse): TalkUserPrivacySettings? {
    if (response.status !in 200..299) return null
    val spreed = ((response.talkSettingsData() as? JsonObject)?.get("capabilities") as? JsonObject)
        ?.get("spreed") as? JsonObject ?: return TalkUserPrivacySettings(null, null)
    val chat = (spreed["config"] as? JsonObject)?.get("chat") as? JsonObject
    val features = (spreed["features"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty().toSet()
    return TalkUserPrivacySettings(
        shareReadStatus = chat?.int("read-privacy")?.takeIf { "chat-read-status" in features }?.let { it == 0 },
        shareTyping = chat?.int("typing-privacy")?.takeIf { "typing-privacy" in features }?.let { it == 0 },
    )
}

/** True when a fresh room read shows [change] applied. Membership changes are checked separately. */
internal fun TalkConversationSettings.reflects(change: TalkConversationChange): Boolean = when (change) {
    is TalkConversationChange.Rename -> name == change.name.trim()
    is TalkConversationChange.Describe -> description == change.description.trim()
    is TalkConversationChange.Notifications -> notificationLevel == change.level
    is TalkConversationChange.ReadOnly -> readOnly == change.readOnly
    is TalkConversationChange.Lobby -> lobbyEnabled == change.enabled
    is TalkConversationChange.GuestAccess -> guestsAllowed == change.allowed
    is TalkConversationChange.RemoveParticipant, is TalkConversationChange.Moderator -> true
}

internal fun List<TalkParticipant>.reflects(change: TalkConversationChange): Boolean = when (change) {
    is TalkConversationChange.RemoveParticipant -> none { it.attendeeId == change.attendeeId }
    is TalkConversationChange.Moderator -> firstOrNull { it.attendeeId == change.attendeeId }?.isModerator == change.promote
    else -> true
}

private fun talkConversationType(value: Int?): TalkConversationType = when (value) {
    1 -> TalkConversationType.OneToOne
    2 -> TalkConversationType.Group
    3 -> TalkConversationType.Public
    4 -> TalkConversationType.Changelog
    5 -> TalkConversationType.FormerOneToOne
    6 -> TalkConversationType.NoteToSelf
    else -> TalkConversationType.Unknown
}

private fun TalkTransportResponse.talkSettingsData(): JsonElement? = try {
    ((talkSettingsJson.parseToJsonElement(body.decodeToString()) as? JsonObject)?.get("ocs") as? JsonObject)?.get("data")
} catch (_: IllegalArgumentException) {
    null
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.longOrNull?.toInt() }
private fun Long.requireAttendee(): Long = also { require(it > 0L) { "The attendee ID is invalid." } }

private val talkSettingsJson = Json { ignoreUnknownKeys = true }
private val RENAMEABLE_TYPES = setOf(TalkConversationType.Group, TalkConversationType.Public)
private const val TALK_ROOM_SETTINGS_PREFIX = "/ocs/v2.php/apps/spreed/api/v4/room"
private const val MAX_TALK_NAME_LENGTH = 255
private const val MAX_TALK_DESCRIPTION_LENGTH = 2_000
private const val TALK_SETTINGS_WRITE_BYTES = 1L * 1024L * 1024L
private const val TALK_SETTINGS_LIST_BYTES = 4L * 1024L * 1024L
