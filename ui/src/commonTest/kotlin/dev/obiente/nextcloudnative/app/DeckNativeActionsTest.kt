package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.runtime.boardRoutes
import dev.obiente.nextcloudnative.nativeui.runtime.forApp
import dev.obiente.nextcloudnative.nativeui.runtime.resolveSemanticBoardContract
import dev.obiente.nextcloudnative.nativeui.runtime.semanticDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Typed board safety rules: identities, permissions, comment authorship, archived boards, and
 * response parsers. Requests come from a synthetic board-shaped contract, not a known app route.
 */
class DeckNativeActionsTest {
    private val routes = SemanticBoardRoutes(
        requireNotNull(
            resolveSemanticBoardContract(
                semanticDescriptor(
                    "planner",
                    "9.0.0",
                    boardRoutes(lane = "stacks", laneId = "stackId").forApp("planner"),
                ),
                writesAuthorized = true,
            ),
        ),
    )
    private val boardId = DeckBoardId(7)
    private val sourceStack = DeckStackContext(boardId, stackId = 11)
    private val destinationStack = DeckStackContext(boardId, stackId = 12)
    private val card = DeckCardContext(sourceStack, cardId = 42)
    private val activeCard = editableCard()
    private val access = DeckBoardAccess.from(editableBoard())
    private val base = "/apps/planner/api/v2"

    @Test
    fun `board stack and card writes use typed nested parent paths`() {
        val createStack = routes.createStack(access, DeckStackDraft("Doing", 2))
        val createCard = routes.createCard(access, sourceStack, DeckCardDraft("Write tests", order = 3))
        val updateStack = routes.updateStack(access, sourceStack, DeckStackDraft("Renamed", 4))

        assertEquals("$base/boards/7/stacks", createStack.relativePath)
        assertEquals("$base/boards/7/stacks/11/cards", createCard.relativePath)
        assertEquals("$base/boards/7/stacks/11", updateStack.relativePath)
        assertEquals("Write tests", createCard.jsonBody().string("title"))
        assertEquals(4L, updateStack.jsonBody().getValue("order").jsonPrimitive.content.toLong())
    }

    @Test
    fun `move keeps source context in path and destination stack only in body`() {
        val request = routes.moveCard(access, activeCard, DeckCardMove(card, destinationStack, order = 1))

        assertEquals("$base/cards/42/reorder", request.relativePath)
        assertEquals("12", request.jsonBody().string("stackId"))
        assertEquals("1", request.jsonBody().string("order"))
    }

    @Test
    fun `move rejects a destination stack from another board`() {
        assertFailsWith<IllegalArgumentException> {
            DeckCardMove(card, DeckStackContext(DeckBoardId(8), 12), order = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            routes.moveCard(access, activeCard.copy(id = 41), DeckCardMove(card, destinationStack, order = 0))
        }
    }

    @Test
    fun `labels and assignees bind to the active card and board members`() {
        assertEquals("$base/cards/42/assignLabel", routes.assignLabel(access, activeCard, 5).relativePath)
        assertEquals("5", routes.removeLabel(access, activeCard, 5).jsonBody().string("labelId"))
        assertEquals("participant", routes.assignUser(access, activeCard, "participant").jsonBody().string("userId"))
        assertEquals("$base/cards/42/unassignUser", routes.unassignUser(access, activeCard, "participant").relativePath)
    }

    @Test
    fun `destructive and archive actions stay bound to their typed resources`() {
        assertEquals(NextcloudApiMethod.DELETE, routes.deleteCard(access, activeCard).method)
        assertEquals("$base/boards/7/stacks/11/cards/42", routes.deleteCard(access, activeCard).relativePath)
        assertEquals("$base/boards/7/stacks/11/cards/42/archive", routes.archiveCard(access, activeCard).relativePath)
        assertEquals("$base/boards/7/stacks/11/cards/42/unarchive", routes.unarchiveCard(access, activeCard).relativePath)
        assertEquals("$base/boards/7/stacks/11", routes.deleteStack(access, sourceStack).relativePath)
        assertEquals("$base/boards/7", routes.deleteBoard(access).relativePath)
        assertEquals("$base/boards/7/undo_delete", routes.restoreBoard(access).relativePath)
    }

    @Test
    fun `label management uses the board resource and normalized fields`() {
        val create = routes.createLabel(access, DeckLabelDraft(" Needs review ", "#A970FF"))
        val update = routes.updateLabel(access, 5, DeckLabelDraft("Reviewed", "a970ff"))

        assertEquals("$base/boards/7/labels", create.relativePath)
        assertEquals("Needs review", create.jsonBody().string("title"))
        assertEquals("a970ff", create.jsonBody().string("color"))
        assertEquals("$base/boards/7/labels/5", update.relativePath)
        assertEquals("$base/boards/7/labels/5", routes.deleteLabel(access, 5).relativePath)
        assertFailsWith<IllegalArgumentException> { routes.deleteLabel(access, 99) }
    }

    @Test
    fun `comments use the contract route and bounded pagination`() {
        val list = routes.comments(access, activeCard, limit = 25, offset = 50)
        val create = routes.createComment(access, activeCard, DeckCommentDraft("A useful comment"))

        assertEquals("$base/cards/42/comments", list.relativePath)
        assertEquals(mapOf("limit" to "25", "offset" to "50"), list.queryParameters)
        assertEquals(list.relativePath, create.relativePath)
        assertEquals("A useful comment", create.jsonBody().string("message"))
        assertTrue("parentId" !in create.jsonBody())

        assertFailsWith<IllegalArgumentException> { routes.comments(access, activeCard, limit = 101, offset = 0) }
        assertFailsWith<IllegalArgumentException> { routes.comments(access, activeCard, limit = 20, offset = -1) }
    }

    @Test
    fun `comment mutations require the parsed author and active card`() {
        val comment = comment()
        val update = routes.updateComment(access, activeCard, comment, "reviewer", DeckCommentUpdate("Updated"))
        val delete = routes.deleteComment(access, activeCard, comment, currentUserId = "reviewer")

        assertEquals("$base/cards/42/comments/18", update.relativePath)
        assertEquals(update.relativePath, delete.relativePath)
        assertFailsWith<IllegalArgumentException> {
            routes.updateComment(access, activeCard, comment, "another-user", DeckCommentUpdate("Not allowed"))
        }
        assertFailsWith<IllegalArgumentException> {
            routes.deleteComment(access, activeCard, comment.copy(cardId = 99), currentUserId = "reviewer")
        }
    }

    @Test
    fun `active readers may comment while archived boards reject comment writes`() {
        val readerAccess = DeckBoardAccess.from(editableBoard(permissions = readOnlyPermissions()))
        val archivedAccess = DeckBoardAccess.from(editableBoard(archived = true))

        assertEquals(NextcloudApiMethod.POST, routes.createComment(readerAccess, activeCard, DeckCommentDraft("Reader")).method)
        assertEquals(NextcloudApiMethod.GET, routes.comments(archivedAccess, activeCard, 20, 0).method)
        assertFailsWith<IllegalArgumentException> {
            routes.createComment(archivedAccess, activeCard, DeckCommentDraft("Blocked"))
        }
        assertFailsWith<IllegalArgumentException> {
            routes.deleteComment(archivedAccess, activeCard, comment(), currentUserId = "reviewer")
        }
    }

    @Test
    fun `attachment routes carry the server-reported attachment type`() {
        val attachment = DeckAttachment(9, 42, DeckAttachmentType("file"), "Doc.pdf", null, null, null, null, null)
        val open = routes.openAttachment(access, activeCard, attachment)
        val delete = routes.deleteAttachment(access, activeCard, attachment)

        assertEquals("$base/boards/7/stacks/11/cards/42/attachments/file/9", open.relativePath)
        assertEquals(open.relativePath, delete.relativePath)
        assertEquals(NextcloudApiMethod.DELETE, delete.method)
        assertFailsWith<IllegalArgumentException> {
            routes.openAttachment(access, activeCard, attachment.copy(cardId = 99))
        }
        assertFailsWith<IllegalArgumentException> { DeckAttachmentType("../files") }
    }

    @Test
    fun `attachment open target rejects writes and traversal paths`() {
        assertFailsWith<IllegalArgumentException> { DeckAttachmentOpenTarget(NextcloudApiMethod.POST, "$base/cards") }
        assertFailsWith<IllegalArgumentException> {
            DeckAttachmentOpenTarget(NextcloudApiMethod.GET, "$base/boards/7/stacks/11/cards/42/attachments/../../files")
        }
        assertFailsWith<IllegalArgumentException> { DeckAttachmentOpenTarget(NextcloudApiMethod.GET, "//evil.example") }
    }

    @Test
    fun `comment parser accepts OCS values but rejects a different card`() {
        val comment = parseDeckComment(
            card,
            jsonResponse(
                """
                {
                  "ocs": {
                    "meta": {"statuscode": 200},
                    "data": {
                      "id": "18",
                      "objectId": "42",
                      "message": "Ready for review",
                      "actorId": "reviewer",
                      "actorDisplayName": "Reviewer",
                      "creationDateTime": "2026-07-26T11:00:00+00:00",
                      "mentions": [{
                        "mentionId": "helper",
                        "mentionType": "user",
                        "mentionDisplayName": "Helper"
                      }]
                    }
                  }
                }
                """.trimIndent(),
            ),
        )

        assertEquals(18L, comment.id)
        assertEquals(42L, comment.cardId)
        assertEquals("Reviewer", comment.authorDisplayName)
        assertEquals("Helper", comment.mentions.single().displayName)

        assertFailsWith<IllegalArgumentException> {
            parseDeckComment(
                card,
                jsonResponse("""{"ocs":{"data":{"id":18,"objectId":99,"message":"Wrong"}}}"""),
            )
        }
    }

    @Test
    fun `move parser verifies the moved card reached the typed destination`() {
        val move = DeckCardMove(card, destinationStack, order = 1)
        val positions = parseDeckCardMove(
            move,
            jsonResponse(
                """
                [
                  {"id": 41, "stackId": 11, "order": 0},
                  {"id": 42, "stackId": 12, "order": 1}
                ]
                """.trimIndent(),
            ),
        )

        assertEquals(2, positions.size)
        assertEquals(12L, positions.last().stackId)

        assertFailsWith<IllegalArgumentException> {
            parseDeckCardMove(
                move,
                jsonResponse("""[{"id":42,"stackId":11,"order":1}]"""),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            parseDeckCardMove(
                move,
                jsonResponse("""[{"id":42,"stackId":12,"order":0}]"""),
            )
        }
    }

    @Test
    fun `assignment parser verifies the active card identity`() {
        val assignment = parseDeckAssignment(
            card,
            jsonResponse(
                """
                {
                  "id": 8,
                  "cardId": 42,
                  "participant": {
                    "uid": "participant",
                    "displayname": "Participant"
                  }
                }
                """.trimIndent(),
            ),
        )

        assertEquals(8L, assignment.id)
        assertEquals("Participant", assignment.user.displayName)

        assertFailsWith<IllegalArgumentException> {
            parseDeckAssignment(
                card,
                jsonResponse(
                    """{"id":8,"cardId":99,"participant":{"uid":"participant"}}""",
                ),
            )
        }
    }

    @Test
    fun `attachment parser preserves metadata and rejects the wrong card`() {
        val attachments = parseDeckAttachments(
            card,
            jsonResponse(
                """
                [{
                  "id": 9,
                  "cardId": 42,
                  "type": "file",
                  "data": "document.pdf",
                  "createdBy": "participant",
                  "createdAt": 100,
                  "lastModified": 120,
                  "extendedData": {
                    "filesize": 4096,
                    "mimetype": "application/pdf",
                    "info": {"basename": "Document.pdf"}
                  }
                }]
                """.trimIndent(),
            ),
        )

        val attachment = attachments.single()
        assertEquals("Document.pdf", attachment.name)
        assertEquals(4096L, attachment.byteCount)
        assertEquals("application/pdf", attachment.mimeType)

        assertFailsWith<IllegalArgumentException> {
            parseDeckAttachments(
                card,
                jsonResponse("""[{"id":9,"cardId":99,"type":"file","data":"wrong.txt"}]"""),
            )
        }
    }

    @Test
    fun `mutation receipt requires a matching returned identity and later refresh`() {
        val receipt = parseDeckMutationReceipt(
            jsonResponse("""{"id":42}""", etag = "new-etag"),
            expectedId = 42,
        )

        assertEquals(42L, receipt.returnedId)
        assertEquals("new-etag", receipt.etag)
        assertTrue(receipt.requiresAuthoritativeRefresh)

        assertFailsWith<IllegalArgumentException> {
            parseDeckMutationReceipt(jsonResponse("""{"id":99}"""), expectedId = 42)
        }
    }

    @Test
    fun `comment mutation receipt rejects unsuccessful OCS metadata`() {
        val receipt = parseDeckCommentMutationReceipt(
            jsonResponse(
                """{"ocs":{"meta":{"statuscode":200},"data":{"id":18}}}""",
                etag = "comment-etag",
            ),
        )

        assertEquals(18L, receipt.returnedId)
        assertEquals("comment-etag", receipt.etag)
        assertFailsWith<IllegalArgumentException> {
            parseDeckCommentMutationReceipt(
                jsonResponse(
                    """{"ocs":{"meta":{"statuscode":403},"data":null}}""",
                ),
            )
        }
    }

    @Test
    fun `draft validation rejects invalid values before transport`() {
        assertFailsWith<IllegalArgumentException> { DeckBoardId(0) }
        assertFailsWith<IllegalArgumentException> { DeckBoardDraft("Board", "12345") }
        assertFailsWith<IllegalArgumentException> { DeckCardDraft(title = "Card", dueAt = "not-a-date") }
        assertFailsWith<IllegalArgumentException> { DeckCommentDraft(" ".repeat(10)) }
        assertFailsWith<IllegalArgumentException> { routes.assignLabel(access, activeCard, labelId = -1) }
        assertFailsWith<IllegalArgumentException> { routes.assignLabel(access, activeCard, labelId = 99) }
        assertFailsWith<IllegalArgumentException> { routes.assignUser(access, activeCard, userId = "not-a-board-member") }
        assertFailsWith<IllegalArgumentException> { routes.assignUser(access, activeCard, userId = "participant\u0000other") }
        assertNull(DeckCardDraft("Card").normalizedDueAt)
    }

    @Test
    fun `permission and board identity gates reject unavailable writes`() {
        val readOnly = DeckBoardAccess.from(editableBoard(permissions = readOnlyPermissions()))
        val otherBoard = DeckBoardAccess.from(editableBoard(id = 99))
        val archivedBoard = DeckBoardAccess.from(editableBoard(archived = true))

        assertFailsWith<IllegalArgumentException> { routes.createStack(readOnly, DeckStackDraft("Stack", 0)) }
        assertFailsWith<IllegalArgumentException> { routes.createCard(readOnly, sourceStack, DeckCardDraft("Card")) }
        assertFailsWith<IllegalArgumentException> { routes.createCard(otherBoard, sourceStack, DeckCardDraft("Card")) }
        assertFailsWith<IllegalArgumentException> { routes.createStack(archivedBoard, DeckStackDraft("Stack", 0)) }
        assertEquals(
            NextcloudApiMethod.PUT,
            routes.updateBoard(archivedBoard, editableBoard(archived = true), DeckBoardUpdate("Restored", "a970ff", false)).method,
        )
        assertEquals("$base/cards/42/comments", routes.comments(readOnly, activeCard, 20, 0).relativePath)
    }

    private fun comment() = DeckComment(
        id = 18,
        cardId = 42,
        message = "Original",
        authorId = "reviewer",
        authorDisplayName = "Reviewer",
        createdAt = null,
        mentions = emptyList(),
        replyToId = null,
    )

    private fun readOnlyPermissions() = DeckPermissions(canRead = true, canEdit = false, canManage = false, canShare = false)

    private fun NextcloudApiRequest.jsonBody(): JsonObject {
        val bytes = body ?: error("Expected a JSON body.")
        return Json.parseToJsonElement(bytes.decodeToString()) as JsonObject
    }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private fun jsonResponse(
        json: String,
        status: Int = 200,
        etag: String? = null,
    ) = NextcloudApiResponse(
        status = status,
        body = json.encodeToByteArray(),
        contentType = "application/json",
        etag = etag,
    )

    private fun editableBoard(
        id: Long = 7,
        archived: Boolean = false,
        permissions: DeckPermissions = DeckPermissions(canRead = true, canEdit = true, canManage = true, canShare = true),
    ) = DeckBoard(
        id = id,
        title = "Product",
        color = "a970ff",
        archived = archived,
        owner = DeckUser("owner", "Owner"),
        labels = listOf(DeckLabel(5, "Needs review", "a970ff")),
        users = listOf(DeckUser("participant", "Participant")),
        permissions = permissions,
        shared = false,
        lastModified = null,
        etag = null,
    )

    private fun editableCard(
        id: Long = 42,
        boardId: Long = 7,
        stackId: Long = 11,
    ) = DeckCard(
        id = id,
        boardId = boardId,
        stackId = stackId,
        title = "Original",
        descriptionMarkdown = "Original details",
        ownerId = "card-owner",
        color = "a970ff",
        order = 10,
        dueAt = null,
        startAt = null,
        completedAt = null,
        archived = false,
        overdue = false,
        labels = emptyList(),
        assignees = emptyList(),
        attachmentCount = 0,
        unreadCommentCount = 0,
        etag = "card-etag",
        rawFields = mapOf("type" to "plain"),
    )
}
