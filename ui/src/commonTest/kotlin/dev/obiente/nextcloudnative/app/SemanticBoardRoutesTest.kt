package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.runtime.BoardOperation
import dev.obiente.nextcloudnative.nativeui.runtime.BoardOperationUnavailableException
import dev.obiente.nextcloudnative.nativeui.runtime.boardRoutes
import dev.obiente.nextcloudnative.nativeui.runtime.forApp
import dev.obiente.nextcloudnative.nativeui.runtime.resolveSemanticBoardContract
import dev.obiente.nextcloudnative.nativeui.runtime.semanticDescriptor
import dev.obiente.nextcloudnative.nativeui.runtime.write
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SemanticBoardRoutesTest {
    private val board = DeckBoard(
        id = 7,
        title = "Launch",
        color = "0082c9",
        archived = false,
        owner = DeckUser("owner", "Owner"),
        labels = listOf(DeckLabel(3, "Urgent", "ff0000")),
        users = listOf(DeckUser("owner", "Owner")),
        permissions = DeckPermissions(canRead = true, canEdit = true, canManage = true, canShare = true),
        shared = false,
        lastModified = null,
        etag = "\"board-1\"",
    )
    private val card = DeckCard(
        id = 42, boardId = 7, stackId = 10, title = "Ship", descriptionMarkdown = "Notes", ownerId = "owner",
        color = null, order = 1, dueAt = null, startAt = null, completedAt = null, archived = false,
        overdue = false, labels = emptyList(), assignees = emptyList(), attachmentCount = 0,
        unreadCommentCount = 0, etag = "\"card-1\"", rawFields = mapOf("type" to "plain", "title" to "Ship"),
    )

    @Test
    fun `requests for an unknown board app come from its own contract paths`() {
        val routes = routes("kanban_x")

        assertEquals("/apps/kanban_x/api/v2/boards", routes.boards().relativePath)
        assertEquals("/apps/kanban_x/api/v2/boards/7/lists", routes.stacks(7).relativePath)
        val create = routes.createCard(access(), DeckStackContext(DeckBoardId(7), 10), DeckCardDraft("New", order = 4))
        assertEquals(NextcloudApiMethod.POST, create.method)
        assertEquals("/apps/kanban_x/api/v2/boards/7/lists/10/cards", create.relativePath)
        assertEquals(setOf("title", "order", "description"), body(create).keys)
    }

    @Test
    fun `a cross-list move addresses the card and names only the destination list in the body`() {
        val routes = routes("kanban_x")
        val move = DeckCardMove(
            source = DeckCardContext(DeckStackContext(DeckBoardId(7), 10), 42),
            destinationStack = DeckStackContext(DeckBoardId(7), 11),
            order = 2,
        )

        val request = routes.moveCard(access(), card, move)

        assertEquals(NextcloudApiMethod.PUT, request.method)
        assertEquals("/apps/kanban_x/api/v2/cards/42/reorder", request.relativePath)
        assertEquals<Map<String, Any>>(mapOf("listId" to JsonPrimitive(11), "order" to JsonPrimitive(2)), body(request))
    }

    @Test
    fun `a full replacement keeps undeclared required fields at their server value`() {
        val request = routes("kanban_x").updateCard(
            access(),
            DeckCardContext(DeckStackContext(DeckBoardId(7), 10), 42),
            DeckCardUpdate(card, "Ship it", 1, "Notes", null, null, archived = false, completedAt = null),
        )

        val sent = body(request)
        assertEquals("/apps/kanban_x/api/v2/boards/7/lists/10/cards/42", request.relativePath)
        assertEquals(JsonPrimitive("plain"), sent["type"])
        assertEquals(JsonPrimitive("owner"), sent["owner"])
        assertEquals(JsonPrimitive("Ship it"), sent["title"])
        assertFalse("duedate" in sent)
    }

    @Test
    fun `an update fails before sending when a required field has no source`() {
        val unknownType = card.copy(rawFields = emptyMap())

        assertFailsWith<IllegalArgumentException> {
            routes("kanban_x").updateCard(
                access(),
                DeckCardContext(DeckStackContext(DeckBoardId(7), 10), 42),
                DeckCardUpdate(unknownType, "Ship", 1, "", null, null, archived = false, completedAt = null),
            )
        }
    }

    @Test
    fun `an undescribed operation is unavailable and never builds a request`() {
        val routes = routes("kanban_x", includeMove = false)
        val move = DeckCardMove(
            DeckCardContext(DeckStackContext(DeckBoardId(7), 10), 42),
            DeckStackContext(DeckBoardId(7), 11),
            0,
        )

        assertFalse(routes.supports(BoardOperation.MoveCard))
        assertFailsWith<BoardOperationUnavailableException> { routes.moveCard(access(), card, move) }
    }

    @Test
    fun `permission evidence still blocks a described write`() {
        val readOnly = DeckBoardAccess.from(board.copy(permissions = board.permissions.copy(canEdit = false)))

        assertFailsWith<IllegalArgumentException> { routes("kanban_x").archiveCard(readOnly, card) }
    }

    @Test
    fun `labels and assignees bind to the active card`() {
        val routes = routes("kanban_x")

        assertTrue(routes.assignsLabels())
        assertTrue(routes.assignsUsers())
        assertFalse(routes("kanban_x", includeMove = false).supports(BoardOperation.MoveCard))
        assertEquals("/apps/kanban_x/api/v2/cards/42/assignLabel", routes.assignLabel(access(), card, 3).relativePath)
    }

    @Test
    fun `sharee search uses the sharing app id as the item type`() {
        val request = deckShareeSearchRequest("kanban_x", 7, "ali")

        assertEquals("kanban_x", request.queryParameters["itemType"])
        assertFailsWith<IllegalArgumentException> { deckShareeSearchRequest("bad id", 7, "ali") }
    }

    @Test
    fun `a contract-declared single value fills a required upload part`() {
        val routes = boardRoutes().forApp("kanban_x") + write(
            HttpMethod.POST,
            "/apps/kanban_x/api/v2/cards/{cardId}/attachments",
            mapOf("type" to "enum:file", "file" to "binary"),
            setOf("type", "file"),
        ).copy(contentType = "multipart/form-data")
        val contract = requireNotNull(
            resolveSemanticBoardContract(semanticDescriptor("kanban_x", "42.0.0", routes), writesAuthorized = true),
        )
        val upload = contract.binding(BoardOperation.UploadAttachment)

        assertEquals("file", upload.fileField)
        assertEquals(mapOf("type" to "file"), upload.fixedBodyValues)
        assertNull(routes("kanban_x").contract.operations[BoardOperation.UploadAttachment])
    }

    private fun access() = DeckBoardAccess.from(board)

    private fun routes(appId: String, includeMove: Boolean = true) = SemanticBoardRoutes(
        requireNotNull(
            resolveSemanticBoardContract(
                semanticDescriptor(appId, "42.0.0", boardRoutes(includeMove = includeMove).forApp(appId)),
                writesAuthorized = true,
            ),
        ),
    )

    private fun body(request: NextcloudApiRequest): JsonObject =
        Json.parseToJsonElement(requireNotNull(request.body).decodeToString()).jsonObject
}
