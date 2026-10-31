package dev.obiente.nextcloudnative.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.time.Instant

@JvmInline
value class DeckBoardId(val value: Long) {
    init {
        require(value > 0L) { "The board id is invalid." }
    }
}

data class DeckStackContext(
    val boardId: DeckBoardId,
    val stackId: Long,
) {
    init {
        require(stackId > 0L) { "The stack id is invalid." }
    }
}

data class DeckCardContext(
    val stack: DeckStackContext,
    val cardId: Long,
) {
    init {
        require(cardId > 0L) { "The card id is invalid." }
    }
}

data class DeckBoardDraft(
    val title: String,
    val color: String,
) {
    internal val normalizedTitle = title.requireDeckText("Board title", DECK_BOARD_TITLE_LIMIT)
    internal val normalizedColor = color.requireDeckColor()
}

data class DeckBoardUpdate(
    val title: String,
    val color: String,
    val archived: Boolean,
) {
    internal val normalizedTitle = title.requireDeckText("Board title", DECK_BOARD_TITLE_LIMIT)
    internal val normalizedColor = color.requireDeckColor()
}

data class DeckStackDraft(
    val title: String,
    val order: Long,
) {
    internal val normalizedTitle = title.requireDeckText("Stack title", DECK_STACK_TITLE_LIMIT)

    init {
        require(order >= 0) { "The stack order is invalid." }
    }
}

data class DeckLabelDraft(
    val title: String,
    val color: String,
) {
    internal val normalizedTitle = title.requireDeckText("Label title", DECK_LABEL_TITLE_LIMIT)
    internal val normalizedColor = color.requireDeckColor()
}

data class DeckCardDraft(
    val title: String,
    val order: Long = DECK_DEFAULT_CARD_ORDER,
    val descriptionMarkdown: String = "",
    val dueAt: String? = null,
    val startAt: String? = null,
    val color: String? = null,
) {
    internal val normalizedTitle = title.requireDeckText("Card title", DECK_CARD_TITLE_LIMIT)
    internal val normalizedDescription = descriptionMarkdown.requireDeckMarkdown()
    internal val normalizedDueAt = dueAt.requireDeckInstant("Card due date")
    internal val normalizedStartAt = startAt.requireDeckInstant("Card start date")
    internal val normalizedColor = color.requireDeckOptionalColor()

    init {
        require(order >= 0) { "The card order is invalid." }
    }
}

/**
 * Deck's update endpoint expects the complete editable card representation, not a partial patch.
 * The owner and current order therefore remain explicit typed state rather than user-entered IDs.
 */
data class DeckCardUpdate(
    val original: DeckCard,
    val title: String,
    val order: Long,
    val descriptionMarkdown: String,
    val dueAt: String?,
    val startAt: String?,
    val archived: Boolean,
    val completedAt: String?,
) {
    internal val normalizedTitle = title.requireDeckText("Card title", DECK_CARD_TITLE_LIMIT)
    internal val normalizedOwnerId = original.ownerId?.requireDeckParticipantId()
        ?: error("The card owner is unavailable.")
    internal val normalizedDescription = descriptionMarkdown.requireDeckMarkdown()
    internal val normalizedDueAt = dueAt.requireDeckInstant("Card due date")
    internal val normalizedStartAt = startAt.requireDeckInstant("Card start date")
    internal val normalizedCompletedAt = completedAt.requireDeckInstant("Card completion date")
    internal val normalizedColor = original.color.requireDeckOptionalColor()

    init {
        require(order >= 0) { "The card order is invalid." }
    }
}

data class DeckCardMove(
    val source: DeckCardContext,
    val destinationStack: DeckStackContext,
    val order: Long,
) {
    init {
        require(source.stack.boardId == destinationStack.boardId) {
            "A card cannot be moved to a stack from another board."
        }
        require(order >= 0) { "The card order is invalid." }
    }
}

data class DeckCommentDraft(
    val message: String,
    val parentId: Long? = null,
) {
    internal val normalizedMessage = message.requireDeckComment()

    init {
        require(parentId == null || parentId > 0L) { "The parent comment id is invalid." }
    }
}

data class DeckCommentUpdate(
    val message: String,
) {
    internal val normalizedMessage = message.requireDeckComment()
}

data class DeckComment(
    val id: Long,
    val cardId: Long,
    val message: String,
    val authorId: String?,
    val authorDisplayName: String,
    val createdAt: String?,
    val mentions: List<DeckCommentMention>,
    val replyToId: Long?,
)

data class DeckCommentMention(
    val id: String,
    val type: String,
    val displayName: String,
)

/** The attachment kind exactly as the server reported it; used only to address the attachment. */
@JvmInline
value class DeckAttachmentType(val serverValue: String) {
    init {
        require(serverValue.length in 1..64 && serverValue.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
            "The attachment type is invalid."
        }
    }
}

data class DeckAttachment(
    val id: Long,
    val cardId: Long,
    val type: DeckAttachmentType,
    val name: String,
    val mimeType: String?,
    val byteCount: Long?,
    val createdBy: String?,
    val createdAt: Long?,
    val lastModified: Long?,
)

/**
 * Opening an arbitrary attachment must stream through a platform service into a temporary file.
 * The path comes from the app contract's attachment read, validated by the shared request builder.
 */
data class DeckAttachmentOpenTarget(
    val method: NextcloudApiMethod,
    val relativePath: String,
) {
    init {
        require(method == NextcloudApiMethod.GET) { "Attachments require a read request." }
        NextcloudApiRequest(method, relativePath).requireSafe()
    }
}

data class DeckMutationReceipt(
    val returnedId: Long?,
    val etag: String?,
    val requiresAuthoritativeRefresh: Boolean = true,
)

class DeckBoardAccess private constructor(
    val boardId: DeckBoardId,
    private val permissions: DeckPermissions,
    private val archived: Boolean,
    private val labelIds: Set<Long>,
    private val userIds: Set<String>,
) {
    companion object {
        fun from(board: DeckBoard): DeckBoardAccess =
            DeckBoardAccess(
                boardId = DeckBoardId(board.id),
                permissions = board.permissions,
                archived = board.archived,
                labelIds = board.labels.mapTo(mutableSetOf(), DeckLabel::id),
                userIds = buildSet {
                    board.owner?.id?.let(::add)
                    board.users.mapTo(this, DeckUser::id)
                },
            )
    }

    internal fun requireRead(expectedBoardId: DeckBoardId) {
        require(boardId == expectedBoardId) { "Board access belongs to another board." }
        require(permissions.canRead) { "Read permission is required." }
    }

    internal fun requireEdit(expectedBoardId: DeckBoardId) {
        requireRead(expectedBoardId)
        require(!archived) { "Archived boards are read-only." }
        require(permissions.canEdit) { "Edit permission is required." }
    }

    internal fun requireCommentWrite(expectedBoardId: DeckBoardId) {
        requireRead(expectedBoardId)
        require(!archived) { "Archived boards are read-only." }
    }

    internal fun requireManage(expectedBoardId: DeckBoardId) {
        requireRead(expectedBoardId)
        require(permissions.canManage) { "Manage permission is required." }
    }

    internal fun requireActiveManage(expectedBoardId: DeckBoardId) {
        requireManage(expectedBoardId)
        require(!archived) { "Archived boards are read-only." }
    }

    internal fun requireLabel(labelId: Long): Long {
        val normalized = labelId.requireDeckRelationshipId()
        require(normalized in labelIds) { "The label does not belong to this board." }
        return normalized
    }

    internal fun requireUser(userId: String): String {
        val normalized = userId.requireDeckParticipantId()
        require(normalized in userIds) { "The user does not belong to this board." }
        return normalized
    }
}

data class DeckCardPosition(
    val cardId: Long,
    val boardId: Long,
    val stackId: Long,
    val order: Long,
)

data class DeckAssignment(
    val id: Long,
    val cardId: Long,
    val user: DeckUser,
)

fun parseDeckMutationReceipt(
    response: NextcloudApiResponse,
    expectedId: Long? = null,
): DeckMutationReceipt {
    require(response.status in 200..299) { "The action failed (HTTP ${response.status})." }
    require(response.body.size <= DECK_MUTATION_RESPONSE_BYTES) {
        "The action returned too much data."
    }
    if (response.body.isEmpty()) {
        return DeckMutationReceipt(returnedId = null, etag = response.etag)
    }
    val root = response.deckActionPayload("Board action")
    val returnedId = (root as? JsonObject)?.long("id")
    if (expectedId != null && returnedId != null) {
        require(returnedId == expectedId) { "The action returned an unexpected resource." }
    }
    return DeckMutationReceipt(returnedId = returnedId, etag = response.etag)
}

fun parseDeckCommentMutationReceipt(
    response: NextcloudApiResponse,
): DeckMutationReceipt {
    require(response.status in 200..299) {
        "The comment action failed (HTTP ${response.status})."
    }
    require(response.body.size <= DECK_MUTATION_RESPONSE_BYTES) {
        "The comment action returned too much data."
    }
    if (response.body.isEmpty()) {
        return DeckMutationReceipt(returnedId = null, etag = response.etag)
    }
    val root = runCatching { deckActionsJson.parseToJsonElement(response.body.decodeToString()) }
        .getOrNull() ?: error("The comment action returned invalid JSON.")
    val ocs = (root as? JsonObject)?.get("ocs") as? JsonObject
    val statusCode = (ocs?.get("meta") as? JsonObject)?.int("statuscode")
    require(statusCode == null || statusCode in 200..299) {
        "The comment action returned an unsuccessful OCS response."
    }
    val data = ocs?.get("data") ?: root
    val returnedId = (data as? JsonObject)?.long("id")
    return DeckMutationReceipt(returnedId = returnedId, etag = response.etag)
}

fun parseDeckCardMove(
    move: DeckCardMove,
    response: NextcloudApiResponse,
): List<DeckCardPosition> {
    require(response.status in 200..299) { "The card move failed (HTTP ${response.status})." }
    val payload = response.deckActionPayload("Card move")
    val values = payload as? JsonArray ?: error("The card move returned an unexpected response shape.")
    val positions = values.map { element ->
        val value = element.requireObject("Card position")
        DeckCardPosition(
            cardId = value.requirePositiveId("id", "Card position"),
            boardId = move.source.stack.boardId.value,
            stackId = value.requirePositiveId("stackId", "Card position"),
            order = value.long("order")?.takeIf { it >= 0L }
                ?: error("Card position has no valid order."),
        )
    }
    val movedCard = positions.firstOrNull { it.cardId == move.source.cardId }
        ?: error("The move response did not contain the moved card.")
    require(movedCard.stackId == move.destinationStack.stackId) {
        "The move response placed the card in an unexpected stack."
    }
    require(movedCard.order == move.order) {
        "The move response placed the card at an unexpected position."
    }
    return positions
}

fun parseDeckAssignment(
    card: DeckCardContext,
    response: NextcloudApiResponse,
): DeckAssignment {
    require(response.status in 200..299) { "The assignment action failed (HTTP ${response.status})." }
    val value = response.deckActionPayload("Assignment")
        .requireObject("Assignment")
    val responseCardId = value.long("cardId") ?: card.cardId
    require(responseCardId == card.cardId) { "A assignment belongs to an unexpected card." }
    val participant = (value["participant"] as? JsonObject)
        ?: (value["user"] as? JsonObject)
        ?: error("Assignment has no participant.")
    return DeckAssignment(
        id = value.requirePositiveId("id", "Assignment"),
        cardId = card.cardId,
        user = participant.toDeckActionUser(),
    )
}

fun parseDeckComments(
    card: DeckCardContext,
    response: NextcloudApiResponse,
): List<DeckComment> {
    require(response.status in 200..299) { "Comments failed to load (HTTP ${response.status})." }
    val payload = response.deckActionPayload("Comments")
    val values = payload as? JsonArray ?: error("Comments returned an unexpected response shape.")
    return values.map { it.requireObject("Comment").toDeckComment(card.cardId) }
}

fun parseDeckComment(
    card: DeckCardContext,
    response: NextcloudApiResponse,
): DeckComment {
    require(response.status in 200..299) { "The comment action failed (HTTP ${response.status})." }
    return response.deckActionPayload("Comment")
        .requireObject("Comment")
        .toDeckComment(card.cardId)
}

fun parseDeckAttachments(
    card: DeckCardContext,
    response: NextcloudApiResponse,
): List<DeckAttachment> {
    require(response.status in 200..299) { "Attachments failed to load (HTTP ${response.status})." }
    val payload = response.deckActionPayload("Attachments")
    val values = payload as? JsonArray ?: error("Attachments returned an unexpected response shape.")
    return values.map { element ->
        val value = element.requireObject("Attachment")
        val responseCardId = value.long("cardId") ?: card.cardId
        require(responseCardId == card.cardId) {
            "A attachment belongs to an unexpected card."
        }
        val extended = value["extendedData"] as? JsonObject
        val info = extended?.get("info") as? JsonObject
        val type = DeckAttachmentType(value.string("type") ?: DEFAULT_ATTACHMENT_TYPE)
        DeckAttachment(
            id = value.requirePositiveId("id", "Attachment"),
            cardId = card.cardId,
            type = type,
            name = info?.string("basename")
                ?: value.string("data")
                ?: error("Attachment has no name."),
            mimeType = extended?.string("mimetype"),
            byteCount = extended?.long("filesize")?.takeIf { it >= 0L },
            createdBy = value.string("createdBy"),
            createdAt = value.long("createdAt"),
            lastModified = value.long("lastModified"),
        )
    }
}

private fun NextcloudApiResponse.deckActionPayload(label: String): JsonElement {
    require(body.size <= maximumDeckActionResponseBytes(label)) { "$label returned too much data." }
    val root = runCatching { deckActionsJson.parseToJsonElement(body.decodeToString()) }
        .getOrNull() ?: error("$label returned invalid JSON.")
    if (root !is JsonObject) return root
    val ocs = root["ocs"] as? JsonObject ?: return root
    val meta = ocs["meta"] as? JsonObject
    val statusCode = meta?.int("statuscode")
    require(statusCode == null || statusCode in 200..299) {
        "$label returned an unsuccessful OCS response."
    }
    return ocs["data"] ?: error("$label returned no OCS data.")
}

private fun maximumDeckActionResponseBytes(label: String): Long =
    if ("attachment" in label.lowercase()) DECK_ATTACHMENT_LIST_RESPONSE_BYTES
    else DECK_MUTATION_RESPONSE_BYTES

private fun JsonObject.toDeckComment(expectedCardId: Long): DeckComment {
    val responseCardId = long("objectId") ?: expectedCardId
    require(responseCardId == expectedCardId) { "A comment belongs to an unexpected card." }
    val reply = this["replyTo"] as? JsonObject
    return DeckComment(
        id = requirePositiveId("id", "Comment"),
        cardId = expectedCardId,
        message = string("message") ?: "",
        authorId = string("actorId"),
        authorDisplayName = string("actorDisplayName") ?: string("actorId") ?: "Unknown",
        createdAt = string("creationDateTime"),
        mentions = array("mentions").mapNotNull { mention ->
            val value = mention as? JsonObject ?: return@mapNotNull null
            val id = value.string("mentionId") ?: return@mapNotNull null
            DeckCommentMention(
                id = id,
                type = value.string("mentionType") ?: "user",
                displayName = value.string("mentionDisplayName") ?: id,
            )
        },
        replyToId = reply?.long("id")?.takeIf { it > 0L },
    )
}

private fun JsonObject.toDeckActionUser(): DeckUser {
    val id = string("uid") ?: string("primaryKey") ?: string("id")
        ?: error("Assignment has no participant id.")
    return DeckUser(
        id = id,
        displayName = string("displayname") ?: string("displayName") ?: id,
        type = int("type"),
    )
}

private fun JsonElement.requireObject(label: String): JsonObject =
    this as? JsonObject ?: error("$label returned an unexpected record.")

private fun JsonObject.requirePositiveId(name: String, label: String): Long =
    long(name)?.takeIf { it > 0L } ?: error("$label has no valid $name.")

private fun JsonObject.string(name: String): String? =
    this[name]?.let { value ->
        if (value is JsonNull) null else value.jsonPrimitive.contentOrNull
    }?.takeIf(String::isNotBlank)

private fun JsonObject.long(name: String): Long? = this[name]?.let { value ->
    if (value is JsonNull) null else value.jsonPrimitive.longOrNull
}

private fun JsonObject.int(name: String): Int? = this[name]?.let { value ->
    if (value is JsonNull) null else value.jsonPrimitive.intOrNull
}

private fun JsonObject.array(name: String): JsonArray =
    this[name] as? JsonArray ?: JsonArray(emptyList())

private fun String.requireDeckText(label: String, maximumLength: Int): String {
    val normalized = trim()
    require(normalized.isNotEmpty()) { "$label is required." }
    require(normalized.length <= maximumLength) { "$label is too long." }
    require(normalized.none(Char::isISOControl)) { "$label contains invalid control characters." }
    return normalized
}

private fun String.requireDeckColor(): String {
    val normalized = trim().removePrefix("#")
    require(normalized.length == 6 && normalized.all(Char::isDeckHexDigit)) {
        "The color must contain exactly six hexadecimal characters."
    }
    return normalized.lowercase()
}

private fun String?.requireDeckOptionalColor(): String? =
    this?.trim()?.takeIf(String::isNotEmpty)?.let { color ->
        val normalized = color.removePrefix("#")
        require(
            normalized.length in setOf(3, 6, 8) &&
                normalized.all(Char::isDeckHexDigit),
        ) {
            "The card color must contain three, six, or eight hexadecimal characters."
        }
        normalized.lowercase()
    }

private fun Char.isDeckHexDigit(): Boolean =
    this in '0'..'9' || lowercaseChar() in 'a'..'f'

private fun String.requireDeckMarkdown(): String {
    require(length <= DECK_CARD_DESCRIPTION_LIMIT) { "The card description is too long." }
    require(none { it == '\u0000' }) { "The card description contains invalid characters." }
    return this
}

private fun String?.requireDeckInstant(label: String): String? {
    val normalized = this?.trim()?.takeIf(String::isNotEmpty) ?: return null
    if (normalized == COMPLETED_WITHOUT_TIMESTAMP) return normalized
    require(normalized.length <= DECK_DATE_LIMIT) { "$label is too long." }
    return runCatching { Instant.parse(normalized).toString() }
        .getOrElse { throw IllegalArgumentException("$label must be an ISO-8601 timestamp.") }
}

private fun String.requireDeckParticipantId(): String {
    val normalized = trim()
    require(normalized.length in 1..DECK_PARTICIPANT_ID_LIMIT) {
        "The participant id is invalid."
    }
    require(normalized.none(Char::isISOControl)) { "The participant id is invalid." }
    return normalized
}

private fun String.requireDeckComment(): String {
    val normalized = trim()
    require(normalized.isNotEmpty()) { "A comment cannot be empty." }
    require(normalized.length <= DECK_COMMENT_LIMIT) { "The comment is too long." }
    require(normalized.none { it == '\u0000' }) { "The comment contains invalid characters." }
    return normalized
}

private fun Long.requireDeckRelationshipId(): Long {
    require(this > 0L) { "The relationship id is invalid." }
    return this
}

private val deckActionsJson = Json {
    ignoreUnknownKeys = true
    isLenient = false
}

private const val DECK_BOARD_TITLE_LIMIT = 100
private const val DECK_STACK_TITLE_LIMIT = 100
private const val DECK_LABEL_TITLE_LIMIT = 100
private const val DECK_CARD_TITLE_LIMIT = 255
private const val DECK_CARD_DESCRIPTION_LIMIT = 64 * 1024
private const val DECK_COMMENT_LIMIT = 1_000
private const val DECK_PARTICIPANT_ID_LIMIT = 255
private const val DECK_DATE_LIMIT = 64
private const val DECK_DEFAULT_CARD_ORDER = 999L
private const val DEFAULT_ATTACHMENT_TYPE = "file"
private const val DECK_MUTATION_RESPONSE_BYTES = 2L * 1024L * 1024L
private const val DECK_ATTACHMENT_LIST_RESPONSE_BYTES = 4L * 1024L * 1024L
