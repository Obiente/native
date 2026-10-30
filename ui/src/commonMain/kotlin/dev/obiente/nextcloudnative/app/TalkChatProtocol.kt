package dev.obiente.nextcloudnative.app

import kotlin.random.Random
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

enum class TalkTransportMethod { GET, POST, PUT, DELETE }

/**
 * One reviewed Talk OCS request, built only by [TalkChatRequests].
 *
 * Platform transports attach the account session and `OCS-APIRequest`, reject cross-origin
 * redirects, bound the response to [maximumResponseBytes], and cancel the network call with the
 * calling coroutine. A long poll may stay open for [serverWaitSeconds]; the transport read timeout
 * must exceed it.
 */
class TalkTransportRequest internal constructor(
    val method: TalkTransportMethod,
    val relativePath: String,
    val queryParameters: List<Pair<String, String>>,
    val formFields: List<Pair<String, String>>,
    val maximumResponseBytes: Long,
    val serverWaitSeconds: Int = 0,
) {
    init {
        require(relativePath.startsWith(TALK_API_PREFIX) || relativePath.startsWith("$TALK_ROOM_PREFIX/") || relativePath == TALK_CAPABILITIES_PATH)
        require(formFields.isEmpty() || method != TalkTransportMethod.GET)
        require(maximumResponseBytes in 1..TALK_MAX_RESPONSE_BYTES)
        require(serverWaitSeconds in 0..TALK_MAX_POLL_WAIT_SECONDS)
    }

    val isRead: Boolean get() = method == TalkTransportMethod.GET

    fun url(serverUrl: String): String = buildString {
        append(serverUrl.trimEnd('/'))
        append(relativePath)
        append("?format=json")
        queryParameters.forEach { (name, value) ->
            append('&').append(encodeUrlComponent(name)).append('=').append(encodeUrlComponent(value))
        }
    }

    fun encodedFormBody(): ByteArray? = formFields.takeIf(List<*>::isNotEmpty)
        ?.joinToString("&") { (name, value) -> "${encodeUrlComponent(name)}=${encodeUrlComponent(value)}" }
        ?.encodeToByteArray()
}

class TalkTransportResponse(
    val status: Int,
    val body: ByteArray,
    /** `X-Chat-Last-Given`, the cursor for the next history page or long poll. */
    val chatLastGiven: Long?,
)

/** Talk features that gate client behavior. Names follow Spreed's `docs/capabilities.md`. */
data class TalkCapabilities(
    val features: Set<String>,
    val maxMessageLength: Int = DEFAULT_TALK_MAX_MESSAGE_LENGTH,
) {
    val replies: Boolean get() = "chat-replies" in features
    val reactions: Boolean get() = "reactions" in features
    val editMessages: Boolean get() = "edit-messages" in features
    val editNoteToSelfWithoutLimit: Boolean get() = "edit-messages-note-to-self" in features
    val deleteMessages: Boolean get() = "delete-messages" in features
    val deleteWithoutLimit: Boolean get() = "delete-messages-unlimited" in features
    val deleteSharedObjects: Boolean get() = "rich-object-delete" in features
    val referenceIds: Boolean get() = "chat-reference-id" in features
    val messageContext: Boolean get() = "chat-get-context" in features
    val chatPermission: Boolean get() = "chat-permission" in features
    val reactPermission: Boolean get() = "react-permission" in features

    companion object {
        val None = TalkCapabilities(emptySet())
    }
}

enum class TalkConversationType { OneToOne, Group, Public, Changelog, FormerOneToOne, NoteToSelf, Unknown }

/** The current participant's view of one conversation, read from `room/{token}`. */
data class TalkConversationAccess(
    val token: String,
    val type: TalkConversationType,
    val readOnly: Boolean,
    val lobbyBlocksParticipant: Boolean,
    /** Combined attendee permissions, or null when the server did not return them. */
    val permissions: Int?,
)

/** Which writes this client may offer, derived from capabilities and conversation access. */
data class TalkChatAbilities(
    val capabilities: TalkCapabilities,
    val access: TalkConversationAccess?,
) {
    private val conversationWritable: Boolean
        get() = access != null && !access.readOnly && !access.lobbyBlocksParticipant

    val canPost: Boolean
        get() = conversationWritable && hasPermission(TALK_PERMISSION_CHAT, capabilities.chatPermission)

    val canReact: Boolean
        get() = capabilities.reactions && conversationWritable && if (capabilities.reactPermission) {
            hasPermission(TALK_PERMISSION_REACT, required = true)
        } else {
            hasPermission(TALK_PERMISSION_CHAT, capabilities.chatPermission)
        }

    fun canReply(message: TalkMessage): Boolean =
        capabilities.replies && canPost && message.isReplyable && !message.deleted && message.id > 0L

    fun canEdit(message: TalkMessage, ownActorId: String, nowSeconds: Long): Boolean {
        if (!capabilities.editMessages || !canPost || !message.isOwnComment(ownActorId)) return false
        // Mentions and other rich parameters are shown resolved; editing that text would drop them.
        if (message.content !is TalkMessageContent.Text || message.parameters.isNotEmpty()) return false
        val unlimited = capabilities.editNoteToSelfWithoutLimit && access?.type == TalkConversationType.NoteToSelf
        return unlimited || message.ageSeconds(nowSeconds) in 0 until TALK_EDIT_WINDOW_SECONDS
    }

    fun canDelete(message: TalkMessage, ownActorId: String, nowSeconds: Long): Boolean {
        if (!capabilities.deleteMessages || !conversationWritable || !message.isOwnComment(ownActorId)) return false
        val deletableContent = message.content is TalkMessageContent.Text || capabilities.deleteSharedObjects
        if (!deletableContent) return false
        return capabilities.deleteWithoutLimit || message.ageSeconds(nowSeconds) in 0 until TALK_DELETE_WINDOW_SECONDS
    }

    private fun hasPermission(flag: Int, required: Boolean): Boolean {
        if (!required) return true
        val permissions = access?.permissions ?: return false
        return permissions and flag != 0
    }
}

private fun TalkMessage.isOwnComment(ownActorId: String): Boolean =
    ownActorId.isNotBlank() && actorType == "users" && actorId == ownActorId && id > 0L &&
        messageType == TalkMessageType.Comment && !deleted && !isSystemMessage

private fun TalkMessage.ageSeconds(nowSeconds: Long): Long = if (timestamp <= 0L) -1L else nowSeconds - timestamp

enum class TalkFailureKind {
    Offline,
    Unauthorized,
    Forbidden,
    NotFound,
    Lobby,
    TooLong,
    NotAllowed,
    Throttled,
    Server,
    Malformed,
}

data class TalkFailure(val kind: TalkFailureKind) {
    val message: String get() = when (kind) {
        TalkFailureKind.Offline -> "Can't reach your server. Check your connection."
        TalkFailureKind.Unauthorized -> "Sign in to this account again to keep chatting."
        TalkFailureKind.Forbidden -> "This conversation does not allow that for your account."
        TalkFailureKind.NotFound -> "This conversation or message is no longer available."
        TalkFailureKind.Lobby -> "The conversation lobby is active. Wait for a moderator to open it."
        TalkFailureKind.TooLong -> "The message is longer than the server allows."
        TalkFailureKind.NotAllowed -> "The server no longer allows changing this message."
        TalkFailureKind.Throttled -> "The server asked to slow down. Wait a moment before trying again."
        TalkFailureKind.Server -> "The server could not complete the request."
        TalkFailureKind.Malformed -> "Talk sent an unexpected response."
    }

    /** Failures that a later poll cannot fix without the user or the server changing something. */
    val stopsLiveUpdates: Boolean get() = kind in setOf(
        TalkFailureKind.Unauthorized,
        TalkFailureKind.Forbidden,
        TalkFailureKind.NotFound,
        TalkFailureKind.Lobby,
    )
}

internal fun talkFailureForStatus(status: Int): TalkFailure? = when (status) {
    in 200..299, 304 -> null
    400, 405 -> TalkFailure(TalkFailureKind.NotAllowed)
    401 -> TalkFailure(TalkFailureKind.Unauthorized)
    403 -> TalkFailure(TalkFailureKind.Forbidden)
    404 -> TalkFailure(TalkFailureKind.NotFound)
    412 -> TalkFailure(TalkFailureKind.Lobby)
    413 -> TalkFailure(TalkFailureKind.TooLong)
    429 -> TalkFailure(TalkFailureKind.Throttled)
    else -> TalkFailure(TalkFailureKind.Server)
}

/**
 * Outcome of a Talk write. [Unknown] means the server may or may not have applied it, so callers
 * verify the postcondition before offering to try again.
 */
internal sealed interface TalkWriteOutcome<out T> {
    data class Confirmed<T>(val value: T) : TalkWriteOutcome<T>
    data class Rejected(val failure: TalkFailure) : TalkWriteOutcome<Nothing>
    data object Unknown : TalkWriteOutcome<Nothing>
}

/**
 * Classifies a write response. Statuses that Talk documents as a decision are definitive; a gateway
 * timeout or other 5xx can follow a change that was already stored, so it stays unknown.
 */
internal fun <T> talkWriteOutcome(response: TalkTransportResponse, parse: (TalkTransportResponse) -> T): TalkWriteOutcome<T> =
    when (response.status) {
        in 200..299 -> TalkWriteOutcome.Confirmed(parse(response))
        in 400..499, 502 -> TalkWriteOutcome.Rejected(requireNotNull(talkFailureForStatus(response.status)))
        else -> TalkWriteOutcome.Unknown
    }

internal sealed interface TalkChatRead {
    data class Messages(val entries: List<TalkChatEntry>, val lastGiven: Long?) : TalkChatRead
    data object NotModified : TalkChatRead
    data class Failed(val failure: TalkFailure) : TalkChatRead
}

/** Reviewed Talk endpoints from Spreed's `docs/chat.md`, `docs/reaction.md`, and `docs/conversation.md`. */
internal object TalkChatRequests {
    fun capabilities(): TalkTransportRequest = TalkTransportRequest(
        TalkTransportMethod.GET, TALK_CAPABILITIES_PATH, emptyList(), emptyList(), TALK_CAPABILITIES_RESPONSE_BYTES,
    )

    fun conversation(token: String): TalkTransportRequest = TalkTransportRequest(
        TalkTransportMethod.GET, "$TALK_ROOM_PREFIX/${token.pathSegment()}", emptyList(), emptyList(),
        TALK_SMALL_RESPONSE_BYTES,
    )

    fun history(token: String, olderCursor: Long?, limit: Int = DEFAULT_TALK_MESSAGE_PAGE_SIZE): TalkTransportRequest {
        require(limit in 1..MAX_TALK_MESSAGE_PAGE_SIZE)
        require(olderCursor == null || olderCursor >= 0L)
        return chatRead(token, lookIntoFuture = false, lastKnown = olderCursor ?: 0L, limit = limit, waitSeconds = 0)
    }

    /** Waits up to [waitSeconds] for messages after [lastKnownMessageId]; the server answers 304 when none arrive. */
    fun poll(token: String, lastKnownMessageId: Long, waitSeconds: Int = TALK_POLL_WAIT_SECONDS): TalkTransportRequest {
        require(lastKnownMessageId >= 0L)
        require(waitSeconds in 1..TALK_MAX_POLL_WAIT_SECONDS)
        return chatRead(token, lookIntoFuture = true, lastKnown = lastKnownMessageId, limit = MAX_TALK_MESSAGE_PAGE_SIZE, waitSeconds = waitSeconds)
    }

    fun context(token: String, messageId: Long): TalkTransportRequest = TalkTransportRequest(
        TalkTransportMethod.GET, "${chatPath(token)}/${messageId.requireMessageId()}/context",
        listOf("limit" to "1"), emptyList(), TALK_CHAT_RESPONSE_BYTES,
    )

    fun send(token: String, message: String, replyTo: Long?, referenceId: String?): TalkTransportRequest {
        require(message.isNotBlank())
        require(referenceId == null || referenceId.isTalkReferenceId())
        return TalkTransportRequest(
            TalkTransportMethod.POST, chatPath(token), emptyList(),
            buildList {
                add("message" to message)
                replyTo?.let { add("replyTo" to it.requireMessageId().toString()) }
                referenceId?.let { add("referenceId" to it) }
            },
            TALK_SMALL_RESPONSE_BYTES,
        )
    }

    fun edit(token: String, messageId: Long, message: String): TalkTransportRequest {
        require(message.isNotBlank())
        return TalkTransportRequest(
            TalkTransportMethod.PUT, "${chatPath(token)}/${messageId.requireMessageId()}", emptyList(),
            listOf("message" to message), TALK_SMALL_RESPONSE_BYTES,
        )
    }

    fun delete(token: String, messageId: Long): TalkTransportRequest = TalkTransportRequest(
        TalkTransportMethod.DELETE, "${chatPath(token)}/${messageId.requireMessageId()}", emptyList(), emptyList(),
        TALK_SMALL_RESPONSE_BYTES,
    )

    fun react(token: String, messageId: Long, emoji: String, add: Boolean): TalkTransportRequest {
        val reaction = emoji.requireReaction()
        val path = "$TALK_API_PREFIX/reaction/${token.pathSegment()}/${messageId.requireMessageId()}"
        return if (add) {
            TalkTransportRequest(TalkTransportMethod.POST, path, emptyList(), listOf("reaction" to reaction), TALK_SMALL_RESPONSE_BYTES)
        } else {
            TalkTransportRequest(TalkTransportMethod.DELETE, path, listOf("reaction" to reaction), emptyList(), TALK_SMALL_RESPONSE_BYTES)
        }
    }

    fun reactions(token: String, messageId: Long, emoji: String): TalkTransportRequest = TalkTransportRequest(
        TalkTransportMethod.GET,
        "$TALK_API_PREFIX/reaction/${token.pathSegment()}/${messageId.requireMessageId()}",
        listOf("reaction" to emoji.requireReaction()), emptyList(), TALK_SMALL_RESPONSE_BYTES,
    )

    private fun chatRead(token: String, lookIntoFuture: Boolean, lastKnown: Long, limit: Int, waitSeconds: Int) =
        TalkTransportRequest(
            method = TalkTransportMethod.GET,
            relativePath = chatPath(token),
            queryParameters = buildList {
                add("lookIntoFuture" to if (lookIntoFuture) "1" else "0")
                add("limit" to limit.toString())
                add("lastKnownMessageId" to lastKnown.toString())
                add("includeLastKnown" to "0")
                if (lookIntoFuture) add("timeout" to waitSeconds.toString())
                // Reading must not write: the read marker, notifications, and presence stay unchanged.
                add("setReadMarker" to "0")
                add("markNotificationsAsRead" to "0")
                add("noStatusUpdate" to "1")
            },
            formFields = emptyList(),
            maximumResponseBytes = TALK_CHAT_RESPONSE_BYTES,
            serverWaitSeconds = waitSeconds,
        )

    private fun chatPath(token: String) = "$TALK_API_PREFIX/chat/${token.pathSegment()}"
}

private fun String.pathSegment(): String {
    require(isNotBlank() && length <= MAX_TALK_TOKEN_LENGTH && none { it.isWhitespace() || it.isISOControl() }) {
        "The Talk conversation token is invalid."
    }
    return encodeUrlComponent(this)
}

private fun Long.requireMessageId(): Long = also { require(it > 0L) { "The Talk message ID is invalid." } }

private fun String.requireReaction(): String = also {
    require(isNotBlank() && length <= MAX_TALK_REACTION_LENGTH && none(Char::isWhitespace)) { "The reaction is invalid." }
}

internal fun String.isTalkReferenceId(): Boolean = length == 64 && all { it in '0'..'9' || it in 'a'..'f' }

/** A random reference that lets this client find its own message after an unconfirmed send. */
internal fun newTalkReferenceId(random: Random = Random.Default): String =
    random.nextBytes(32).joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

/** Talk counts message length in characters, so a surrogate pair counts once. */
internal fun talkMessageLength(message: String): Int = message.count { !it.isLowSurrogate() }

private val talkProtocolJson = Json { ignoreUnknownKeys = true }

private fun TalkTransportResponse.ocsData(): JsonElement? = runCatching {
    (talkProtocolJson.parseToJsonElement(body.decodeToString()) as? JsonObject)
        ?.objectValue("ocs")
        ?.get("data")
}.getOrNull()

internal fun parseTalkCapabilities(response: TalkTransportResponse): TalkCapabilities? {
    if (response.status !in 200..299) return null
    val capabilities = (response.ocsData() as? JsonObject)?.objectValue("capabilities") ?: return null
    val spreed = capabilities.objectValue("spreed") ?: return TalkCapabilities.None
    val features = (spreed["features"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
        ?.toSet()
        .orEmpty()
    val maxLength = spreed.objectValue("config")?.objectValue("chat")
        ?.let { (it["max-length"] as? JsonPrimitive)?.intOrNull }
        ?.takeIf { it > 0 }
        ?: DEFAULT_TALK_MAX_MESSAGE_LENGTH
    return TalkCapabilities(features, maxLength)
}

internal fun parseTalkConversationAccess(response: TalkTransportResponse): TalkConversationAccess? {
    val room = response.ocsData() as? JsonObject ?: return null
    val token = room.string("token")?.takeIf(String::isNotBlank) ?: return null
    val participantType = room.int("participantType")
    val moderator = participantType == 1 || participantType == 2 || participantType == 6
    return TalkConversationAccess(
        token = token,
        type = when (room.int("type")) {
            1 -> TalkConversationType.OneToOne
            2 -> TalkConversationType.Group
            3 -> TalkConversationType.Public
            4 -> TalkConversationType.Changelog
            5 -> TalkConversationType.FormerOneToOne
            6 -> TalkConversationType.NoteToSelf
            else -> TalkConversationType.Unknown
        },
        // An unknown read-only state is treated as read-only so writes stay disabled.
        readOnly = room.int("readOnly") != 0,
        lobbyBlocksParticipant = room.int("lobbyState") == 1 && !moderator,
        permissions = room.int("permissions"),
    )
}

internal fun parseTalkChatRead(response: TalkTransportResponse): TalkChatRead {
    if (response.status == 304) return TalkChatRead.NotModified
    talkFailureForStatus(response.status)?.let { return TalkChatRead.Failed(it) }
    val data = response.ocsData() as? JsonArray ?: return TalkChatRead.Failed(TalkFailure(TalkFailureKind.Malformed))
    val entries = data.mapNotNull { element ->
        (element as? JsonObject)?.let(::parseTalkChatEntry)?.takeIf { it.message.id > 0L }
    }
    return TalkChatRead.Messages(entries, response.chatLastGiven)
}

/** The created or changed message returned by send, edit, and delete, or null when the body is unusable. */
internal fun parseTalkWrittenEntry(response: TalkTransportResponse): TalkChatEntry? =
    (response.ocsData() as? JsonObject)?.let(::parseTalkChatEntry)?.takeIf { it.message.id > 0L }

/**
 * Reactions from a reaction API response, with this account's own reactions marked, or null when
 * the response cannot be trusted. Each emoji maps to the list of actors who reacted with it.
 */
internal fun parseTalkReactions(response: TalkTransportResponse, ownActorId: String): List<TalkReaction>? {
    if (response.status !in 200..299) return null
    return when (val data = response.ocsData()) {
        // PHP encodes an empty reaction map as an empty list.
        is JsonArray -> if (data.isEmpty()) emptyList() else null
        is JsonObject -> data.entries.mapNotNull { (emoji, value) ->
            val actors = (value as? JsonArray)?.filterIsInstance<JsonObject>() ?: return null
            if (emoji.isBlank() || actors.isEmpty()) return@mapNotNull null
            TalkReaction(
                emoji = emoji,
                count = actors.size,
                reactedByMe = actors.any { it.string("actorType") == "users" && it.string("actorId") == ownActorId },
            )
        }
        else -> null
    }
}

private fun JsonObject.objectValue(key: String): JsonObject? = get(key) as? JsonObject

private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull

private fun JsonObject.int(key: String): Int? = (get(key) as? JsonPrimitive)?.let { it.intOrNull ?: it.longOrNull?.toInt() }

internal const val TALK_API_PREFIX = "/ocs/v2.php/apps/spreed/api/v1"
private const val TALK_ROOM_PREFIX = "/ocs/v2.php/apps/spreed/api/v4/room"
private const val TALK_CAPABILITIES_PATH = "/ocs/v2.php/cloud/capabilities"
internal const val TALK_POLL_WAIT_SECONDS = 30
private const val TALK_MAX_POLL_WAIT_SECONDS = 60
internal const val DEFAULT_TALK_MAX_MESSAGE_LENGTH = 32_000
private const val TALK_PERMISSION_CHAT = 128
private const val TALK_PERMISSION_REACT = 256
private const val TALK_EDIT_WINDOW_SECONDS = 24L * 60L * 60L
private const val TALK_DELETE_WINDOW_SECONDS = 6L * 60L * 60L
private const val MAX_TALK_TOKEN_LENGTH = 64
private const val MAX_TALK_REACTION_LENGTH = 32
private const val TALK_SMALL_RESPONSE_BYTES = 1L * 1024L * 1024L
private const val TALK_CHAT_RESPONSE_BYTES = 8L * 1024L * 1024L
private const val TALK_CAPABILITIES_RESPONSE_BYTES = 2L * 1024L * 1024L
private const val TALK_MAX_RESPONSE_BYTES = 8L * 1024L * 1024L
