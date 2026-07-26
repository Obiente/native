package dev.obiente.nextcloudnative.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

data class DeckCapabilities(
    val appVersion: String?,
    val apiVersions: List<String>,
    val canCreateBoards: Boolean,
)

data class DeckApiVersion(val value: String) : Comparable<DeckApiVersion> {
    init {
        require(value in SUPPORTED_DECK_API_VERSIONS) { "The Deck API version is unsupported." }
    }

    override fun compareTo(other: DeckApiVersion): Int =
        SUPPORTED_DECK_API_VERSIONS.indexOf(other.value).compareTo(
            SUPPORTED_DECK_API_VERSIONS.indexOf(value),
        )
}

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
)

sealed interface DeckWorkspaceState {
    data object Loading : DeckWorkspaceState
    data class BoardPicker(
        val boards: List<DeckBoard>,
        val canCreateBoards: Boolean,
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

data class DeckReadRoutePlan(
    val version: DeckApiVersion,
    val apiRoot: String = "/index.php/apps/deck/api/v${version.value}",
) {
    fun boards(details: Boolean = false): NextcloudApiRequest = deckGet(
        path = "$apiRoot/boards",
        query = if (details) mapOf("details" to "true") else emptyMap(),
    )

    fun board(boardId: Long): NextcloudApiRequest = deckGet("$apiRoot/boards/${boardId.requireDeckId()}")

    fun stacks(boardId: Long): NextcloudApiRequest =
        deckGet("$apiRoot/boards/${boardId.requireDeckId()}/stacks")

    fun card(boardId: Long, stackId: Long, cardId: Long): NextcloudApiRequest = deckGet(
        "$apiRoot/boards/${boardId.requireDeckId()}/stacks/${stackId.requireDeckId()}/cards/" +
            cardId.requireDeckId(),
    )

    private fun deckGet(
        path: String,
        query: Map<String, String> = emptyMap(),
    ): NextcloudApiRequest = NextcloudApiRequest(
        method = NextcloudApiMethod.GET,
        relativePath = path,
        queryParameters = query,
        ocsApiRequest = true,
        maximumResponseBytes = DECK_READ_RESPONSE_BYTES,
    )
}

data class DeckReadNegotiation(
    val candidates: List<DeckReadRoutePlan>,
    val capabilityBacked: Boolean,
) {
    init {
        require(candidates.isNotEmpty()) { "At least one Deck read route is required." }
    }

    /**
     * Only a route/version mismatch advances to the next verified read version. Authentication,
     * permission, throttling, and server failures are surfaced without masking the real problem.
     */
    fun nextAfter(current: DeckReadRoutePlan, status: Int): DeckReadRoutePlan? {
        if (status !in DECK_VERSION_MISMATCH_STATUSES) return null
        val index = candidates.indexOf(current)
        return candidates.getOrNull(index + 1)
    }
}

fun negotiateDeckReadRoutes(capabilities: DeckCapabilities?): DeckReadNegotiation {
    val advertised = capabilities?.apiVersions.orEmpty().mapNotNull { raw ->
        raw.takeIf { it in SUPPORTED_DECK_API_VERSIONS }?.let(::DeckApiVersion)
    }.distinct().sortedDescending()
    val versions = advertised.ifEmpty { SUPPORTED_DECK_API_VERSIONS.map(::DeckApiVersion) }
    return DeckReadNegotiation(
        candidates = versions.map(::DeckReadRoutePlan),
        capabilityBacked = advertised.isNotEmpty(),
    )
}

fun parseDeckCapabilities(response: NextcloudApiResponse): DeckCapabilities? {
    require(response.status in 200..299) { "Deck capability discovery failed (HTTP ${response.status})." }
    val root = response.deckJsonObject("Deck capability discovery")
    val deck = root.pathObject("ocs", "data", "capabilities", "deck")
        ?: root.pathObject("capabilities", "deck")
        ?: return null
    return DeckCapabilities(
        appVersion = deck.string("version"),
        apiVersions = (deck["apiVersions"] as? JsonArray).orEmpty().mapNotNull { it.scalarString() },
        canCreateBoards = deck.boolean("canCreateBoards") ?: false,
    )
}

fun parseDeckBoards(response: NextcloudApiResponse): List<DeckBoard> {
    require(response.status in 200..299) { "Deck boards failed to load (HTTP ${response.status})." }
    return response.deckPayload("Deck boards").requireArray("Deck boards").map { element ->
        element.requireObject("Deck board").toDeckBoard()
    }.filterNot(DeckBoard::archived).sortedBy { it.title.lowercase() }
}

fun parseDeckBoard(response: NextcloudApiResponse): DeckBoard {
    require(response.status in 200..299) { "The Deck board failed to load (HTTP ${response.status})." }
    val board = response.deckPayload("Deck board").requireObject("Deck board").toDeckBoard()
    return if (board.etag == null && response.etag != null) {
        board.copy(etag = response.etag)
    } else {
        board
    }
}

fun parseDeckStacks(
    boardId: Long,
    response: NextcloudApiResponse,
): List<DeckStack> {
    require(response.status in 200..299) { "Deck stacks failed to load (HTTP ${response.status})." }
    val safeBoardId = boardId.requireDeckId()
    return response.deckPayload("Deck stacks").requireArray("Deck stacks").map { element ->
        val value = element.requireObject("Deck stack")
        val parsedBoardId = value.long("boardId") ?: safeBoardId
        require(parsedBoardId == safeBoardId) { "A Deck stack belongs to an unexpected board." }
        val stackId = value.requirePositiveId("id", "Deck stack")
        DeckStack(
            id = stackId,
            boardId = safeBoardId,
            title = value.requireString("title", "Deck stack"),
            order = value.long("order") ?: 0L,
            doneColumn = value.boolean("isDoneColumn") ?: false,
            cards = value.array("cards").map { card ->
                card.requireObject("Deck card").toDeckCard(safeBoardId, stackId)
            }.filterNot(DeckCard::archived).sortedBy(DeckCard::order),
            lastModified = value.long("lastModified"),
            etag = value.string("ETag") ?: value.string("etag"),
        )
    }.sortedBy(DeckStack::order)
}

fun parseDeckCard(
    boardId: Long,
    stackId: Long,
    response: NextcloudApiResponse,
): DeckCard {
    require(response.status in 200..299) { "The Deck card failed to load (HTTP ${response.status})." }
    return response.deckPayload("Deck card").requireObject("Deck card")
        .toDeckCard(boardId.requireDeckId(), stackId.requireDeckId())
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
        "Deck stacks cannot be attached to the wrong board."
    }
    return DeckWorkspaceState.Board(
        board = selected,
        stacks = loadedStacks,
        selectedCardId = selectedCardId,
    )
}

private fun JsonObject.toDeckCard(boardId: Long, expectedStackId: Long): DeckCard {
    val stackId = long("stackId") ?: expectedStackId
    require(stackId == expectedStackId) { "A Deck card belongs to an unexpected stack." }
    return DeckCard(
        id = requirePositiveId("id", "Deck card"),
        boardId = boardId,
        stackId = stackId,
        title = requireString("title", "Deck card"),
        descriptionMarkdown = string("description"),
        ownerId = string("owner")
            ?: (this["owner"] as? JsonObject)?.let { owner ->
                owner.string("uid") ?: owner.string("primaryKey") ?: owner.string("id")
            },
        color = string("color")?.normalizeDeckColor(),
        order = long("order") ?: 0L,
        dueAt = string("duedate"),
        startAt = string("startdate"),
        completedAt = string("done"),
        archived = boolean("archived") ?: false,
        overdue = (long("overdue") ?: 0L) > 0L,
        labels = array("labels").mapNotNull(JsonElement::toDeckLabel),
        assignees = array("assignedUsers").mapNotNull { element ->
            val assignment = element as? JsonObject ?: return@mapNotNull null
            (assignment["participant"] as? JsonObject)?.toDeckUser()
                ?: (assignment["user"] as? JsonObject)?.toDeckUser()
                ?: assignment.toDeckUser()
        },
        attachmentCount = int("attachmentCount") ?: array("attachments").size,
        unreadCommentCount = int("commentsUnread") ?: 0,
        etag = string("ETag") ?: string("etag"),
    )
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
    return DeckBoard(
        id = requirePositiveId("id", "Deck board"),
        title = requireString("title", "Deck board"),
        color = string("color")?.normalizeDeckColor(),
        archived = boolean("archived") ?: false,
        owner = owner,
        labels = array("labels").mapNotNull(JsonElement::toDeckLabel),
        users = users,
        permissions = DeckPermissions(
            canRead = permissions?.boolean("PERMISSION_READ") ?: true,
            canEdit = permissions?.boolean("PERMISSION_EDIT") ?: false,
            canManage = permissions?.boolean("PERMISSION_MANAGE") ?: false,
            canShare = permissions?.boolean("PERMISSION_SHARE") ?: false,
        ),
        shared = (long("shared") ?: 0L) > 0L,
        lastModified = long("lastModified"),
        etag = string("ETag") ?: string("etag"),
    )
}

private fun JsonElement.toDeckLabel(): DeckLabel? {
    val value = this as? JsonObject ?: return null
    val id = value.long("id")?.takeIf { it > 0L } ?: return null
    val title = value.string("title")?.takeIf(String::isNotBlank) ?: return null
    return DeckLabel(id, title, value.string("color")?.normalizeDeckColor())
}

private fun JsonObject.toDeckUser(): DeckUser? {
    val id = string("uid") ?: string("primaryKey") ?: string("id") ?: return null
    val display = string("displayname") ?: string("displayName") ?: id
    return DeckUser(id = id, displayName = display, type = int("type"))
}

private fun NextcloudApiResponse.deckJsonObject(label: String): JsonObject {
    require(body.size <= DECK_READ_RESPONSE_BYTES) { "$label returned too much data." }
    return runCatching { deckJson.parseToJsonElement(body.decodeToString()) as? JsonObject }
        .getOrNull() ?: error("$label returned invalid JSON.")
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

private fun JsonObject.requireString(name: String, label: String): String =
    string(name)?.trim()?.takeIf(String::isNotBlank) ?: error("$label has no valid $name.")

private fun JsonObject.string(name: String): String? =
    this[name]?.scalarString()?.takeIf(String::isNotBlank)

private fun JsonObject.long(name: String): Long? = this[name]?.let { element ->
    if (element is JsonNull) null else element.jsonPrimitive.longOrNull
}

private fun JsonObject.int(name: String): Int? = this[name]?.let { element ->
    if (element is JsonNull) null else element.jsonPrimitive.intOrNull
}

private fun JsonObject.boolean(name: String): Boolean? = this[name]?.let { element ->
    if (element is JsonNull) null
    else element.jsonPrimitive.booleanOrNull ?: when (element.jsonPrimitive.contentOrNull?.lowercase()) {
        "1", "yes" -> true
        "0", "no" -> false
        else -> null
    }
}

private fun JsonObject.array(name: String): JsonArray =
    this[name] as? JsonArray ?: JsonArray(emptyList())

private fun JsonElement.scalarString(): String? =
    if (this is JsonNull) null else runCatching { jsonPrimitive.contentOrNull }.getOrNull()

private fun JsonObject.pathObject(vararg path: String): JsonObject? {
    var cursor: JsonObject = this
    path.forEachIndexed { index, name ->
        val next = cursor[name] as? JsonObject ?: return null
        if (index == path.lastIndex) return next
        cursor = next
    }
    return cursor
}

private fun String.normalizeDeckColor(): String? {
    val normalized = trim().removePrefix("#")
    return normalized.takeIf { value ->
        value.length in setOf(3, 6, 8) && value.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
    }?.lowercase()
}

private fun Long.requireDeckId(): Long {
    require(this > 0L) { "The Deck resource id is invalid." }
    return this
}

private val deckJson = Json {
    ignoreUnknownKeys = true
    isLenient = false
}
private val SUPPORTED_DECK_API_VERSIONS = listOf("1.1", "1.0")
private val DECK_VERSION_MISMATCH_STATUSES = setOf(400, 404, 405, 406)
private const val DECK_READ_RESPONSE_BYTES = 8L * 1024L * 1024L
