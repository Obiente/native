package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.template.replaceBracedTemplateTokens

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

enum class TalkMessageType(val wireValue: String) {
    Comment("comment"),
    System("system"),
    ObjectShared("object_shared"),
    Command("command"),
    CommentDeleted("comment_deleted"),
    VoiceMessage("voice-message"),
    AudioRecording("record-audio"),
    VideoRecording("record-video"),
    Unknown(""),
    ;

    companion object {
        fun fromWireValue(value: String?): TalkMessageType =
            entries.firstOrNull { it.wireValue == value } ?: Unknown
    }
}

/** System message identifiers published by Talk's current `MESSAGE.SYSTEM_TYPE` constants. */
enum class TalkSystemMessageType(val wireValue: String) {
    AudioRecordingStarted("audio_recording_started"),
    AudioRecordingStopped("audio_recording_stopped"),
    AvatarRemoved("avatar_removed"),
    AvatarSet("avatar_set"),
    BreakoutRoomsStarted("breakout_rooms_started"),
    BreakoutRoomsStopped("breakout_rooms_stopped"),
    CallEnded("call_ended"),
    CallEndedEveryone("call_ended_everyone"),
    CallJoined("call_joined"),
    CallLeft("call_left"),
    CallMissed("call_missed"),
    CallStarted("call_started"),
    CircleAdded("circle_added"),
    CircleRemoved("circle_removed"),
    ConversationCreated("conversation_created"),
    ConversationRenamed("conversation_renamed"),
    DescriptionRemoved("description_removed"),
    DescriptionSet("description_set"),
    FederatedUserAdded("federated_user_added"),
    FederatedUserRemoved("federated_user_removed"),
    FileShared("file_shared"),
    GroupAdded("group_added"),
    GroupRemoved("group_removed"),
    GuestModeratorDemoted("guest_moderator_demoted"),
    GuestModeratorPromoted("guest_moderator_promoted"),
    GuestsAllowed("guests_allowed"),
    GuestsDisallowed("guests_disallowed"),
    HistoryCleared("history_cleared"),
    ListableAll("listable_all"),
    ListableNone("listable_none"),
    ListableUsers("listable_users"),
    LobbyNonModerators("lobby_non_moderators"),
    LobbyNone("lobby_none"),
    LobbyTimerReached("lobby_timer_reached"),
    MatterbridgeConfigAdded("matterbridge_config_added"),
    MatterbridgeConfigDisabled("matterbridge_config_disabled"),
    MatterbridgeConfigEdited("matterbridge_config_edited"),
    MatterbridgeConfigEnabled("matterbridge_config_enabled"),
    MatterbridgeConfigRemoved("matterbridge_config_removed"),
    MessageDeleted("message_deleted"),
    MessageEdited("message_edited"),
    MessageExpirationDisabled("message_expiration_disabled"),
    MessageExpirationEnabled("message_expiration_enabled"),
    MessagePinned("message_pinned"),
    MessageUnpinned("message_unpinned"),
    ModeratorDemoted("moderator_demoted"),
    ModeratorPromoted("moderator_promoted"),
    ObjectShared("object_shared"),
    PasswordRemoved("password_removed"),
    PasswordSet("password_set"),
    PhoneAdded("phone_added"),
    PhoneRemoved("phone_removed"),
    PollClosed("poll_closed"),
    PollVoted("poll_voted"),
    PreserveConversation("preserve_conversation"),
    PreserveConversationOff("preserve_conversation_off"),
    Reaction("reaction"),
    ReactionDeleted("reaction_deleted"),
    ReactionRevoked("reaction_revoked"),
    ReadOnly("read_only"),
    ReadOnlyOff("read_only_off"),
    RecordingFailed("recording_failed"),
    RecordingStarted("recording_started"),
    RecordingStopped("recording_stopped"),
    ThreadCreated("thread_created"),
    ThreadRenamed("thread_renamed"),
    UserAdded("user_added"),
    UserRemoved("user_removed"),
    Unknown(""),
    ;

    companion object {
        fun fromWireValue(value: String?): TalkSystemMessageType =
            entries.firstOrNull { it.wireValue == value } ?: Unknown
    }
}

data class TalkRichObjectParameter(
    val type: String,
    val id: String,
    val name: String,
    val server: String?,
    val link: String?,
    val callType: String?,
    val iconUrl: String?,
    val messageId: String?,
    val boardName: String?,
    val stackName: String?,
    val size: Long?,
    val path: String?,
    val mimeType: String?,
    val previewAvailable: Boolean,
    val hideDownload: Boolean,
    val modifiedTime: Long?,
    val latitude: Double?,
    val longitude: Double?,
    val description: String?,
    val thumbnailUrl: String?,
    val website: String?,
    val visibility: Boolean?,
    val assignable: Boolean?,
    val conversation: String?,
    val etag: String?,
    val permissions: String?,
    val width: Int?,
    val height: Int?,
    val blurHash: String?,
)

enum class TalkAttachmentKind { Image, Video, Audio, Voice, Recording, File }

data class TalkFileAttachment(
    val fileId: Long?,
    val name: String,
    val path: String?,
    val mimeType: String?,
    val size: Long?,
    val previewAvailable: Boolean,
    val hideDownload: Boolean,
    val etag: String?,
    val width: Int?,
    val height: Int?,
    val kind: TalkAttachmentKind,
)

enum class TalkCallEventType {
    Started,
    Joined,
    Left,
    Ended,
    EndedForEveryone,
    Missed,
    RecordingStarted,
    RecordingStopped,
    RecordingFailed,
    AudioRecordingStarted,
    AudioRecordingStopped,
}

data class TalkCallEvent(
    val type: TalkCallEventType,
    val actorDisplayName: String,
)

data class TalkSystemEvent(
    val type: TalkSystemMessageType,
    val rawType: String,
    val actorDisplayName: String,
)

sealed interface TalkMessageContent {
    val summary: String

    data class Text(
        override val summary: String,
        val markdown: Boolean,
    ) : TalkMessageContent

    data class FileShare(
        override val summary: String,
        val attachments: List<TalkFileAttachment>,
    ) : TalkMessageContent {
        init {
            require(attachments.isNotEmpty())
        }

        /** Compatibility accessor for callers that only render the first shared file. */
        val attachment: TalkFileAttachment get() = attachments.first()
    }

    data class Call(
        override val summary: String,
        val event: TalkCallEvent,
    ) : TalkMessageContent

    data class System(
        override val summary: String,
        val event: TalkSystemEvent,
    ) : TalkMessageContent

    data class SharedObject(
        override val summary: String,
        val parameter: TalkRichObjectParameter,
    ) : TalkMessageContent

}

private val talkJson = Json { ignoreUnknownKeys = true }

/** Parses a Talk OpenAPI ChatMessage/RoomLastMessage without platform-specific JSON types. */
fun parseTalkMessageJson(rawJson: String): TalkMessage? = runCatching {
    parseTalkMessage(talkJson.parseToJsonElement(rawJson).jsonObject)
}.getOrNull()

/** Merges an older Talk history page without duplicating the cursor boundary message. */
fun mergeTalkMessageHistory(
    current: List<TalkMessage>,
    older: List<TalkMessage>,
): List<TalkMessage> = (current + older).distinctBy(TalkMessage::id)

private fun parseTalkMessage(json: JsonObject): TalkMessage {
    val parameters = json.objectValue("messageParameters")
        ?.mapNotNull { (key, value) ->
            val parameter = value as? JsonObject ?: return@mapNotNull null
            key to parameter.toTalkRichObjectParameter()
        }
        ?.toMap()
        .orEmpty()
    val rawMessage = json.stringValue("message").orEmpty()
    val renderedMessage = rawMessage.replaceBracedTemplateTokens { name, original ->
        parameters[name]?.name?.takeIf(String::isNotBlank) ?: original
    }
    val rawSystemMessage = json.stringValue("systemMessage")?.takeIf(String::isNotBlank)
    val systemMessage = TalkSystemMessageType.fromWireValue(rawSystemMessage)
    val messageType = TalkMessageType.fromWireValue(json.stringValue("messageType"))
    val actorDisplayName = json.stringValue("actorDisplayName").orEmpty().ifBlank { "Nextcloud" }
    val fallbackSummary = rawSystemMessage?.humanizeIdentifier() ?: "Message"
    val summary = renderedMessage.ifBlank { fallbackSummary }
    val files = parameters
        .filter { (key, parameter) ->
            parameter.type == "file" ||
                (key.startsWith("file") && (parameter.path != null || parameter.mimeType != null))
        }
        .values
        .map { it.toTalkFileAttachment(messageType) }
    val sharedObject = parameters["object"] ?: parameters.entries
        .firstOrNull { (key, parameter) -> key.startsWith("object") && parameter.type != "file" }
        ?.value
    val callType = systemMessage.toCallEventType()
    val content = when {
        files.isNotEmpty() -> TalkMessageContent.FileShare(
            summary = summary,
            attachments = files,
        )
        callType != null -> TalkMessageContent.Call(
            summary = summary,
            event = TalkCallEvent(callType, actorDisplayName),
        )
        rawSystemMessage != null -> TalkMessageContent.System(
            summary = summary,
            event = TalkSystemEvent(systemMessage, rawSystemMessage, actorDisplayName),
        )
        sharedObject != null -> TalkMessageContent.SharedObject(summary, sharedObject)
        else -> TalkMessageContent.Text(
            summary = summary,
            markdown = json.booleanValue("markdown") ?: false,
        )
    }

    return TalkMessage(
        id = json.longValue("id") ?: 0L,
        actorDisplayName = actorDisplayName,
        actorId = json.stringValue("actorId").orEmpty(),
        actorType = json.stringValue("actorType").orEmpty(),
        message = summary,
        timestamp = json.longValue("timestamp") ?: 0L,
        messageType = messageType,
        systemMessage = systemMessage,
        systemMessageName = rawSystemMessage,
        parameters = parameters,
        content = content,
    )
}

private fun JsonObject.toTalkRichObjectParameter(): TalkRichObjectParameter = TalkRichObjectParameter(
    type = stringValue("type").orEmpty(),
    id = stringValue("id").orEmpty(),
    name = stringValue("name").orEmpty(),
    server = stringValue("server"),
    link = stringValue("link"),
    callType = stringValue("call-type"),
    iconUrl = stringValue("icon-url"),
    messageId = stringValue("message-id"),
    boardName = stringValue("boardname"),
    stackName = stringValue("stackname"),
    size = longValue("size"),
    path = stringValue("path"),
    mimeType = stringValue("mimetype"),
    previewAvailable = stringValue("preview-available") == "yes",
    hideDownload = stringValue("hide-download") == "yes",
    modifiedTime = longValue("mtime"),
    latitude = stringValue("latitude")?.toDoubleOrNull(),
    longitude = stringValue("longitude")?.toDoubleOrNull(),
    description = stringValue("description"),
    thumbnailUrl = stringValue("thumb"),
    website = stringValue("website"),
    visibility = stringValue("visibility")?.toBinaryBoolean(),
    assignable = stringValue("assignable")?.toBinaryBoolean(),
    conversation = stringValue("conversation"),
    etag = stringValue("etag"),
    permissions = stringValue("permissions"),
    width = stringValue("width")?.toIntOrNull(),
    height = stringValue("height")?.toIntOrNull(),
    blurHash = stringValue("blurhash"),
)

private fun TalkRichObjectParameter.toTalkFileAttachment(messageType: TalkMessageType): TalkFileAttachment {
    val attachmentKind = when {
        messageType == TalkMessageType.VoiceMessage -> TalkAttachmentKind.Voice
        messageType == TalkMessageType.AudioRecording || messageType == TalkMessageType.VideoRecording ->
            TalkAttachmentKind.Recording
        mimeType?.startsWith("image/") == true -> TalkAttachmentKind.Image
        mimeType?.startsWith("video/") == true -> TalkAttachmentKind.Video
        mimeType?.startsWith("audio/") == true -> TalkAttachmentKind.Audio
        else -> TalkAttachmentKind.File
    }
    return TalkFileAttachment(
        fileId = id.toLongOrNull(),
        name = name.ifBlank { path?.substringAfterLast('/')?.takeIf(String::isNotBlank) ?: "Shared file" },
        path = path,
        mimeType = mimeType,
        size = size,
        previewAvailable = previewAvailable,
        hideDownload = hideDownload,
        etag = etag,
        width = width,
        height = height,
        kind = attachmentKind,
    )
}

private fun TalkSystemMessageType.toCallEventType(): TalkCallEventType? = when (this) {
    TalkSystemMessageType.CallStarted -> TalkCallEventType.Started
    TalkSystemMessageType.CallJoined -> TalkCallEventType.Joined
    TalkSystemMessageType.CallLeft -> TalkCallEventType.Left
    TalkSystemMessageType.CallEnded -> TalkCallEventType.Ended
    TalkSystemMessageType.CallEndedEveryone -> TalkCallEventType.EndedForEveryone
    TalkSystemMessageType.CallMissed -> TalkCallEventType.Missed
    TalkSystemMessageType.RecordingStarted -> TalkCallEventType.RecordingStarted
    TalkSystemMessageType.RecordingStopped -> TalkCallEventType.RecordingStopped
    TalkSystemMessageType.RecordingFailed -> TalkCallEventType.RecordingFailed
    TalkSystemMessageType.AudioRecordingStarted -> TalkCallEventType.AudioRecordingStarted
    TalkSystemMessageType.AudioRecordingStopped -> TalkCallEventType.AudioRecordingStopped
    else -> null
}

private fun JsonObject.stringValue(key: String): String? =
    (get(key) as? JsonPrimitive)?.contentOrNull

private fun JsonObject.longValue(key: String): Long? =
    (get(key) as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }

private fun JsonObject.booleanValue(key: String): Boolean? =
    (get(key) as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() }

private fun JsonObject.objectValue(key: String): JsonObject? = get(key) as? JsonObject

private fun String.toBinaryBoolean(): Boolean? = when (this) {
    "1" -> true
    "0" -> false
    else -> null
}

private fun String.humanizeIdentifier(): String =
    replace('_', ' ').replaceFirstChar { it.uppercase() }
