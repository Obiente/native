package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.runtime.BoardEntity
import dev.obiente.nextcloudnative.nativeui.runtime.BoardField
import dev.obiente.nextcloudnative.nativeui.runtime.BoardOperation
import dev.obiente.nextcloudnative.nativeui.runtime.BoardOperationBinding
import dev.obiente.nextcloudnative.nativeui.runtime.BoardValueRole
import dev.obiente.nextcloudnative.nativeui.runtime.SemanticBoardContract
import dev.obiente.nextcloudnative.nativeui.runtime.declaredJsonType
import dev.obiente.nextcloudnative.nativeui.runtime.declaresNull
import dev.obiente.nextcloudnative.nativeui.runtime.semanticRouteId
import kotlinx.serialization.json.JsonObject

/** A typed request input; its wire form follows the type the contract declares for the field. */
internal sealed interface BoardInput {
    data class Text(val value: String?) : BoardInput
    data class Flag(val value: Boolean) : BoardInput
    data class Number(val value: Long) : BoardInput

    /** A completion state: a timestamp for date fields, or a flag for boolean fields. */
    data class Completion(val completedAt: String?) : BoardInput

    /** An optional value the user removed: JSON null when the contract declares the field nullable. */
    data object Clear : BoardInput
}

/**
 * Builds every board request from the operations the installed app's own contract describes.
 *
 * Paths, methods, and body fields come only from [contract]; the shared dynamic request builder
 * validates them against the approved API prefixes. Typed board, list, and card contexts supply
 * identities, so the UI never types or guesses an ID, and an operation the contract does not
 * describe fails before any request is sent.
 */
internal class SemanticBoardRoutes(val contract: SemanticBoardContract) {
    val appId: String get() = contract.descriptor.app.id

    fun supports(operation: BoardOperation): Boolean = contract.supports(operation)

    fun managesLabels(): Boolean = supports(BoardOperation.CreateLabel) || supports(BoardOperation.UpdateLabel) ||
        supports(BoardOperation.DeleteLabel)

    /** Label and assignee pickers toggle both ways, so both directions must be described. */
    fun assignsLabels(): Boolean = supports(BoardOperation.AssignLabel) && supports(BoardOperation.RemoveLabel)

    fun assignsUsers(): Boolean = supports(BoardOperation.AssignUser) && supports(BoardOperation.UnassignUser)

    fun boards(): NextcloudApiRequest = request(BoardOperation.ListBoards, emptyMap(), read = true)

    fun board(boardId: Long, authoritative: Boolean = false): NextcloudApiRequest =
        request(BoardOperation.ReadBoard, ids(BoardEntity.Board to boardId), read = true, authoritative = authoritative)

    fun stacks(boardId: Long): NextcloudApiRequest =
        request(BoardOperation.ListLanes, ids(BoardEntity.Board to boardId), read = true)

    fun archivedStacks(boardId: Long): NextcloudApiRequest =
        request(BoardOperation.ListArchivedLanes, ids(BoardEntity.Board to boardId), read = true, authoritative = true)

    fun cards(boardId: Long, stackId: Long): NextcloudApiRequest = request(
        BoardOperation.ListCards,
        ids(BoardEntity.Board to boardId, BoardEntity.Lane to stackId),
        read = true,
    )

    fun card(card: DeckCard, authoritative: Boolean = false): NextcloudApiRequest = request(
        BoardOperation.ReadCard,
        card.identities(),
        read = true,
        authoritative = authoritative,
    )

    fun createBoard(draft: DeckBoardDraft): NextcloudApiRequest = request(
        BoardOperation.CreateBoard,
        emptyMap(),
        fields = mapOf(BoardField.Title to text(draft.normalizedTitle), BoardField.Color to text(draft.normalizedColor)),
    )

    fun updateBoard(access: DeckBoardAccess, board: DeckBoard, update: DeckBoardUpdate): NextcloudApiRequest {
        access.requireManage(access.boardId)
        return request(
            BoardOperation.UpdateBoard,
            ids(BoardEntity.Board to access.boardId.value),
            fields = mapOf(
                BoardField.Title to text(update.normalizedTitle),
                BoardField.Color to text(update.normalizedColor),
                BoardField.Archived to BoardInput.Flag(update.archived),
            ),
            record = board.rawFields,
        )
    }

    fun deleteBoard(access: DeckBoardAccess): NextcloudApiRequest {
        access.requireManage(access.boardId)
        return request(BoardOperation.DeleteBoard, ids(BoardEntity.Board to access.boardId.value))
    }

    fun restoreBoard(access: DeckBoardAccess): NextcloudApiRequest {
        access.requireManage(access.boardId)
        return request(BoardOperation.RestoreBoard, ids(BoardEntity.Board to access.boardId.value))
    }

    fun createStack(access: DeckBoardAccess, draft: DeckStackDraft): NextcloudApiRequest {
        access.requireActiveManage(access.boardId)
        return request(
            BoardOperation.CreateLane,
            ids(BoardEntity.Board to access.boardId.value),
            fields = mapOf(BoardField.Title to text(draft.normalizedTitle), BoardField.Order to BoardInput.Number(draft.order)),
        )
    }

    fun updateStack(
        access: DeckBoardAccess,
        context: DeckStackContext,
        update: DeckStackDraft,
        record: Map<String, String> = emptyMap(),
    ): NextcloudApiRequest {
        access.requireActiveManage(context.boardId)
        return request(
            BoardOperation.UpdateLane,
            ids(BoardEntity.Board to context.boardId.value, BoardEntity.Lane to context.stackId),
            fields = mapOf(BoardField.Title to text(update.normalizedTitle), BoardField.Order to BoardInput.Number(update.order)),
            record = record,
        )
    }

    fun deleteStack(access: DeckBoardAccess, context: DeckStackContext): NextcloudApiRequest {
        access.requireActiveManage(context.boardId)
        return request(
            BoardOperation.DeleteLane,
            ids(BoardEntity.Board to context.boardId.value, BoardEntity.Lane to context.stackId),
        )
    }

    fun createCard(access: DeckBoardAccess, context: DeckStackContext, draft: DeckCardDraft): NextcloudApiRequest {
        access.requireEdit(context.boardId)
        return request(
            BoardOperation.CreateCard,
            ids(BoardEntity.Board to context.boardId.value, BoardEntity.Lane to context.stackId),
            fields = mapOf(
                BoardField.Title to text(draft.normalizedTitle),
                BoardField.Order to BoardInput.Number(draft.order),
                BoardField.Description to text(draft.normalizedDescription),
                BoardField.DueDate to text(draft.normalizedDueAt),
                BoardField.StartDate to text(draft.normalizedStartAt),
                BoardField.Color to text(draft.normalizedColor),
            ),
        )
    }

    fun updateCard(access: DeckBoardAccess, context: DeckCardContext, update: DeckCardUpdate): NextcloudApiRequest {
        access.requireEdit(context.stack.boardId)
        require(update.original.boardId == context.stack.boardId.value) { "The card update belongs to another board." }
        require(update.original.stackId == context.stack.stackId) { "The card update belongs to another list." }
        require(update.original.id == context.cardId) { "The card update belongs to another card." }
        return request(
            BoardOperation.UpdateCard,
            update.original.identities(),
            fields = mapOf(
                BoardField.Title to text(update.normalizedTitle),
                BoardField.Owner to text(update.normalizedOwnerId),
                BoardField.Order to BoardInput.Number(update.order),
                BoardField.Description to clearable(update.normalizedDescription),
                BoardField.DueDate to clearable(update.normalizedDueAt),
                BoardField.StartDate to clearable(update.normalizedStartAt),
                BoardField.Archived to BoardInput.Flag(update.archived),
                BoardField.Done to BoardInput.Completion(update.normalizedCompletedAt),
                BoardField.Color to text(update.normalizedColor),
            ),
            record = update.original.rawFields,
        )
    }

    fun deleteCard(access: DeckBoardAccess, card: DeckCard): NextcloudApiRequest {
        access.requireEdit(DeckBoardId(card.boardId))
        return request(BoardOperation.DeleteCard, card.identities())
    }

    fun archiveCard(access: DeckBoardAccess, card: DeckCard): NextcloudApiRequest {
        access.requireEdit(DeckBoardId(card.boardId))
        return request(BoardOperation.ArchiveCard, card.identities())
    }

    fun unarchiveCard(access: DeckBoardAccess, card: DeckCard): NextcloudApiRequest {
        access.requireEdit(DeckBoardId(card.boardId))
        return request(BoardOperation.UnarchiveCard, card.identities())
    }

    /** The path addresses the card in its current list; only the body names the destination. */
    fun moveCard(access: DeckBoardAccess, card: DeckCard, move: DeckCardMove): NextcloudApiRequest {
        access.requireEdit(move.source.stack.boardId)
        require(card.id == move.source.cardId && card.stackId == move.source.stack.stackId) {
            "The card move belongs to another card."
        }
        return request(
            BoardOperation.MoveCard,
            card.identities(),
            fields = mapOf(BoardField.Order to BoardInput.Number(move.order)),
            bodyIdentities = ids(BoardEntity.Lane to move.destinationStack.stackId),
        )
    }

    fun assignLabel(access: DeckBoardAccess, card: DeckCard, labelId: Long): NextcloudApiRequest {
        access.requireEdit(DeckBoardId(card.boardId))
        return request(BoardOperation.AssignLabel, card.identities() + (BoardEntity.Label to access.requireLabel(labelId).toString()))
    }

    fun removeLabel(access: DeckBoardAccess, card: DeckCard, labelId: Long): NextcloudApiRequest {
        access.requireEdit(DeckBoardId(card.boardId))
        return request(BoardOperation.RemoveLabel, card.identities() + (BoardEntity.Label to access.requireLabel(labelId).toString()))
    }

    fun assignUser(access: DeckBoardAccess, card: DeckCard, userId: String): NextcloudApiRequest {
        access.requireEdit(DeckBoardId(card.boardId))
        return request(BoardOperation.AssignUser, card.identities() + (BoardEntity.User to access.requireUser(userId)))
    }

    fun unassignUser(access: DeckBoardAccess, card: DeckCard, userId: String): NextcloudApiRequest {
        access.requireEdit(DeckBoardId(card.boardId))
        return request(BoardOperation.UnassignUser, card.identities() + (BoardEntity.User to access.requireUser(userId)))
    }

    fun createLabel(access: DeckBoardAccess, draft: DeckLabelDraft): NextcloudApiRequest {
        access.requireActiveManage(access.boardId)
        return request(
            BoardOperation.CreateLabel,
            ids(BoardEntity.Board to access.boardId.value),
            fields = mapOf(BoardField.Title to text(draft.normalizedTitle), BoardField.Color to text(draft.normalizedColor)),
        )
    }

    fun updateLabel(access: DeckBoardAccess, labelId: Long, draft: DeckLabelDraft): NextcloudApiRequest {
        access.requireActiveManage(access.boardId)
        return request(
            BoardOperation.UpdateLabel,
            ids(BoardEntity.Board to access.boardId.value, BoardEntity.Label to access.requireLabel(labelId)),
            fields = mapOf(BoardField.Title to text(draft.normalizedTitle), BoardField.Color to text(draft.normalizedColor)),
        )
    }

    fun deleteLabel(access: DeckBoardAccess, labelId: Long): NextcloudApiRequest {
        access.requireActiveManage(access.boardId)
        return request(
            BoardOperation.DeleteLabel,
            ids(BoardEntity.Board to access.boardId.value, BoardEntity.Label to access.requireLabel(labelId)),
        )
    }

    fun comments(access: DeckBoardAccess, card: DeckCard, limit: Int, offset: Int): NextcloudApiRequest {
        access.requireRead(DeckBoardId(card.boardId))
        require(limit in 1..MAX_BOARD_COMMENT_PAGE_SIZE) { "The comment page size is invalid." }
        require(offset >= 0) { "The comment offset is invalid." }
        return request(
            BoardOperation.ListComments,
            card.identities(),
            fields = mapOf(BoardField.Limit to BoardInput.Number(limit.toLong()), BoardField.Offset to BoardInput.Number(offset.toLong())),
            read = true,
            authoritative = true,
        )
    }

    fun createComment(access: DeckBoardAccess, card: DeckCard, draft: DeckCommentDraft): NextcloudApiRequest {
        access.requireCommentWrite(DeckBoardId(card.boardId))
        return request(
            BoardOperation.CreateComment,
            card.identities(),
            fields = buildMap {
                put(BoardField.Message, text(draft.normalizedMessage))
                draft.parentId?.let { put(BoardField.ParentComment, BoardInput.Number(it)) }
            },
        )
    }

    fun updateComment(
        access: DeckBoardAccess,
        card: DeckCard,
        comment: DeckComment,
        currentUserId: String,
        update: DeckCommentUpdate,
    ): NextcloudApiRequest {
        access.requireCommentWrite(DeckBoardId(card.boardId))
        requireOwnComment(card, comment, currentUserId)
        return request(
            BoardOperation.UpdateComment,
            card.identities() + (BoardEntity.Comment to comment.id.toString()),
            fields = mapOf(BoardField.Message to text(update.normalizedMessage)),
        )
    }

    fun deleteComment(access: DeckBoardAccess, card: DeckCard, comment: DeckComment, currentUserId: String): NextcloudApiRequest {
        access.requireCommentWrite(DeckBoardId(card.boardId))
        requireOwnComment(card, comment, currentUserId)
        return request(BoardOperation.DeleteComment, card.identities() + (BoardEntity.Comment to comment.id.toString()))
    }

    fun attachments(access: DeckBoardAccess, card: DeckCard): NextcloudApiRequest {
        access.requireRead(DeckBoardId(card.boardId))
        return request(BoardOperation.ListAttachments, card.identities(), read = true, authoritative = true)
            .copy(maximumResponseBytes = BOARD_ATTACHMENT_RESPONSE_BYTES)
    }

    fun openAttachment(access: DeckBoardAccess, card: DeckCard, attachment: DeckAttachment): DeckAttachmentOpenTarget {
        val request = previewAttachment(access, card, attachment)
        return DeckAttachmentOpenTarget(request.method, request.relativePath)
    }

    fun previewAttachment(access: DeckBoardAccess, card: DeckCard, attachment: DeckAttachment): NextcloudApiRequest {
        access.requireRead(DeckBoardId(card.boardId))
        require(attachment.cardId == card.id) { "The attachment belongs to another card." }
        return request(
            BoardOperation.ReadAttachment,
            card.identities() + (BoardEntity.Attachment to attachment.id.toString()),
            attributes = mapOf("type" to attachment.type.serverValue),
            read = true,
            authoritative = true,
        ).copy(maximumResponseBytes = BOARD_ATTACHMENT_RESPONSE_BYTES)
    }

    /** A durable multipart upload whose path and parts all come from the declared contract. */
    fun attachmentUpload(access: DeckBoardAccess, card: DeckCard, file: LocalUploadFile): NextcloudMultipartUploadRequest {
        access.requireEdit(DeckBoardId(card.boardId))
        val fileField = checkNotNull(contract.binding(BoardOperation.UploadAttachment).fileField)
        val built = request(
            BoardOperation.UploadAttachment,
            card.identities(),
            extraValues = mapOf(fileField to encodeDynamicLocalUploadSelection(file)),
        )
        return checkNotNull(built.multipartBody) { "The upload contract has no file part." }.toUploadRequest(built)
    }

    fun deleteAttachment(access: DeckBoardAccess, card: DeckCard, attachment: DeckAttachment): NextcloudApiRequest {
        access.requireEdit(DeckBoardId(card.boardId))
        return request(
            BoardOperation.DeleteAttachment,
            card.identities() + (BoardEntity.Attachment to attachment.id.toString()),
            attributes = mapOf("type" to attachment.type.serverValue),
        )
    }

    fun addShare(board: DeckBoard, sharee: DeckSharee): NextcloudApiRequest {
        require(board.canAddShares()) { "Share permission is required." }
        require(board.acl.none { it.type == sharee.type && it.participant.id == sharee.id }) {
            "This board is already shared with ${sharee.displayName}."
        }
        require(!(sharee.type == DeckAclType.User && sharee.id == board.owner?.id)) { "The board owner already has access." }
        return request(
            BoardOperation.AddShare,
            ids(BoardEntity.Board to board.id),
            fields = mapOf(
                BoardField.ParticipantType to BoardInput.Number(checkNotNull(sharee.type.wireValue).toLong()),
                BoardField.Participant to text(sharee.id),
                BoardField.PermissionEdit to BoardInput.Flag(false),
                BoardField.PermissionShare to BoardInput.Flag(false),
                BoardField.PermissionManage to BoardInput.Flag(false),
            ),
        )
    }

    fun updateShare(board: DeckBoard, acl: DeckAcl, currentUserId: String, requested: DeckAclPermissions): NextcloudApiRequest {
        val current = board.requireCurrentAcl(acl)
        val editability = board.aclEditability(current, currentUserId)
        require(editability.canChangeAny) { "You cannot change this share." }
        require(editability.canChangeEdit || requested.canEdit == current.canEdit) { "You cannot change edit access for this share." }
        require(editability.canChangeShare || requested.canShare == current.canShare) {
            "You cannot change sharing access for this share."
        }
        require(editability.canChangeManage || requested.canManage == current.canManage) {
            "You cannot change manage access for this share."
        }
        return request(
            BoardOperation.UpdateShare,
            ids(BoardEntity.Board to board.id, BoardEntity.Share to current.id),
            fields = mapOf(
                BoardField.PermissionEdit to BoardInput.Flag(requested.canEdit),
                BoardField.PermissionShare to BoardInput.Flag(requested.canShare),
                BoardField.PermissionManage to BoardInput.Flag(requested.canManage),
            ),
            record = current.rawFields,
        )
    }

    fun deleteShare(board: DeckBoard, acl: DeckAcl, currentUserId: String): NextcloudApiRequest {
        val current = board.requireCurrentAcl(acl)
        require(board.aclEditability(current, currentUserId).canRemove) { "Manage permission is required to remove access." }
        return request(BoardOperation.DeleteShare, ids(BoardEntity.Board to board.id, BoardEntity.Share to current.id))
    }

    /** Whether the update operation declares [field], so an editor only offers what it can save. */
    fun declares(operation: BoardOperation, field: BoardField): Boolean =
        contract.operations[operation]?.declares(field) == true

    private fun request(
        operation: BoardOperation,
        identities: Map<BoardEntity, String>,
        fields: Map<BoardField, BoardInput> = emptyMap(),
        bodyIdentities: Map<BoardEntity, String> = emptyMap(),
        attributes: Map<String, String> = emptyMap(),
        record: Map<String, String> = emptyMap(),
        read: Boolean = false,
        authoritative: Boolean = false,
        extraValues: Map<String, String> = emptyMap(),
    ): NextcloudApiRequest {
        val binding = contract.binding(operation)
        // Each location has its own map: the path addresses the card where it is now, while a body
        // field with the same name (a move's destination list) may carry a different value.
        val path = linkedMapOf<String, String>()
        val query = linkedMapOf<String, String>()
        val body = linkedMapOf<String, String>()
        val nulls = mutableSetOf<String>()
        body.putAll(binding.fixedBodyValues)
        body.putAll(extraValues)
        fun resolve(role: BoardValueRole, property: JsonObject?, inBody: Boolean): String? = when (role) {
            is BoardValueRole.Identity -> (if (inBody) bodyIdentities[role.entity] else null) ?: identities[role.entity]
            is BoardValueRole.Attribute -> attributes[role.name]
            is BoardValueRole.Field -> fields[role.field]?.wireValue(property)
        }
        binding.pathRoles.forEach { (name, role) ->
            path[name] = resolve(role, null, inBody = false)
                ?: error("The board context is missing for ${operation.name}.")
        }
        binding.queryRoles.forEach { (name, role) -> resolve(role, null, inBody = false)?.let { query[name] = it } }
        binding.bodyRoles.forEach { (name, role) ->
            val property = binding.bodyProperties[name]
            if (role is BoardValueRole.Field && fields[role.field]?.clearsTo(property) == true) nulls += name
            resolve(role, property, inBody = true)?.let { body[name] = it }
        }
        if (binding.action.binding.method == HttpMethod.PUT) binding.echoFrom(record, body, nulls)
        val built = buildDynamicApiRequest(
            descriptor = contract.descriptor,
            action = binding.action,
            values = path,
            runtimeContext = path,
            queryValues = query,
            bodyValues = body,
            nullBodyFields = nulls,
        )
        return built.copy(
            maximumResponseBytes = if (read) BOARD_READ_RESPONSE_BYTES else BOARD_WRITE_RESPONSE_BYTES,
            cachePolicy = if (authoritative || !read) NextcloudApiCachePolicy.ForceNetwork else built.cachePolicy,
        )
    }
}

/** A full replacement keeps every declared field the client did not change at its server value. */
private fun BoardOperationBinding.echoFrom(
    record: Map<String, String>,
    values: MutableMap<String, String>,
    nulls: Set<String>,
) {
    val byName = record.entries.associateBy({ it.key.semanticRouteId() }, { it.value })
    echoedBodyFields.forEach { name ->
        if (name !in values && name !in nulls) byName[name.semanticRouteId()]?.let { values[name] = it }
    }
}

private fun BoardInput.wireValue(property: JsonObject?): String? {
    val type = property?.declaredJsonType()
    return when (this) {
        is BoardInput.Text -> value
        is BoardInput.Number -> value.toString()
        is BoardInput.Flag -> if (type == "integer") (if (value) "1" else "0") else value.toString()
        is BoardInput.Completion -> when (type) {
            "boolean" -> (completedAt != null).toString()
            "integer" -> if (completedAt != null) "1" else "0"
            else -> completedAt.orEmpty()
        }
        // A non-nullable text field is cleared by an empty string; anything else stays unsent.
        BoardInput.Clear -> "".takeIf { type == "string" && property?.declaresNull() != true }
    }
}

/** Whether this input removes a value the contract lets the client set to JSON null. */
private fun BoardInput.clearsTo(property: JsonObject?): Boolean = property?.declaresNull() == true && when (this) {
    BoardInput.Clear -> true
    is BoardInput.Completion -> completedAt == null && property.declaredJsonType() !in setOf("boolean", "integer")
    else -> false
}

private fun text(value: String?): BoardInput = BoardInput.Text(value)

private fun clearable(value: String?): BoardInput =
    value?.takeIf(String::isNotBlank)?.let(BoardInput::Text) ?: BoardInput.Clear

private fun ids(vararg pairs: Pair<BoardEntity, Long>): Map<BoardEntity, String> =
    pairs.associate { (entity, id) ->
        require(id > 0L) { "The board resource id is invalid." }
        entity to id.toString()
    }

private fun DeckCard.identities(): Map<BoardEntity, String> =
    ids(BoardEntity.Board to boardId, BoardEntity.Lane to stackId, BoardEntity.Card to id)

private fun requireOwnComment(card: DeckCard, comment: DeckComment, currentUserId: String) {
    require(comment.cardId == card.id) { "The comment belongs to another card." }
    require(currentUserId.isNotBlank() && comment.authorId == currentUserId.trim()) {
        "Only the comment author can change this comment."
    }
}

private fun DeckBoard.requireCurrentAcl(acl: DeckAcl): DeckAcl {
    require(acl.boardId == id) { "The share belongs to another board." }
    return this.acl.firstOrNull { it.id == acl.id } ?: error("The share is not part of the current board.")
}

internal const val MAX_BOARD_COMMENT_PAGE_SIZE = 100
private const val BOARD_READ_RESPONSE_BYTES = 8L * 1024L * 1024L
private const val BOARD_WRITE_RESPONSE_BYTES = 2L * 1024L * 1024L
private const val BOARD_ATTACHMENT_RESPONSE_BYTES = 4L * 1024L * 1024L
