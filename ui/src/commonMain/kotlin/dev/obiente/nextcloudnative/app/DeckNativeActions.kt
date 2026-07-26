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
        require(value > 0L) { "The Deck board id is invalid." }
    }
}

data class DeckStackContext(
    val boardId: DeckBoardId,
    val stackId: Long,
) {
    init {
        require(stackId > 0L) { "The Deck stack id is invalid." }
    }
}

data class DeckCardContext(
    val stack: DeckStackContext,
    val cardId: Long,
) {
    init {
        require(cardId > 0L) { "The Deck card id is invalid." }
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
        require(order >= 0) { "The Deck stack order is invalid." }
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
        require(order >= 0) { "The Deck card order is invalid." }
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
        ?: error("The Deck card owner is unavailable.")
    internal val normalizedDescription = descriptionMarkdown.requireDeckMarkdown()
    internal val normalizedDueAt = dueAt.requireDeckInstant("Card due date")
    internal val normalizedStartAt = startAt.requireDeckInstant("Card start date")
    internal val normalizedCompletedAt = completedAt.requireDeckInstant("Card completion date")
    internal val normalizedColor = original.color.requireDeckOptionalColor()

    init {
        require(order >= 0) { "The Deck card order is invalid." }
    }
}

data class DeckCardMove(
    val source: DeckCardContext,
    val destinationStack: DeckStackContext,
    val order: Long,
) {
    init {
        require(source.stack.boardId == destinationStack.boardId) {
            "A Deck card cannot be moved to a stack from another board."
        }
        require(order >= 0) { "The Deck card order is invalid." }
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

enum class DeckAttachmentType(val serverValue: String) {
    File("file"),
    DeckFile("deck_file"),
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

data class DeckAttachmentReference(
    val card: DeckCardContext,
    val type: DeckAttachmentType,
    val attachmentId: Long,
) {
    init {
        require(attachmentId > 0L) { "The Deck attachment id is invalid." }
    }
}

/**
 * The current shared API transport buffers request bodies and cannot safely represent a streaming
 * RFC 7578 upload. Platform code must obtain a file through its native picker and stream it to this
 * authenticated same-origin target. This model deliberately does not expose a fake ByteArray API.
 */
data class DeckAttachmentUploadTarget(
    val method: NextcloudApiMethod,
    val relativePath: String,
    val attachmentType: DeckAttachmentType,
    val typeFieldName: String = "type",
    val fileFieldName: String = "file",
    val requiresRfc7578Multipart: Boolean = true,
)

/**
 * Opening an arbitrary attachment must stream through a platform service into a temporary file.
 * The shared ByteArray API is intentionally limited to bounded previews.
 */
data class DeckAttachmentOpenTarget(
    val method: NextcloudApiMethod,
    val relativePath: String,
) {
    init {
        require(method == NextcloudApiMethod.GET) { "Deck attachments require a read request." }
        require(relativePath.isDeckAttachmentOpenPath()) { "The Deck attachment path is invalid." }
    }
}

private fun String.isDeckAttachmentOpenPath(): Boolean {
    val segments = split('/').filter(String::isNotEmpty)
    if (segments.size !in 13..14) return false
    if (segments.take(4) != listOf("index.php", "apps", "deck", "api")) return false
    if (segments[4] !in setOf("v1.0", "v1.1")) return false
    if (
        segments[5] != "boards" ||
        segments[7] != "stacks" ||
        segments[9] != "cards" ||
        segments[11] != "attachments"
    ) {
        return false
    }
    if (!listOf(segments[6], segments[8], segments[10]).all(String::isPositiveDeckPathId)) return false
    return when (segments[4]) {
        "v1.0" -> segments.size == 13 && segments[12].isPositiveDeckPathId()
        "v1.1" -> segments.size == 14 &&
            segments[12] in DeckAttachmentType.entries.map(DeckAttachmentType::serverValue) &&
            segments[13].isPositiveDeckPathId()
        else -> false
    }
}

private fun String.isPositiveDeckPathId(): Boolean =
    isNotEmpty() && all(Char::isDigit) && toLongOrNull()?.let { it > 0L } == true

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
        require(boardId == expectedBoardId) { "Deck access belongs to another board." }
        require(permissions.canRead) { "Deck read permission is required." }
    }

    internal fun requireEdit(expectedBoardId: DeckBoardId) {
        requireRead(expectedBoardId)
        require(!archived) { "Archived Deck boards are read-only." }
        require(permissions.canEdit) { "Deck edit permission is required." }
    }

    internal fun requireManage(expectedBoardId: DeckBoardId) {
        requireRead(expectedBoardId)
        require(permissions.canManage) { "Deck manage permission is required." }
    }

    internal fun requireActiveManage(expectedBoardId: DeckBoardId) {
        requireManage(expectedBoardId)
        require(!archived) { "Archived Deck boards are read-only." }
    }

    internal fun requireLabel(labelId: Long): Long {
        val normalized = labelId.requireDeckRelationshipId()
        require(normalized in labelIds) { "The Deck label does not belong to this board." }
        return normalized
    }

    internal fun requireUser(userId: String): String {
        val normalized = userId.requireDeckParticipantId()
        require(normalized in userIds) { "The Deck user does not belong to this board." }
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

data class DeckWriteRoutePlan(
    val version: DeckApiVersion,
    val apiRoot: String = "/index.php/apps/deck/api/v${version.value}",
    val ocsApiRoot: String = "/ocs/v2.php/apps/deck/api/v${version.value}",
) {
    fun createBoard(
        capabilities: DeckCapabilities,
        draft: DeckBoardDraft,
    ): NextcloudApiRequest {
        require(capabilities.canCreateBoards) { "Deck board creation is not permitted." }
        return deckJsonRequest(
            method = NextcloudApiMethod.POST,
            path = "$apiRoot/boards",
            body = buildJsonObject {
                put("title", draft.normalizedTitle)
                put("color", draft.normalizedColor)
            },
        )
    }

    fun updateBoard(
        access: DeckBoardAccess,
        update: DeckBoardUpdate,
    ): NextcloudApiRequest {
        access.requireManage(access.boardId)
        return deckJsonRequest(
            method = NextcloudApiMethod.PUT,
            path = "$apiRoot/boards/${access.boardId.value}",
            body = buildJsonObject {
                put("title", update.normalizedTitle)
                put("color", update.normalizedColor)
                put("archived", update.archived)
            },
        )
    }

    fun deleteBoard(access: DeckBoardAccess): NextcloudApiRequest {
        access.requireManage(access.boardId)
        return deckRequest(
            method = NextcloudApiMethod.DELETE,
            path = "$apiRoot/boards/${access.boardId.value}",
        )
    }

    fun restoreBoard(access: DeckBoardAccess): NextcloudApiRequest {
        access.requireManage(access.boardId)
        return deckRequest(
            method = NextcloudApiMethod.POST,
            path = "$apiRoot/boards/${access.boardId.value}/undo_delete",
        )
    }

    fun createStack(
        access: DeckBoardAccess,
        draft: DeckStackDraft,
    ): NextcloudApiRequest {
        access.requireActiveManage(access.boardId)
        return deckJsonRequest(
            method = NextcloudApiMethod.POST,
            path = "$apiRoot/boards/${access.boardId.value}/stacks",
            body = buildJsonObject {
                put("title", draft.normalizedTitle)
                put("order", draft.order)
            },
        )
    }

    fun updateStack(
        access: DeckBoardAccess,
        context: DeckStackContext,
        update: DeckStackDraft,
    ): NextcloudApiRequest {
        access.requireActiveManage(context.boardId)
        return deckJsonRequest(
            method = NextcloudApiMethod.PUT,
            path = context.stackPath(),
            body = buildJsonObject {
                put("title", update.normalizedTitle)
                put("order", update.order)
            },
        )
    }

    fun deleteStack(
        access: DeckBoardAccess,
        context: DeckStackContext,
    ): NextcloudApiRequest {
        access.requireActiveManage(context.boardId)
        return deckRequest(
            method = NextcloudApiMethod.DELETE,
            path = context.stackPath(),
        )
    }

    fun createCard(
        access: DeckBoardAccess,
        context: DeckStackContext,
        draft: DeckCardDraft,
    ): NextcloudApiRequest {
        access.requireEdit(context.boardId)
        return deckJsonRequest(
            method = NextcloudApiMethod.POST,
            path = "${context.stackPath()}/cards",
            body = buildJsonObject {
                put("title", draft.normalizedTitle)
                put("type", DECK_PLAIN_CARD_TYPE)
                put("order", draft.order)
                put("description", draft.normalizedDescription)
                putNullableString("duedate", draft.normalizedDueAt)
                putNullableString("startdate", draft.normalizedStartAt)
                putNullableString("color", draft.normalizedColor)
            },
        )
    }

    fun updateCard(
        access: DeckBoardAccess,
        context: DeckCardContext,
        update: DeckCardUpdate,
    ): NextcloudApiRequest {
        access.requireEdit(context.stack.boardId)
        require(update.original.boardId == context.stack.boardId.value) {
            "The Deck card update belongs to another board."
        }
        require(update.original.stackId == context.stack.stackId) {
            "The Deck card update belongs to another stack."
        }
        require(update.original.id == context.cardId) {
            "The Deck card update belongs to another card."
        }
        return deckJsonRequest(
            method = NextcloudApiMethod.PUT,
            path = context.cardPath(),
            body = buildJsonObject {
                put("title", update.normalizedTitle)
                put("type", DECK_PLAIN_CARD_TYPE)
                put("owner", update.normalizedOwnerId)
                put("order", update.order)
                put("description", update.normalizedDescription)
                putNullableString("duedate", update.normalizedDueAt)
                putNullableString("startdate", update.normalizedStartAt)
                put("archived", update.archived)
                putNullableString("done", update.normalizedCompletedAt)
                putNullableString("color", update.normalizedColor)
            },
        )
    }

    fun deleteCard(
        access: DeckBoardAccess,
        context: DeckCardContext,
    ): NextcloudApiRequest {
        access.requireEdit(context.stack.boardId)
        return deckRequest(
            method = NextcloudApiMethod.DELETE,
            path = context.cardPath(),
        )
    }

    fun archiveCard(
        access: DeckBoardAccess,
        context: DeckCardContext,
    ): NextcloudApiRequest {
        access.requireEdit(context.stack.boardId)
        return deckRequest(
            method = NextcloudApiMethod.PUT,
            path = "${context.cardPath()}/archive",
        )
    }

    fun unarchiveCard(
        access: DeckBoardAccess,
        context: DeckCardContext,
    ): NextcloudApiRequest {
        access.requireEdit(context.stack.boardId)
        return deckRequest(
            method = NextcloudApiMethod.PUT,
            path = "${context.cardPath()}/unarchive",
        )
    }

    fun moveCard(
        access: DeckBoardAccess,
        move: DeckCardMove,
    ): NextcloudApiRequest {
        access.requireEdit(move.source.stack.boardId)
        return deckJsonRequest(
            method = NextcloudApiMethod.PUT,
            path = "${move.source.cardPath()}/reorder",
            body = buildJsonObject {
                put("stackId", move.destinationStack.stackId)
                put("order", move.order)
            },
        )
    }

    fun assignLabel(
        access: DeckBoardAccess,
        context: DeckCardContext,
        labelId: Long,
    ): NextcloudApiRequest {
        access.requireEdit(context.stack.boardId)
        return cardRelationshipRequest(
            context,
            "assignLabel",
            "labelId",
            access.requireLabel(labelId),
        )
    }

    fun removeLabel(
        access: DeckBoardAccess,
        context: DeckCardContext,
        labelId: Long,
    ): NextcloudApiRequest {
        access.requireEdit(context.stack.boardId)
        return cardRelationshipRequest(
            context,
            "removeLabel",
            "labelId",
            access.requireLabel(labelId),
        )
    }

    fun assignUser(
        access: DeckBoardAccess,
        context: DeckCardContext,
        userId: String,
    ): NextcloudApiRequest {
        access.requireEdit(context.stack.boardId)
        return cardRelationshipRequest(
            context,
            "assignUser",
            "userId",
            access.requireUser(userId),
        )
    }

    fun unassignUser(
        access: DeckBoardAccess,
        context: DeckCardContext,
        userId: String,
    ): NextcloudApiRequest {
        access.requireEdit(context.stack.boardId)
        return cardRelationshipRequest(
            context,
            "unassignUser",
            "userId",
            access.requireUser(userId),
        )
    }

    fun createLabel(
        access: DeckBoardAccess,
        draft: DeckLabelDraft,
    ): NextcloudApiRequest {
        access.requireActiveManage(access.boardId)
        return deckJsonRequest(
            method = NextcloudApiMethod.POST,
            path = "$apiRoot/boards/${access.boardId.value}/labels",
            body = buildJsonObject {
                put("title", draft.normalizedTitle)
                put("color", draft.normalizedColor)
            },
        )
    }

    fun updateLabel(
        access: DeckBoardAccess,
        labelId: Long,
        draft: DeckLabelDraft,
    ): NextcloudApiRequest {
        access.requireActiveManage(access.boardId)
        return deckJsonRequest(
            method = NextcloudApiMethod.PUT,
            path = "$apiRoot/boards/${access.boardId.value}/labels/" +
                labelId.requireDeckRelationshipId(),
            body = buildJsonObject {
                put("title", draft.normalizedTitle)
                put("color", draft.normalizedColor)
            },
        )
    }

    fun deleteLabel(
        access: DeckBoardAccess,
        labelId: Long,
    ): NextcloudApiRequest {
        access.requireActiveManage(access.boardId)
        return deckRequest(
            method = NextcloudApiMethod.DELETE,
            path = "$apiRoot/boards/${access.boardId.value}/labels/" +
                labelId.requireDeckRelationshipId(),
        )
    }

    fun comments(
        access: DeckBoardAccess,
        context: DeckCardContext,
        limit: Int = DECK_DEFAULT_COMMENT_PAGE_SIZE,
        offset: Int = 0,
    ): NextcloudApiRequest {
        access.requireRead(context.stack.boardId)
        require(limit in 1..DECK_MAX_COMMENT_PAGE_SIZE) { "The Deck comment page size is invalid." }
        require(offset >= 0) { "The Deck comment offset is invalid." }
        return deckRequest(
            method = NextcloudApiMethod.GET,
            path = "${context.commentsPath()}",
            query = mapOf("limit" to limit.toString(), "offset" to offset.toString()),
        )
    }

    fun createComment(
        access: DeckBoardAccess,
        context: DeckCardContext,
        draft: DeckCommentDraft,
    ): NextcloudApiRequest {
        access.requireRead(context.stack.boardId)
        return deckJsonRequest(
            method = NextcloudApiMethod.POST,
            path = context.commentsPath(),
            body = buildJsonObject {
                put("message", draft.normalizedMessage)
                draft.parentId?.let { put("parentId", it) } ?: put("parentId", JsonNull)
            },
        )
    }

    fun updateComment(
        access: DeckBoardAccess,
        context: DeckCardContext,
        comment: DeckComment,
        currentUserId: String,
        update: DeckCommentUpdate,
    ): NextcloudApiRequest {
        access.requireRead(context.stack.boardId)
        require(comment.cardId == context.cardId) { "The Deck comment belongs to another card." }
        require(comment.authorId == currentUserId.requireDeckParticipantId()) {
            "Only the Deck comment author can update this comment."
        }
        return deckJsonRequest(
            method = NextcloudApiMethod.PUT,
            path = "${context.commentsPath()}/${comment.id.requireDeckRelationshipId()}",
            body = buildJsonObject {
                put("message", update.normalizedMessage)
            },
        )
    }

    fun deleteComment(
        access: DeckBoardAccess,
        context: DeckCardContext,
        comment: DeckComment,
        currentUserId: String,
    ): NextcloudApiRequest {
        access.requireRead(context.stack.boardId)
        require(comment.cardId == context.cardId) { "The Deck comment belongs to another card." }
        require(comment.authorId == currentUserId.requireDeckParticipantId()) {
            "Only the Deck comment author can delete this comment."
        }
        return deckRequest(
            method = NextcloudApiMethod.DELETE,
            path = "${context.commentsPath()}/${comment.id.requireDeckRelationshipId()}",
        )
    }

    fun attachments(
        access: DeckBoardAccess,
        context: DeckCardContext,
    ): NextcloudApiRequest {
        access.requireRead(context.stack.boardId)
        return deckRequest(
            method = NextcloudApiMethod.GET,
            path = "${context.cardPath()}/attachments",
        )
    }

    fun openAttachment(
        access: DeckBoardAccess,
        reference: DeckAttachmentReference,
    ): DeckAttachmentOpenTarget {
        access.requireRead(reference.card.stack.boardId)
        return DeckAttachmentOpenTarget(
            method = NextcloudApiMethod.GET,
            relativePath = reference.attachmentPath(),
        )
    }

    fun previewAttachment(
        access: DeckBoardAccess,
        reference: DeckAttachmentReference,
    ): NextcloudApiRequest {
        access.requireRead(reference.card.stack.boardId)
        return deckRequest(
            method = NextcloudApiMethod.GET,
            path = reference.attachmentPath(),
            maximumResponseBytes = DECK_ATTACHMENT_OPEN_RESPONSE_BYTES,
        )
    }

    fun deleteAttachment(
        access: DeckBoardAccess,
        reference: DeckAttachmentReference,
    ): NextcloudApiRequest {
        access.requireEdit(reference.card.stack.boardId)
        return deckRequest(
            method = NextcloudApiMethod.DELETE,
            path = reference.attachmentPath(),
        )
    }

    fun restoreAttachment(
        access: DeckBoardAccess,
        reference: DeckAttachmentReference,
    ): NextcloudApiRequest {
        access.requireEdit(reference.card.stack.boardId)
        return deckRequest(
            method = NextcloudApiMethod.PUT,
            path = "${reference.attachmentPath()}/restore",
        )
    }

    fun attachmentUploadTarget(
        access: DeckBoardAccess,
        context: DeckCardContext,
        type: DeckAttachmentType = DeckAttachmentType.File,
    ): DeckAttachmentUploadTarget {
        access.requireEdit(context.stack.boardId)
        require(type != DeckAttachmentType.File || version.value == "1.1") {
            "Regular Nextcloud file attachments require Deck API 1.1."
        }
        return DeckAttachmentUploadTarget(
            method = NextcloudApiMethod.POST,
            relativePath = "${context.cardPath()}/attachments",
            attachmentType = type,
        )
    }

    private fun cardRelationshipRequest(
        context: DeckCardContext,
        operation: String,
        key: String,
        value: Long,
    ): NextcloudApiRequest = deckJsonRequest(
        method = NextcloudApiMethod.PUT,
        path = "${context.cardPath()}/$operation",
        body = buildJsonObject { put(key, value) },
    )

    private fun cardRelationshipRequest(
        context: DeckCardContext,
        operation: String,
        key: String,
        value: String,
    ): NextcloudApiRequest = deckJsonRequest(
        method = NextcloudApiMethod.PUT,
        path = "${context.cardPath()}/$operation",
        body = buildJsonObject { put(key, value) },
    )

    private fun DeckStackContext.stackPath(): String =
        "$apiRoot/boards/${boardId.value}/stacks/$stackId"

    private fun DeckCardContext.cardPath(): String =
        "${stack.stackPath()}/cards/$cardId"

    private fun DeckCardContext.commentsPath(): String = "$ocsApiRoot/cards/$cardId/comments"

    private fun DeckAttachmentReference.attachmentPath(): String {
        val base = "${card.cardPath()}/attachments"
        return if (version.value == "1.1") {
            "$base/${type.serverValue}/$attachmentId"
        } else {
            require(type == DeckAttachmentType.DeckFile) {
                "Deck API 1.0 only supports legacy deck_file attachments."
            }
            "$base/$attachmentId"
        }
    }
}

fun parseDeckMutationReceipt(
    response: NextcloudApiResponse,
    expectedId: Long? = null,
): DeckMutationReceipt {
    require(response.status in 200..299) { "The Deck action failed (HTTP ${response.status})." }
    require(response.body.size <= DECK_MUTATION_RESPONSE_BYTES) {
        "The Deck action returned too much data."
    }
    if (response.body.isEmpty()) {
        return DeckMutationReceipt(returnedId = null, etag = response.etag)
    }
    val root = response.deckActionPayload("Deck action")
    val returnedId = (root as? JsonObject)?.long("id")
    if (expectedId != null && returnedId != null) {
        require(returnedId == expectedId) { "The Deck action returned an unexpected resource." }
    }
    return DeckMutationReceipt(returnedId = returnedId, etag = response.etag)
}

fun parseDeckCommentMutationReceipt(
    response: NextcloudApiResponse,
): DeckMutationReceipt {
    require(response.status in 200..299) {
        "The Deck comment action failed (HTTP ${response.status})."
    }
    require(response.body.size <= DECK_MUTATION_RESPONSE_BYTES) {
        "The Deck comment action returned too much data."
    }
    if (response.body.isEmpty()) {
        return DeckMutationReceipt(returnedId = null, etag = response.etag)
    }
    val root = runCatching { deckActionsJson.parseToJsonElement(response.body.decodeToString()) }
        .getOrNull() ?: error("The Deck comment action returned invalid JSON.")
    val ocs = (root as? JsonObject)?.get("ocs") as? JsonObject
    val statusCode = (ocs?.get("meta") as? JsonObject)?.int("statuscode")
    require(statusCode == null || statusCode in 200..299) {
        "The Deck comment action returned an unsuccessful OCS response."
    }
    val data = ocs?.get("data") ?: root
    val returnedId = (data as? JsonObject)?.long("id")
    return DeckMutationReceipt(returnedId = returnedId, etag = response.etag)
}

fun parseDeckCardMove(
    move: DeckCardMove,
    response: NextcloudApiResponse,
): List<DeckCardPosition> {
    require(response.status in 200..299) { "The Deck card move failed (HTTP ${response.status})." }
    val payload = response.deckActionPayload("Deck card move")
    val values = payload as? JsonArray ?: error("The Deck card move returned an unexpected response shape.")
    val positions = values.map { element ->
        val value = element.requireObject("Deck card position")
        DeckCardPosition(
            cardId = value.requirePositiveId("id", "Deck card position"),
            boardId = move.source.stack.boardId.value,
            stackId = value.requirePositiveId("stackId", "Deck card position"),
            order = value.long("order")?.takeIf { it >= 0L }
                ?: error("Deck card position has no valid order."),
        )
    }
    val movedCard = positions.firstOrNull { it.cardId == move.source.cardId }
        ?: error("The Deck move response did not contain the moved card.")
    require(movedCard.stackId == move.destinationStack.stackId) {
        "The Deck move response placed the card in an unexpected stack."
    }
    return positions
}

fun parseDeckAssignment(
    card: DeckCardContext,
    response: NextcloudApiResponse,
): DeckAssignment {
    require(response.status in 200..299) { "The Deck assignment action failed (HTTP ${response.status})." }
    val value = response.deckActionPayload("Deck assignment")
        .requireObject("Deck assignment")
    val responseCardId = value.long("cardId") ?: card.cardId
    require(responseCardId == card.cardId) { "A Deck assignment belongs to an unexpected card." }
    val participant = (value["participant"] as? JsonObject)
        ?: (value["user"] as? JsonObject)
        ?: error("Deck assignment has no participant.")
    return DeckAssignment(
        id = value.requirePositiveId("id", "Deck assignment"),
        cardId = card.cardId,
        user = participant.toDeckActionUser(),
    )
}

fun parseDeckComments(
    card: DeckCardContext,
    response: NextcloudApiResponse,
): List<DeckComment> {
    require(response.status in 200..299) { "Deck comments failed to load (HTTP ${response.status})." }
    val payload = response.deckActionPayload("Deck comments")
    val values = payload as? JsonArray ?: error("Deck comments returned an unexpected response shape.")
    return values.map { it.requireObject("Deck comment").toDeckComment(card.cardId) }
}

fun parseDeckComment(
    card: DeckCardContext,
    response: NextcloudApiResponse,
): DeckComment {
    require(response.status in 200..299) { "The Deck comment action failed (HTTP ${response.status})." }
    return response.deckActionPayload("Deck comment")
        .requireObject("Deck comment")
        .toDeckComment(card.cardId)
}

fun parseDeckAttachments(
    card: DeckCardContext,
    response: NextcloudApiResponse,
): List<DeckAttachment> {
    require(response.status in 200..299) { "Deck attachments failed to load (HTTP ${response.status})." }
    val payload = response.deckActionPayload("Deck attachments")
    val values = payload as? JsonArray ?: error("Deck attachments returned an unexpected response shape.")
    return values.map { element ->
        val value = element.requireObject("Deck attachment")
        val responseCardId = value.long("cardId") ?: card.cardId
        require(responseCardId == card.cardId) {
            "A Deck attachment belongs to an unexpected card."
        }
        val extended = value["extendedData"] as? JsonObject
        val info = extended?.get("info") as? JsonObject
        val type = when (value.string("type")) {
            DeckAttachmentType.File.serverValue -> DeckAttachmentType.File
            DeckAttachmentType.DeckFile.serverValue -> DeckAttachmentType.DeckFile
            else -> error("Deck returned an unsupported attachment type.")
        }
        DeckAttachment(
            id = value.requirePositiveId("id", "Deck attachment"),
            cardId = card.cardId,
            type = type,
            name = info?.string("basename")
                ?: value.string("data")
                ?: error("Deck attachment has no name."),
            mimeType = extended?.string("mimetype"),
            byteCount = extended?.long("filesize")?.takeIf { it >= 0L },
            createdBy = value.string("createdBy"),
            createdAt = value.long("createdAt"),
            lastModified = value.long("lastModified"),
        )
    }
}

private fun deckJsonRequest(
    method: NextcloudApiMethod,
    path: String,
    body: JsonObject,
): NextcloudApiRequest {
    val encoded = body.toString().encodeToByteArray()
    require(encoded.size <= DECK_REQUEST_BODY_BYTES) { "The Deck request is too large." }
    return deckRequest(
        method = method,
        path = path,
        contentType = "application/json",
        body = encoded,
    )
}

private fun deckRequest(
    method: NextcloudApiMethod,
    path: String,
    query: Map<String, String> = emptyMap(),
    contentType: String? = null,
    body: ByteArray? = null,
    maximumResponseBytes: Long = DECK_MUTATION_RESPONSE_BYTES,
): NextcloudApiRequest = NextcloudApiRequest(
    method = method,
    relativePath = path,
    queryParameters = query,
    contentType = contentType,
    body = body,
    ocsApiRequest = true,
    maximumResponseBytes = maximumResponseBytes,
)

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
    require(responseCardId == expectedCardId) { "A Deck comment belongs to an unexpected card." }
    val reply = this["replyTo"] as? JsonObject
    return DeckComment(
        id = requirePositiveId("id", "Deck comment"),
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
        ?: error("Deck assignment has no participant id.")
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
        "The Deck color must contain exactly six hexadecimal characters."
    }
    return normalized.lowercase()
}

private fun String?.requireDeckOptionalColor(): String? =
    this?.trim()?.takeIf(String::isNotEmpty)?.requireDeckColor()

private fun Char.isDeckHexDigit(): Boolean =
    this in '0'..'9' || lowercaseChar() in 'a'..'f'

private fun String.requireDeckMarkdown(): String {
    require(length <= DECK_CARD_DESCRIPTION_LIMIT) { "The Deck card description is too long." }
    require(none { it == '\u0000' }) { "The Deck card description contains invalid characters." }
    return this
}

private fun String?.requireDeckInstant(label: String): String? {
    val normalized = this?.trim()?.takeIf(String::isNotEmpty) ?: return null
    require(normalized.length <= DECK_DATE_LIMIT) { "$label is too long." }
    return runCatching { Instant.parse(normalized).toString() }
        .getOrElse { throw IllegalArgumentException("$label must be an ISO-8601 timestamp.") }
}

private fun String.requireDeckParticipantId(): String {
    val normalized = trim()
    require(normalized.length in 1..DECK_PARTICIPANT_ID_LIMIT) {
        "The Deck participant id is invalid."
    }
    require(normalized.none(Char::isISOControl)) { "The Deck participant id is invalid." }
    return normalized
}

private fun String.requireDeckComment(): String {
    val normalized = trim()
    require(normalized.isNotEmpty()) { "A Deck comment cannot be empty." }
    require(normalized.length <= DECK_COMMENT_LIMIT) { "The Deck comment is too long." }
    require(normalized.none { it == '\u0000' }) { "The Deck comment contains invalid characters." }
    return normalized
}

private fun Long.requireDeckRelationshipId(): Long {
    require(this > 0L) { "The Deck relationship id is invalid." }
    return this
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullableString(
    key: String,
    value: String?,
) {
    put(key, value?.let(::JsonPrimitive) ?: JsonNull)
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
private const val DECK_PLAIN_CARD_TYPE = "plain"
private const val DECK_DEFAULT_COMMENT_PAGE_SIZE = 20
private const val DECK_MAX_COMMENT_PAGE_SIZE = 100
private const val DECK_REQUEST_BODY_BYTES = 96 * 1024
private const val DECK_MUTATION_RESPONSE_BYTES = 2L * 1024L * 1024L
private const val DECK_ATTACHMENT_LIST_RESPONSE_BYTES = 4L * 1024L * 1024L
private const val DECK_ATTACHMENT_OPEN_RESPONSE_BYTES = 4L * 1024L * 1024L
