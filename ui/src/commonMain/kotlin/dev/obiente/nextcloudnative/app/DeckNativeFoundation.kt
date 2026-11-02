package dev.obiente.nextcloudnative.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

data class DeckUser(
    val id: String,
    val displayName: String,
    val type: Int? = null,
)

data class DeckLabel(
    val id: Long,
    val title: String,
    val color: String?,
)

data class DeckPermissions(
    val canRead: Boolean,
    val canEdit: Boolean,
    val canManage: Boolean,
    val canShare: Boolean,
)

data class DeckBoard(
    val id: Long,
    val title: String,
    val color: String?,
    val archived: Boolean,
    val owner: DeckUser?,
    val labels: List<DeckLabel>,
    val users: List<DeckUser> = emptyList(),
    val permissions: DeckPermissions,
    val shared: Boolean,
    val lastModified: Long?,
    val etag: String?,
    val acl: List<DeckAcl> = emptyList(),
    /** False when the share list was present but malformed; sharing stays read-only then. */
    val aclReadable: Boolean = true,
    val settings: Map<String, String> = emptyMap(),
    /** Scalar server values, used only to keep unchanged fields in a full-replacement update. */
    val rawFields: Map<String, String> = emptyMap(),
)

/** The board list response, split into active boards and boards the user archived. */
data class DeckBoardCollection(
    val active: List<DeckBoard>,
    val archived: List<DeckBoard>,
)

data class DeckCard(
    val id: Long,
    val boardId: Long,
    val stackId: Long,
    val title: String,
    val descriptionMarkdown: String?,
    val ownerId: String?,
    val color: String?,
    val order: Long,
    val dueAt: String?,
    val startAt: String?,
    val completedAt: String?,
    val archived: Boolean,
    val overdue: Boolean,
    val labels: List<DeckLabel>,
    val assignees: List<DeckUser>,
    val attachmentCount: Int,
    val unreadCommentCount: Int,
    val etag: String?,
    /** Scalar server values, used only to keep unchanged fields in a full-replacement update. */
    val rawFields: Map<String, String> = emptyMap(),
)

data class DeckStack(
    val id: Long,
    val boardId: Long,
    val title: String,
    val order: Long,
    val doneColumn: Boolean,
    val cards: List<DeckCard>,
    val lastModified: Long?,
    val etag: String?,
    /** False when the list record carried no card collection, so cards are read per list. */
    val cardsEmbedded: Boolean = true,
    /** False when this list's cards were not read, so an empty [cards] does not mean an empty list. */
    val cardsLoaded: Boolean = true,
    val rawFields: Map<String, String> = emptyMap(),
)

sealed interface DeckWorkspaceState {
    data object Loading : DeckWorkspaceState
    data class BoardPicker(
        val boards: List<DeckBoard>,
        val canCreateBoards: Boolean,
        val archivedBoards: List<DeckBoard> = emptyList(),
    ) : DeckWorkspaceState
    data class Board(
        val board: DeckBoard,
        val stacks: List<DeckStack>,
        val selectedCardId: Long? = null,
    ) : DeckWorkspaceState {
        val selectedCard: DeckCard?
            get() = stacks.asSequence().flatMap { it.cards.asSequence() }
                .firstOrNull { it.id == selectedCardId }
    }
    data class Empty(
        val title: String,
        val message: String,
        val canCreateBoards: Boolean,
    ) : DeckWorkspaceState
    data class Error(
        val title: String,
        val message: String,
        val canRetry: Boolean = true,
        val cachedState: DeckWorkspaceState? = null,
    ) : DeckWorkspaceState
}

fun parseDeckBoards(response: NextcloudApiResponse): List<DeckBoard> =
    parseDeckBoardCollection(response).active

fun parseDeckBoardCollection(response: NextcloudApiResponse): DeckBoardCollection {
    require(response.status in 200..299) { "Boards failed to load (HTTP ${response.status})." }
    val boards = response.deckPayload("Boards").requireArray("Boards").map { element ->
        element.requireObject("Board").toDeckBoard()
    }.sortedBy { it.title.lowercase() }
    return DeckBoardCollection(
        active = boards.filterNot(DeckBoard::archived),
        archived = boards.filter(DeckBoard::archived),
    )
}

fun parseDeckBoard(response: NextcloudApiResponse): DeckBoard {
    require(response.status in 200..299) { "The board failed to load (HTTP ${response.status})." }
    val board = response.deckPayload("Board").requireObject("Board").toDeckBoard()
    return if (board.etag == null && response.etag != null) board.copy(etag = response.etag) else board
}

/** Finds one board in a board-list response, for contracts that describe no single-board read. */
fun parseDeckBoardFromList(boardId: Long, response: NextcloudApiResponse): DeckBoard {
    val collection = parseDeckBoardCollection(response)
    return (collection.active + collection.archived).firstOrNull { it.id == boardId }
        ?: error("The board is no longer available.")
}

fun parseDeckStacks(
    boardId: Long,
    response: NextcloudApiResponse,
    includeArchivedCards: Boolean = false,
): List<DeckStack> {
    require(response.status in 200..299) { "Lists failed to load (HTTP ${response.status})." }
    val safeBoardId = boardId.requireDeckId()
    return response.deckPayload("Lists").requireArray("Lists").map { element ->
        val value = element.requireObject("List")
        val parsedBoardId = value.firstLong(BOARD_REFERENCE_FIELDS) ?: safeBoardId
        require(parsedBoardId == safeBoardId) { "A list belongs to an unexpected board." }
        val stackId = value.requirePositiveId("id", "List")
        val embedded = value.firstArray(CARD_COLLECTION_FIELDS)
        DeckStack(
            id = stackId,
            boardId = safeBoardId,
            title = value.requireTitle("List"),
            order = value.firstLong(ORDER_FIELDS) ?: 0L,
            doneColumn = value.boolean("isDoneColumn") ?: false,
            cards = embedded.orEmpty().map { card ->
                card.requireObject("Card").toDeckCard(safeBoardId, stackId)
            }.filter { includeArchivedCards || !it.archived }.sortedBy(DeckCard::order),
            lastModified = value.firstLong(MODIFIED_FIELDS),
            etag = value.string("ETag") ?: value.string("etag"),
            cardsEmbedded = embedded != null,
            rawFields = value.scalarFields(),
        )
    }.sortedBy(DeckStack::order)
}

/** Cards of one list, for contracts that list cards separately from their lists. */
fun parseDeckStackCards(
    boardId: Long,
    stackId: Long,
    response: NextcloudApiResponse,
    includeArchivedCards: Boolean = false,
): List<DeckCard> {
    require(response.status in 200..299) { "Cards failed to load (HTTP ${response.status})." }
    return response.deckPayload("Cards").requireArray("Cards").map { element ->
        element.requireObject("Card").toDeckCard(boardId.requireDeckId(), stackId.requireDeckId())
    }.filter { includeArchivedCards || !it.archived }.sortedBy(DeckCard::order)
}

fun parseDeckCard(
    boardId: Long,
    stackId: Long,
    response: NextcloudApiResponse,
): DeckCard {
    require(response.status in 200..299) { "The card failed to load (HTTP ${response.status})." }
    val card = response.deckPayload("Card").requireObject("Card")
        .toDeckCard(boardId.requireDeckId(), stackId.requireDeckId())
    return if (card.etag == null && response.etag != null) card.copy(etag = response.etag) else card
}

fun deckBoardState(
    boards: List<DeckBoard>,
    selectedBoardId: Long?,
    stacks: List<DeckStack>?,
    canCreateBoards: Boolean,
    selectedCardId: Long? = null,
): DeckWorkspaceState {
    if (boards.isEmpty()) {
        return DeckWorkspaceState.Empty(
            title = "No boards yet",
            message = "Create a board to organize work into lists and cards.",
            canCreateBoards = canCreateBoards,
        )
    }
    val selected = selectedBoardId?.let { id -> boards.firstOrNull { it.id == id } }
        ?: return DeckWorkspaceState.BoardPicker(boards, canCreateBoards)
    val loadedStacks = stacks
        ?: return DeckWorkspaceState.Error(
            title = "Board is not loaded",
            message = "The board lists are unavailable. Try loading the board again.",
        )
    require(loadedStacks.all { it.boardId == selected.id }) {
        "Lists cannot be attached to the wrong board."
    }
    return DeckWorkspaceState.Board(
        board = selected,
        stacks = loadedStacks,
        selectedCardId = selectedCardId,
    )
}

private fun JsonObject.toDeckCard(boardId: Long, expectedStackId: Long): DeckCard {
    val stackId = firstLong(LANE_REFERENCE_FIELDS) ?: expectedStackId
    require(stackId == expectedStackId) { "A card belongs to an unexpected list." }
    return DeckCard(
        id = requirePositiveId("id", "Card"),
        boardId = boardId,
        stackId = stackId,
        title = requireTitle("Card"),
        descriptionMarkdown = firstString(DESCRIPTION_FIELDS),
        ownerId = string("owner")
            ?: (this["owner"] as? JsonObject)?.let { owner ->
                owner.string("uid") ?: owner.string("primaryKey") ?: owner.string("id")
            },
        color = string("color")?.normalizeDeckColor(),
        order = firstLong(ORDER_FIELDS) ?: 0L,
        dueAt = firstString(DUE_FIELDS),
        startAt = firstString(START_FIELDS),
        completedAt = completion(),
        archived = boolean("archived") ?: false,
        overdue = (long("overdue") ?: 0L) > 0L,
        labels = firstArray(LABEL_FIELDS).orEmpty().mapNotNull(JsonElement::toDeckLabel),
        assignees = firstArray(ASSIGNEE_FIELDS).orEmpty().mapNotNull { element ->
            val assignment = element as? JsonObject ?: return@mapNotNull null
            (assignment["participant"] as? JsonObject)?.toDeckUser()
                ?: (assignment["user"] as? JsonObject)?.toDeckUser()
                ?: assignment.toDeckUser()
        },
        attachmentCount = int("attachmentCount") ?: array("attachments").size,
        unreadCommentCount = int("commentsUnread") ?: 0,
        etag = string("ETag") ?: string("etag"),
        rawFields = scalarFields(),
    )
}

/** A completion timestamp, or a marker when the record only reports a completed flag. */
private fun JsonObject.completion(): String? {
    COMPLETION_FIELDS.forEach { name ->
        val element = this[name] ?: return@forEach
        if (element is JsonNull) return null
        val primitive = element as? JsonPrimitive ?: return@forEach
        primitive.booleanOrNull?.let { done -> return if (done) COMPLETED_WITHOUT_TIMESTAMP else null }
        return primitive.contentOrNull?.takeIf(String::isNotBlank)
    }
    return null
}

private fun JsonObject.toDeckBoard(): DeckBoard {
    val permissions = this["permissions"] as? JsonObject
    val owner = (this["owner"] as? JsonObject)?.toDeckUser()
    val users = buildList {
        owner?.let(::add)
        array("users").mapNotNullTo(this) { userElement ->
            val assignment = userElement as? JsonObject ?: return@mapNotNullTo null
            (assignment["participant"] as? JsonObject)?.toDeckUser()
                ?: (assignment["user"] as? JsonObject)?.toDeckUser()
                ?: assignment.toDeckUser()
        }
    }.distinctBy(DeckUser::id)
    val boardId = requirePositiveId("id", "Board")
    val acl = try {
        parseDeckBoardAcl(boardId, firstArray(SHARE_FIELDS) ?: JsonArray(emptyList()))
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IllegalStateException) {
        null
    }
    return DeckBoard(
        id = boardId,
        title = requireTitle("Board"),
        color = string("color")?.normalizeDeckColor(),
        archived = boolean("archived") ?: false,
        owner = owner,
        labels = firstArray(LABEL_FIELDS).orEmpty().mapNotNull(JsonElement::toDeckLabel),
        users = users,
        permissions = permissions.toDeckPermissions(),
        shared = (long("shared") ?: 0L) > 0L,
        lastModified = firstLong(MODIFIED_FIELDS),
        etag = string("ETag") ?: string("etag"),
        acl = acl.orEmpty(),
        aclReadable = acl != null,
        settings = (this["settings"] as? JsonObject)?.scalarFields().orEmpty(),
        rawFields = scalarFields(),
    )
}

/**
 * Only observed permission evidence grants an action. A record without a permission object is
 * readable (the server returned it) but offers no edit, manage, or share action.
 */
private fun JsonObject?.toDeckPermissions(): DeckPermissions {
    if (this == null) return DeckPermissions(canRead = true, canEdit = false, canManage = false, canShare = false)
    fun flag(vararg names: String): Boolean? = names.firstNotNullOfOrNull { boolean(it) }
    return DeckPermissions(
        canRead = flag("PERMISSION_READ", "read", "canRead") ?: true,
        canEdit = flag("PERMISSION_EDIT", "edit", "canEdit") ?: false,
        canManage = flag("PERMISSION_MANAGE", "manage", "canManage") ?: false,
        canShare = flag("PERMISSION_SHARE", "share", "canShare") ?: false,
    )
}

private fun JsonElement.toDeckLabel(): DeckLabel? {
    val value = this as? JsonObject ?: return null
    val id = value.long("id")?.takeIf { it > 0L } ?: return null
    val title = value.firstString(TITLE_FIELDS) ?: return null
    return DeckLabel(id, title, value.string("color")?.normalizeDeckColor())
}

private fun JsonObject.toDeckUser(): DeckUser? {
    val id = string("uid") ?: string("primaryKey") ?: string("id") ?: return null
    val display = string("displayname") ?: string("displayName") ?: id
    return DeckUser(id = id, displayName = display, type = int("type"))
}

private fun NextcloudApiResponse.deckPayload(label: String): JsonElement {
    require(body.size <= DECK_READ_RESPONSE_BYTES) { "$label returned too much data." }
    val root = runCatching { deckJson.parseToJsonElement(body.decodeToString()) }
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

private fun JsonElement.requireArray(label: String): JsonArray =
    this as? JsonArray ?: error("$label returned an unexpected response shape.")

private fun JsonElement.requireObject(label: String): JsonObject =
    this as? JsonObject ?: error("$label returned an unexpected record.")

private fun JsonObject.requirePositiveId(name: String, label: String): Long =
    long(name)?.takeIf { it > 0L } ?: error("$label has no valid $name.")

private fun JsonObject.requireTitle(label: String): String =
    TITLE_FIELDS.firstNotNullOfOrNull { name -> string(name)?.trim()?.takeIf(String::isNotBlank) }
        ?: error("$label has no valid title.")

private fun JsonObject.string(name: String): String? =
    this[name]?.scalarString()?.takeIf(String::isNotBlank)

private fun JsonObject.firstString(names: List<String>): String? = names.firstNotNullOfOrNull { string(it) }

private fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.let { element ->
    if (element is JsonNull) null else element.longOrNull
}

private fun JsonObject.firstLong(names: List<String>): Long? = names.firstNotNullOfOrNull { long(it) }

private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.let { element ->
    if (element is JsonNull) null else element.intOrNull
}

private fun JsonObject.boolean(name: String): Boolean? = (this[name] as? JsonPrimitive)?.let { element ->
    if (element is JsonNull) {
        null
    } else {
        element.booleanOrNull ?: when (element.contentOrNull?.lowercase()) {
            "1", "yes" -> true
            "0", "no" -> false
            else -> null
        }
    }
}

private fun JsonObject.array(name: String): JsonArray =
    this[name] as? JsonArray ?: JsonArray(emptyList())

private fun JsonObject.firstArray(names: List<String>): JsonArray? =
    names.firstNotNullOfOrNull { this[it] as? JsonArray }

private fun JsonElement.scalarString(): String? =
    if (this is JsonNull) null else (this as? JsonPrimitive)?.contentOrNull

/** Bounded scalar values of a record, kept so a full replacement can echo unchanged fields. */
private fun JsonObject.scalarFields(): Map<String, String> = entries.asSequence()
    .mapNotNull { (name, value) ->
        if (value is JsonNull) return@mapNotNull null
        val primitive = value as? JsonPrimitive ?: return@mapNotNull null
        primitive.contentOrNull?.takeIf { it.length <= MAX_ECHOED_SCALAR_CHARS }?.let { name to it }
    }
    .take(MAX_ECHOED_SCALAR_FIELDS)
    .toMap()

private fun String.normalizeDeckColor(): String? {
    val normalized = trim().removePrefix("#")
    return normalized.takeIf { value ->
        value.length in setOf(3, 6, 8) && value.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
    }?.lowercase()
}

private fun Long.requireDeckId(): Long {
    require(this > 0L) { "The board resource id is invalid." }
    return this
}

/** Marks a card whose record reports completion without a timestamp. */
internal const val COMPLETED_WITHOUT_TIMESTAMP = "completed"

private val deckJson = Json {
    ignoreUnknownKeys = true
    isLenient = false
}
private val TITLE_FIELDS = listOf("title", "name")
private val DESCRIPTION_FIELDS = listOf("description", "details", "body", "content")
private val ORDER_FIELDS = listOf("order", "position", "sortOrder", "sort", "index")
private val DUE_FIELDS = listOf("duedate", "dueDate", "due", "dueAt", "deadline")
private val START_FIELDS = listOf("startdate", "startDate", "startAt", "start")
private val COMPLETION_FIELDS = listOf("done", "completedAt", "completed", "isDone")
private val MODIFIED_FIELDS = listOf("lastModified", "modified", "updatedAt")
private val LABEL_FIELDS = listOf("labels", "tags")
private val ASSIGNEE_FIELDS = listOf("assignedUsers", "assignees", "members")
private val SHARE_FIELDS = listOf("acl", "shares")
private val BOARD_REFERENCE_FIELDS = listOf("boardId", "projectId")
private val LANE_REFERENCE_FIELDS = listOf("stackId", "listId", "columnId", "laneId", "stageId")
private val CARD_COLLECTION_FIELDS = listOf("cards", "tasks", "tickets", "issues")
private const val DECK_READ_RESPONSE_BYTES = 8L * 1024L * 1024L
private const val MAX_ECHOED_SCALAR_FIELDS = 64
private const val MAX_ECHOED_SCALAR_CHARS = 4_096
