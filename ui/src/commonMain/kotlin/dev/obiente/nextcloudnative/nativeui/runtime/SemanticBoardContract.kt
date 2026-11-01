package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.DynamicAction
import dev.obiente.nextcloudnative.nativeui.model.DynamicAppDescriptor
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.runtime.SemanticRouteShape.Token
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Things a board-shaped contract addresses. Identities are always typed by their entity. */
internal enum class BoardEntity { Board, Lane, Card, Label, User, Comment, Attachment, Share }

/** Semantic roles of request inputs, resolved from contract names rather than an app identity. */
internal enum class BoardField {
    Title, Description, Color, Order, DueDate, StartDate, Done, Archived, Owner,
    Message, ParentComment, ParticipantType, Participant, PermissionEdit, PermissionShare,
    PermissionManage, Limit, Offset,
}

internal sealed interface BoardValueRole {
    data class Identity(val entity: BoardEntity) : BoardValueRole
    data class Field(val field: BoardField) : BoardValueRole

    /** A non-identity path input of the addressed item, such as an attachment `{type}`. */
    data class Attribute(val name: String) : BoardValueRole
}

internal enum class BoardOperation(val write: Boolean) {
    ListBoards(false), ReadBoard(false), CreateBoard(true), UpdateBoard(true), DeleteBoard(true),
    RestoreBoard(true),
    ListLanes(false), ListArchivedLanes(false), CreateLane(true), UpdateLane(true), DeleteLane(true),
    ListCards(false), ReadCard(false), CreateCard(true), UpdateCard(true), DeleteCard(true),
    ArchiveCard(true), UnarchiveCard(true), MoveCard(true),
    AssignLabel(true), RemoveLabel(true), AssignUser(true), UnassignUser(true),
    CreateLabel(true), UpdateLabel(true), DeleteLabel(true),
    ListComments(false), CreateComment(true), UpdateComment(true), DeleteComment(true),
    ListAttachments(false), ReadAttachment(false), UploadAttachment(true), DeleteAttachment(true),
    RestoreAttachment(true),
    ListShares(false), AddShare(true), UpdateShare(true), DeleteShare(true),
}

/**
 * One contract action chosen for a board operation, with every declared input mapped to a role.
 *
 * Inputs the client cannot name are kept in [echoedBodyFields]: a full-replacement update fills
 * them from the server's own current record so an omitted field is never silently reset.
 */
internal data class BoardOperationBinding(
    val operation: BoardOperation,
    val action: DynamicAction,
    val pathRoles: Map<String, BoardValueRole>,
    val queryRoles: Map<String, BoardValueRole>,
    val bodyRoles: Map<String, BoardValueRole>,
    val echoedBodyFields: Set<String>,
    val requiredBodyFields: Set<String>,
    val bodyProperties: Map<String, JsonObject>,
    /** The single binary part of a multipart upload. */
    val fileField: String? = null,
    /** Required inputs whose only allowed value the contract itself declares. */
    val fixedBodyValues: Map<String, String> = emptyMap(),
) {
    fun declares(field: BoardField): Boolean = bodyRoles.values.any { it == BoardValueRole.Field(field) }

    fun declaresIdentity(entity: BoardEntity): Boolean =
        (pathRoles.values + bodyRoles.values + queryRoles.values).any { it == BoardValueRole.Identity(entity) }

    fun bodyFieldFor(field: BoardField): String? =
        bodyRoles.entries.firstOrNull { it.value == BoardValueRole.Field(field) }?.key
}

/**
 * Board operations an installed app's own contract describes.
 *
 * There is deliberately no app identifier or version here. Any app whose contract exposes a
 * board collection, lanes inside a board, and cards inside lanes receives the board surface; each
 * individual action appears only when the contract describes it.
 */
internal data class SemanticBoardContract(
    val descriptor: DynamicAppDescriptor,
    val operations: Map<BoardOperation, BoardOperationBinding>,
    val settings: SemanticSettingsContract? = null,
) {
    fun supports(operation: BoardOperation): Boolean = operation in operations

    fun binding(operation: BoardOperation): BoardOperationBinding =
        operations[operation] ?: throw BoardOperationUnavailableException(operation)
}

internal class BoardOperationUnavailableException(operation: BoardOperation) :
    IllegalStateException("This app does not describe how to ${operation.userDescription()}.")

/**
 * Resolves the board pattern from [descriptor], or returns null when it is not board-shaped.
 *
 * Writes are only resolved when [writesAuthorized] is true (the contract is verified for the
 * installed version) and the individual action carries trusted contract provenance.
 */
internal fun resolveSemanticBoardContract(
    descriptor: DynamicAppDescriptor,
    writesAuthorized: Boolean,
): SemanticBoardContract? {
    val candidates = descriptor.semanticRoutes().mapNotNull { (action, shape) ->
        val allowed = if (action.isSemanticRead()) {
            action.risk == ActionRisk.readOnly
        } else {
            action.risk != ActionRisk.readOnly && action.isAuthorizedSemanticWrite(writesAuthorized)
        }
        if (!allowed) return@mapNotNull null
        classifyBoardRoute(action, shape)?.let { binding -> Triple(binding, action, shape) }
    }
    val operations = candidates.groupBy { (binding, _, _) -> binding.operation }
        .mapValues { (_, matches) ->
            matches.sortedWith { left, right ->
                semanticRoutePreference.compare(left.second to left.third, right.second to right.third)
            }.first().first
        }
    val hasCards = BoardOperation.ListCards in operations || BoardOperation.ReadCard in operations ||
        BoardOperation.CreateCard in operations ||
        operations[BoardOperation.ListLanes]?.action?.responseFieldIds.orEmpty()
            .any { it.semanticRouteId() in CARD_WORDS }
    if (BoardOperation.ListBoards !in operations || BoardOperation.ListLanes !in operations || !hasCards) {
        return null
    }
    return SemanticBoardContract(
        descriptor = descriptor,
        operations = operations,
        settings = resolveSemanticSettingsContract(descriptor, writesAuthorized),
    )
}

private data class RouteTarget(
    val entity: BoardEntity,
    val item: Boolean,
    val verb: String?,
    val pathRoles: Map<String, BoardValueRole>,
    /** The addressed item the target is nested under, as a card in `/cards/{cardId}/labels/{labelId}`. */
    val parent: BoardEntity? = null,
)

private fun classifyBoardRoute(action: DynamicAction, shape: SemanticRouteShape): BoardOperationBinding? {
    val target = shape.boardTarget() ?: return null
    val bodyProperties = action.declaredBodyProperties()
    val bodyRoles = bodyProperties.keys.mapNotNull { name ->
        boardBodyRole(name, target.entity)?.let { name to it }
    }.toMap()
    val queryRoles = action.binding.queryParameters.mapNotNull { parameter ->
        boardQueryRole(parameter.name, target.entity)?.let { parameter.name to it }
    }.toMap()
    if (action.binding.queryParameters.any { it.required && it.name !in queryRoles }) return null
    val operation = boardOperation(action.binding.method, target, bodyRoles) ?: return null
    val required = action.requiredBodyProperties()
    val fileField = if (operation == BoardOperation.UploadAttachment) {
        bodyProperties.entries.singleOrNull { (_, property) -> property.isBinaryPart() }?.key ?: return null
    } else {
        null
    }
    val fixed = (bodyProperties.keys - bodyRoles.keys - setOfNotNull(fileField)).mapNotNull { name ->
        bodyProperties.getValue(name).singleDeclaredValue()?.let { name to it }
    }.toMap()
    val echoed = bodyProperties.keys - bodyRoles.keys - setOfNotNull(fileField) - fixed.keys
    // A required input the client can neither name nor read back from a record cannot be supplied.
    val echoAllowed = operation in ECHOING_OPERATIONS
    if (required.any { it in echoed } && !echoAllowed) return null
    if (
        operation == BoardOperation.UploadAttachment &&
        action.binding.body?.contentType?.substringBefore(';')?.trim()?.lowercase() != "multipart/form-data"
    ) {
        return null
    }
    if (operation == BoardOperation.MoveCard && !bodyRoles.containsValue(BoardValueRole.Identity(BoardEntity.Lane))) {
        return null
    }
    if (operation in TITLED_CREATES && BoardValueRole.Field(BoardField.Title) !in bodyRoles.values) return null
    if (operation in MESSAGE_WRITES && BoardValueRole.Field(BoardField.Message) !in bodyRoles.values) return null
    if (
        operation == BoardOperation.CreateCard &&
        !(target.pathRoles.values + bodyRoles.values).contains(BoardValueRole.Identity(BoardEntity.Lane))
    ) {
        return null
    }
    return BoardOperationBinding(
        operation = operation,
        action = action,
        pathRoles = target.pathRoles,
        queryRoles = queryRoles,
        bodyRoles = bodyRoles,
        echoedBodyFields = echoed,
        requiredBodyFields = required,
        bodyProperties = bodyProperties,
        fileField = fileField,
        fixedBodyValues = fixed,
    )
}

private fun JsonObject.isBinaryPart(): Boolean =
    declaredJsonType() == "string" && (this["format"] as? JsonPrimitive)?.contentOrNull == "binary"

/** A value the contract fixes: a one-member enum or a `const`. Defaults are not assumed. */
private fun JsonObject.singleDeclaredValue(): String? =
    declaredEnumValues().singleOrNull() ?: (this["const"] as? JsonPrimitive)?.contentOrNull

private fun SemanticRouteShape.boardTarget(): RouteTarget? {
    var current: BoardEntity? = null
    var parent: BoardEntity? = null
    var item = false
    var verb: String? = null
    val roles = linkedMapOf<String, BoardValueRole>()
    tokens.forEachIndexed { index, token ->
        when (token) {
            is Token.Word -> {
                val entity = BOARD_ENTITY_WORDS.entries.firstOrNull { token.word in it.value }?.key
                when {
                    verb != null -> return null
                    entity != null -> {
                        parent = current?.takeIf { item }
                        current = entity
                        item = false
                    }
                    current != null && index == tokens.lastIndex -> verb = token.word
                    else -> return null
                }
            }
            is Token.Placeholder -> {
                if (verb != null) return null
                val named = token.normalized.semanticIdentityStem()?.let(::boardEntityForStem)
                val owner = current
                when {
                    named != null -> {
                        roles[token.name] = BoardValueRole.Identity(named)
                        if (named == owner) item = true
                    }
                    owner != null && token.normalized in GENERIC_IDENTITY_PLACEHOLDERS -> {
                        roles[token.name] = BoardValueRole.Identity(owner)
                        item = true
                    }
                    owner != null && !item -> roles[token.name] = BoardValueRole.Attribute(token.normalized)
                    else -> return null
                }
            }
        }
    }
    return current?.let { entity -> RouteTarget(entity, item, verb, roles, parent) }
}

private fun boardOperation(
    method: HttpMethod,
    target: RouteTarget,
    bodyRoles: Map<String, BoardValueRole>,
): BoardOperation? {
    val verb = target.verb
    val get = method == HttpMethod.GET
    val write = method in setOf(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH)
    val delete = method == HttpMethod.DELETE
    val itemUpdate = method in setOf(HttpMethod.PUT, HttpMethod.PATCH)
    // A label or user addressed inside a card relates it to that card; it never edits the label.
    if (target.parent == BoardEntity.Card && target.entity in setOf(BoardEntity.Label, BoardEntity.User)) {
        val label = target.entity == BoardEntity.Label
        return when {
            verb != null || !target.item -> null
            write -> if (label) BoardOperation.AssignLabel else BoardOperation.AssignUser
            delete -> if (label) BoardOperation.RemoveLabel else BoardOperation.UnassignUser
            else -> null
        }
    }
    return when (target.entity) {
        BoardEntity.Board -> when {
            verb in RESTORE_VERBS && target.item && write -> BoardOperation.RestoreBoard
            verb != null -> null
            get && !target.item -> BoardOperation.ListBoards
            get -> BoardOperation.ReadBoard
            method == HttpMethod.POST && !target.item -> BoardOperation.CreateBoard
            itemUpdate && target.item -> BoardOperation.UpdateBoard
            delete && target.item -> BoardOperation.DeleteBoard
            else -> null
        }
        BoardEntity.Lane -> when {
            verb == "archived" && get && !target.item -> BoardOperation.ListArchivedLanes
            verb != null -> null
            get && !target.item && target.pathRoles.containsValue(BoardValueRole.Identity(BoardEntity.Board)) ->
                BoardOperation.ListLanes
            method == HttpMethod.POST && !target.item -> BoardOperation.CreateLane
            itemUpdate && target.item -> BoardOperation.UpdateLane
            delete && target.item -> BoardOperation.DeleteLane
            else -> null
        }
        BoardEntity.Card -> when {
            verb in ARCHIVE_VERBS && target.item && write -> BoardOperation.ArchiveCard
            verb in UNARCHIVE_VERBS && target.item && write -> BoardOperation.UnarchiveCard
            verb in MOVE_VERBS && target.item && write -> BoardOperation.MoveCard
            verb != null && target.item && write -> cardRelationshipOperation(verb, bodyRoles)
            verb != null -> null
            get && !target.item && target.pathRoles.containsValue(BoardValueRole.Identity(BoardEntity.Lane)) ->
                BoardOperation.ListCards
            get && target.item -> BoardOperation.ReadCard
            method == HttpMethod.POST && !target.item -> BoardOperation.CreateCard
            itemUpdate && target.item -> BoardOperation.UpdateCard
            delete && target.item -> BoardOperation.DeleteCard
            else -> null
        }
        BoardEntity.Label -> when {
            verb != null -> null
            method == HttpMethod.POST && !target.item -> BoardOperation.CreateLabel
            itemUpdate && target.item -> BoardOperation.UpdateLabel
            delete && target.item -> BoardOperation.DeleteLabel
            else -> null
        }
        BoardEntity.Comment -> when {
            verb != null -> null
            get && !target.item -> BoardOperation.ListComments
            method == HttpMethod.POST && !target.item -> BoardOperation.CreateComment
            itemUpdate && target.item -> BoardOperation.UpdateComment
            delete && target.item -> BoardOperation.DeleteComment
            else -> null
        }
        BoardEntity.Attachment -> when {
            verb in RESTORE_VERBS && target.item && write -> BoardOperation.RestoreAttachment
            verb != null -> null
            get && !target.item -> BoardOperation.ListAttachments
            get -> BoardOperation.ReadAttachment
            method == HttpMethod.POST && !target.item -> BoardOperation.UploadAttachment
            delete && target.item -> BoardOperation.DeleteAttachment
            else -> null
        }
        BoardEntity.Share -> when {
            verb != null -> null
            get && !target.item -> BoardOperation.ListShares
            method == HttpMethod.POST && !target.item -> BoardOperation.AddShare
            itemUpdate && target.item -> BoardOperation.UpdateShare
            delete && target.item -> BoardOperation.DeleteShare
            else -> null
        }
        BoardEntity.User -> null
    }
}

private fun cardRelationshipOperation(verb: String, bodyRoles: Map<String, BoardValueRole>): BoardOperation? {
    val label = BoardValueRole.Identity(BoardEntity.Label) in bodyRoles.values
    val user = BoardValueRole.Identity(BoardEntity.User) in bodyRoles.values
    if (label == user) return null
    val removes = REMOVE_RELATION_PREFIXES.any(verb::startsWith)
    val adds = ADD_RELATION_PREFIXES.any(verb::startsWith)
    if (removes == adds) return null
    return when {
        label && adds -> BoardOperation.AssignLabel
        label -> BoardOperation.RemoveLabel
        adds -> BoardOperation.AssignUser
        else -> BoardOperation.UnassignUser
    }
}

private fun boardBodyRole(name: String, entity: BoardEntity): BoardValueRole? {
    val normalized = name.semanticRouteId()
    normalized.semanticIdentityStem()?.let(::boardEntityForStem)?.let { return BoardValueRole.Identity(it) }
    if (normalized in USER_IDENTITY_FIELDS) return BoardValueRole.Identity(BoardEntity.User)
    if (entity == BoardEntity.Share) {
        when (normalized) {
            "type", "participanttype", "sharetype" -> return BoardValueRole.Field(BoardField.ParticipantType)
            "participant", "sharewith", "principal" -> return BoardValueRole.Field(BoardField.Participant)
        }
    }
    if (entity == BoardEntity.Comment && normalized in MESSAGE_FIELDS) return BoardValueRole.Field(BoardField.Message)
    if (entity == BoardEntity.Comment && normalized == "parentid") {
        return BoardValueRole.Field(BoardField.ParentComment)
    }
    return BOARD_FIELD_NAMES.entries.firstOrNull { normalized in it.value }?.key?.let(BoardValueRole::Field)
}

private fun boardQueryRole(name: String, entity: BoardEntity): BoardValueRole? = when (name.semanticRouteId()) {
    "limit", "pagesize", "count" -> BoardValueRole.Field(BoardField.Limit)
    "offset", "start" -> BoardValueRole.Field(BoardField.Offset)
    else -> boardBodyRole(name, entity)?.takeIf { it is BoardValueRole.Identity }
}

private fun boardEntityForStem(stem: String): BoardEntity? =
    BOARD_ENTITY_WORDS.entries.firstOrNull { (_, words) -> stem in words }?.key
        ?: BoardEntity.User.takeIf { stem in setOf("participant", "assignee", "member") }

internal fun BoardOperation.userDescription(): String = name
    .replace(Regex("([a-z])([A-Z])"), "$1 $2")
    .lowercase()

private val BOARD_ENTITY_WORDS: Map<BoardEntity, Set<String>> = mapOf(
    BoardEntity.Board to setOf("board", "boards", "project", "projects"),
    BoardEntity.Lane to setOf(
        "stack", "stacks", "list", "lists", "column", "columns", "lane", "lanes", "stage", "stages",
    ),
    BoardEntity.Card to setOf("card", "cards", "task", "tasks", "ticket", "tickets", "issue", "issues"),
    BoardEntity.Label to setOf("label", "labels", "tag", "tags"),
    BoardEntity.User to setOf("user", "users"),
    BoardEntity.Comment to setOf("comment", "comments"),
    BoardEntity.Attachment to setOf("attachment", "attachments"),
    BoardEntity.Share to setOf("acl", "acls", "share", "shares", "member", "members"),
)

private val GENERIC_IDENTITY_PLACEHOLDERS = setOf("id", "key", "uid")
private val CARD_WORDS = setOf("cards", "tasks", "tickets", "issues")
private val USER_IDENTITY_FIELDS = setOf("userid", "uid", "assignee", "assigneeid", "participantid")
private val MESSAGE_FIELDS = setOf("message", "comment", "content", "body", "text")
private val BOARD_FIELD_NAMES: Map<BoardField, Set<String>> = mapOf(
    BoardField.Title to setOf("title", "name"),
    BoardField.Description to setOf("description", "details", "body", "content", "notes"),
    BoardField.Color to setOf("color", "colour"),
    BoardField.Order to setOf("order", "position", "sort", "sortorder", "index"),
    BoardField.DueDate to setOf("duedate", "due", "dueat", "deadline"),
    BoardField.StartDate to setOf("startdate", "startat", "start"),
    BoardField.Done to setOf("done", "completed", "completedat", "isdone"),
    BoardField.Archived to setOf("archived", "isarchived"),
    BoardField.Owner to setOf("owner"),
    BoardField.PermissionEdit to setOf("permissionedit", "canedit"),
    BoardField.PermissionShare to setOf("permissionshare", "canshare"),
    BoardField.PermissionManage to setOf("permissionmanage", "canmanage"),
)
private val ECHOING_OPERATIONS = setOf(
    BoardOperation.UpdateBoard, BoardOperation.UpdateLane, BoardOperation.UpdateCard,
    BoardOperation.UpdateLabel, BoardOperation.UpdateShare,
)
private val TITLED_CREATES = setOf(
    BoardOperation.CreateBoard, BoardOperation.CreateLane, BoardOperation.CreateCard, BoardOperation.CreateLabel,
)
private val MESSAGE_WRITES = setOf(BoardOperation.CreateComment, BoardOperation.UpdateComment)
private val RESTORE_VERBS = setOf("undodelete", "deleteundo", "undelete", "restore")
private val ARCHIVE_VERBS = setOf("archive")
private val UNARCHIVE_VERBS = setOf("unarchive")
private val MOVE_VERBS = setOf("reorder", "move", "relocate")
private val ADD_RELATION_PREFIXES = listOf("assign", "add", "attach", "link")
private val REMOVE_RELATION_PREFIXES = listOf("unassign", "remove", "detach", "unlink", "delete")
