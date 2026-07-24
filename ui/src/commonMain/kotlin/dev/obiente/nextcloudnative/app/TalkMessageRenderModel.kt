package dev.obiente.nextcloudnative.app

/** Stable presentation categories aligned with Talk's shared-item classifier. */
enum class TalkMessageRenderKind {
    Text,
    Attachments,
    CallEvent,
    SystemEvent,
    SharedObject,
    Deleted,
}

enum class TalkAttachmentVisual {
    Image,
    Video,
    Audio,
    Voice,
    AudioRecording,
    VideoRecording,
    File,
}

enum class TalkEventTone {
    Neutral,
    Positive,
    Warning,
    Error,
}

enum class TalkSharedObjectKind {
    Poll,
    Location,
    DeckCard,
    Contact,
    Link,
    Other,
}

data class TalkAttachmentRenderModel(
    val attachment: TalkFileAttachment,
    val visual: TalkAttachmentVisual,
    /** A core raster preview is safe even when the share disallows original download. */
    val canLoadServerRaster: Boolean,
    /** Original access is never offered when Talk says `hide-download=yes`. */
    val canDownloadOriginal: Boolean,
) {
    val canOpen: Boolean get() = canLoadServerRaster || canDownloadOriginal

    fun asNextcloudFile(): NextcloudFile = NextcloudFile(
        path = attachment.path ?: "Talk/${attachment.fileId ?: attachment.name}",
        name = attachment.name,
        isDirectory = false,
        mimeType = attachment.mimeType,
        size = attachment.size,
        lastModified = null,
        fileId = attachment.fileId,
        hasPreview = canLoadServerRaster,
        etag = attachment.etag,
        originalAccessAllowed = canDownloadOriginal,
    )
}

sealed interface TalkMessageRenderModel {
    val kind: TalkMessageRenderKind
    val summary: String

    data class Text(
        override val summary: String,
        val markdown: Boolean,
    ) : TalkMessageRenderModel {
        override val kind = TalkMessageRenderKind.Text
    }

    data class Attachments(
        override val summary: String,
        val items: List<TalkAttachmentRenderModel>,
    ) : TalkMessageRenderModel {
        override val kind = TalkMessageRenderKind.Attachments
    }

    data class Event(
        override val kind: TalkMessageRenderKind,
        val title: String,
        override val summary: String,
        val tone: TalkEventTone,
    ) : TalkMessageRenderModel {
        init {
            require(kind == TalkMessageRenderKind.CallEvent || kind == TalkMessageRenderKind.SystemEvent)
        }
    }

    data class SharedObject(
        override val summary: String,
        val title: String,
        val objectKind: TalkSharedObjectKind,
    ) : TalkMessageRenderModel {
        override val kind = TalkMessageRenderKind.SharedObject
    }

    data class Deleted(
        override val summary: String = "Message deleted",
    ) : TalkMessageRenderModel {
        override val kind = TalkMessageRenderKind.Deleted
    }
}

fun TalkMessage.toRenderModel(): TalkMessageRenderModel {
    if (deleted || messageType == TalkMessageType.CommentDeleted) {
        return TalkMessageRenderModel.Deleted()
    }

    return when (val value = content) {
        is TalkMessageContent.Text -> TalkMessageRenderModel.Text(value.summary, value.markdown)
        is TalkMessageContent.FileShare -> TalkMessageRenderModel.Attachments(
            summary = value.summary,
            items = value.attachments.map(TalkFileAttachment::toRenderModel),
        )
        is TalkMessageContent.Call -> TalkMessageRenderModel.Event(
            kind = TalkMessageRenderKind.CallEvent,
            title = value.event.type.displayTitle(),
            summary = value.summary,
            tone = value.event.type.tone(),
        )
        is TalkMessageContent.System -> TalkMessageRenderModel.Event(
            kind = TalkMessageRenderKind.SystemEvent,
            title = value.event.type.displayTitle(),
            summary = value.summary,
            tone = value.event.type.tone(),
        )
        is TalkMessageContent.SharedObject -> TalkMessageRenderModel.SharedObject(
            summary = value.summary,
            title = value.parameter.name.ifBlank { value.parameter.type.humanizedTalkIdentifier() },
            objectKind = value.parameter.toSharedObjectKind(),
        )
    }
}

fun TalkFileAttachment.toRenderModel(): TalkAttachmentRenderModel {
    val normalizedMime = mimeType?.lowercase().orEmpty()
    val visual = when (kind) {
        TalkAttachmentKind.Image -> TalkAttachmentVisual.Image
        TalkAttachmentKind.Video -> TalkAttachmentVisual.Video
        TalkAttachmentKind.Audio -> TalkAttachmentVisual.Audio
        TalkAttachmentKind.Voice -> TalkAttachmentVisual.Voice
        TalkAttachmentKind.Recording -> if (normalizedMime.startsWith("video/")) {
            TalkAttachmentVisual.VideoRecording
        } else {
            TalkAttachmentVisual.AudioRecording
        }
        TalkAttachmentKind.File -> TalkAttachmentVisual.File
    }
    val rasterEligible = visual == TalkAttachmentVisual.Image ||
        visual == TalkAttachmentVisual.Video ||
        visual == TalkAttachmentVisual.VideoRecording ||
        visual == TalkAttachmentVisual.File
    return TalkAttachmentRenderModel(
        attachment = this,
        visual = visual,
        canLoadServerRaster = rasterEligible && previewAvailable && fileId != null,
        canDownloadOriginal = !hideDownload && !path.isNullOrBlank(),
    )
}

private fun TalkRichObjectParameter.toSharedObjectKind(): TalkSharedObjectKind = when {
    type == "talk-poll" -> TalkSharedObjectKind.Poll
    type == "geo-location" -> TalkSharedObjectKind.Location
    type == "deck-card" -> TalkSharedObjectKind.DeckCard
    mimeType == "text/vcard" -> TalkSharedObjectKind.Contact
    !link.isNullOrBlank() -> TalkSharedObjectKind.Link
    else -> TalkSharedObjectKind.Other
}

fun TalkCallEventType.displayTitle(): String = when (this) {
    TalkCallEventType.Started -> "Call started"
    TalkCallEventType.Joined -> "Joined call"
    TalkCallEventType.Left -> "Left call"
    TalkCallEventType.Ended -> "Call ended"
    TalkCallEventType.EndedForEveryone -> "Call ended for everyone"
    TalkCallEventType.Missed -> "Missed call"
    TalkCallEventType.RecordingStarted -> "Recording started"
    TalkCallEventType.RecordingStopped -> "Recording stopped"
    TalkCallEventType.RecordingFailed -> "Recording failed"
    TalkCallEventType.AudioRecordingStarted -> "Audio recording started"
    TalkCallEventType.AudioRecordingStopped -> "Audio recording stopped"
}

private fun TalkCallEventType.tone(): TalkEventTone = when (this) {
    TalkCallEventType.Joined,
    TalkCallEventType.RecordingStarted,
    TalkCallEventType.AudioRecordingStarted,
    -> TalkEventTone.Positive
    TalkCallEventType.Missed -> TalkEventTone.Warning
    TalkCallEventType.RecordingFailed -> TalkEventTone.Error
    else -> TalkEventTone.Neutral
}

fun TalkSystemMessageType.displayTitle(): String = when (this) {
    TalkSystemMessageType.ConversationCreated -> "Conversation created"
    TalkSystemMessageType.ConversationRenamed -> "Conversation renamed"
    TalkSystemMessageType.UserAdded,
    TalkSystemMessageType.GroupAdded,
    TalkSystemMessageType.CircleAdded,
    TalkSystemMessageType.FederatedUserAdded,
    TalkSystemMessageType.PhoneAdded,
    -> "Participant added"
    TalkSystemMessageType.UserRemoved,
    TalkSystemMessageType.GroupRemoved,
    TalkSystemMessageType.CircleRemoved,
    TalkSystemMessageType.FederatedUserRemoved,
    TalkSystemMessageType.PhoneRemoved,
    -> "Participant removed"
    TalkSystemMessageType.ModeratorPromoted,
    TalkSystemMessageType.GuestModeratorPromoted,
    -> "Moderator promoted"
    TalkSystemMessageType.ModeratorDemoted,
    TalkSystemMessageType.GuestModeratorDemoted,
    -> "Moderator demoted"
    TalkSystemMessageType.MessagePinned -> "Message pinned"
    TalkSystemMessageType.MessageUnpinned -> "Message unpinned"
    TalkSystemMessageType.HistoryCleared -> "History cleared"
    TalkSystemMessageType.PollClosed -> "Poll closed"
    TalkSystemMessageType.PollVoted -> "Poll updated"
    TalkSystemMessageType.Unknown -> "Conversation update"
    else -> "Conversation update"
}

private fun TalkSystemMessageType.tone(): TalkEventTone = when (this) {
    TalkSystemMessageType.HistoryCleared,
    TalkSystemMessageType.MessageExpirationEnabled,
    -> TalkEventTone.Warning
    TalkSystemMessageType.RecordingFailed -> TalkEventTone.Error
    else -> TalkEventTone.Neutral
}

private fun String.humanizedTalkIdentifier(): String =
    replace('-', ' ').replace('_', ' ').replaceFirstChar { it.uppercase() }.ifBlank { "Shared item" }
