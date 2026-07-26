package dev.obiente.nextcloudnative.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeckNativeActionsTest {
    private val routes = DeckWriteRoutePlan(DeckApiVersion("1.1"))
    private val boardId = DeckBoardId(7)
    private val sourceStack = DeckStackContext(boardId, stackId = 11)
    private val destinationStack = DeckStackContext(boardId, stackId = 12)
    private val card = DeckCardContext(sourceStack, cardId = 42)
    private val access = DeckBoardAccess.from(editableBoard())
    private val capabilities = DeckCapabilities(
        appVersion = "1.18.2",
        apiVersions = listOf("1.1"),
        canCreateBoards = true,
    )

    @Test
    fun `board stack and card writes use typed nested parent paths`() {
        val createBoard = routes.createBoard(
            capabilities,
            DeckBoardDraft(title = "  Product  ", color = "#A970FF"),
        )
        val createStack = routes.createStack(access, DeckStackDraft(title = "Ready", order = 2))
        val createCard = routes.createCard(
            access,
            sourceStack,
            DeckCardDraft(
                title = "Native card",
                descriptionMarkdown = "A **useful** description",
                dueAt = "2026-08-01T10:30:00Z",
            ),
        )

        assertEquals("/index.php/apps/deck/api/v1.1/boards", createBoard.relativePath)
        assertEquals(NextcloudApiMethod.POST, createBoard.method)
        assertEquals("Product", createBoard.jsonBody().string("title"))
        assertEquals("a970ff", createBoard.jsonBody().string("color"))

        assertEquals("/index.php/apps/deck/api/v1.1/boards/7/stacks", createStack.relativePath)
        assertEquals(2L, createStack.jsonBody().long("order"))

        assertEquals("/index.php/apps/deck/api/v1.1/boards/7/stacks/11/cards", createCard.relativePath)
        assertEquals("plain", createCard.jsonBody().string("type"))
        assertEquals("2026-08-01T10:30:00Z", createCard.jsonBody().string("duedate"))
        assertTrue(createCard.ocsApiRequest)
        assertEquals("application/json", createCard.contentType)
    }

    @Test
    fun `card update sends the complete documented representation`() {
        val request = routes.updateCard(
            access,
            card,
            DeckCardUpdate(
                original = editableCard(),
                title = "Updated",
                order = 15,
                descriptionMarkdown = "Details",
                dueAt = null,
                startAt = "2026-07-30T08:00:00Z",
                archived = false,
                completedAt = null,
            ),
        )
        val body = request.jsonBody()

        assertEquals(NextcloudApiMethod.PUT, request.method)
        assertEquals("/index.php/apps/deck/api/v1.1/boards/7/stacks/11/cards/42", request.relativePath)
        assertEquals("card-owner", body.string("owner"))
        assertEquals(15L, body.long("order"))
        assertFalse(body.boolean("archived"))
        assertEquals(JsonNull, body["duedate"])
        assertEquals(JsonNull, body["done"])
        assertEquals("2026-07-30T08:00:00Z", body.string("startdate"))
        assertEquals("a970ff", body.string("color"))

        assertFailsWith<IllegalArgumentException> {
            routes.updateCard(
                access,
                card,
                DeckCardUpdate(
                    original = editableCard(id = 99),
                    title = "Wrong card",
                    order = 15,
                    descriptionMarkdown = "Details",
                    dueAt = null,
                    startAt = null,
                    archived = false,
                    completedAt = null,
                ),
            )
        }
    }

    @Test
    fun `move keeps source context in path and destination stack only in body`() {
        val move = DeckCardMove(
            source = card,
            destinationStack = destinationStack,
            order = 4,
        )
        val request = routes.moveCard(access, move)

        assertEquals(
            "/index.php/apps/deck/api/v1.1/boards/7/stacks/11/cards/42/reorder",
            request.relativePath,
        )
        assertEquals(12L, request.jsonBody().long("stackId"))
        assertEquals(4L, request.jsonBody().long("order"))
    }

    @Test
    fun `move rejects a destination stack from another board`() {
        assertFailsWith<IllegalArgumentException> {
            DeckCardMove(
                source = card,
                destinationStack = DeckStackContext(DeckBoardId(99), stackId = 12),
                order = 0,
            )
        }
    }

    @Test
    fun `labels and assignees bind to the active card without typed ids in paths`() {
        val assignLabel = routes.assignLabel(access, card, labelId = 5)
        val assignUser = routes.assignUser(access, card, userId = "participant")

        assertEquals(
            "/index.php/apps/deck/api/v1.1/boards/7/stacks/11/cards/42/assignLabel",
            assignLabel.relativePath,
        )
        assertEquals(5L, assignLabel.jsonBody().long("labelId"))
        assertEquals(
            "/index.php/apps/deck/api/v1.1/boards/7/stacks/11/cards/42/assignUser",
            assignUser.relativePath,
        )
        assertEquals("participant", assignUser.jsonBody().string("userId"))
    }

    @Test
    fun `destructive and archive actions stay bound to their typed resources`() {
        val deleteBoard = routes.deleteBoard(access)
        val restoreBoard = routes.restoreBoard(access)
        val deleteStack = routes.deleteStack(access, sourceStack)
        val deleteCard = routes.deleteCard(access, card)
        val archiveCard = routes.archiveCard(access, card)
        val unarchiveCard = routes.unarchiveCard(access, card)

        assertEquals(
            "/index.php/apps/deck/api/v1.1/boards/7",
            deleteBoard.relativePath,
        )
        assertEquals(NextcloudApiMethod.DELETE, deleteBoard.method)
        assertEquals(
            "/index.php/apps/deck/api/v1.1/boards/7/undo_delete",
            restoreBoard.relativePath,
        )
        assertEquals(NextcloudApiMethod.POST, restoreBoard.method)
        assertEquals(
            "/index.php/apps/deck/api/v1.1/boards/7/stacks/11",
            deleteStack.relativePath,
        )
        assertEquals(NextcloudApiMethod.DELETE, deleteStack.method)
        assertEquals(
            "/index.php/apps/deck/api/v1.1/boards/7/stacks/11/cards/42",
            deleteCard.relativePath,
        )
        assertEquals(NextcloudApiMethod.DELETE, deleteCard.method)
        assertEquals("${deleteCard.relativePath}/archive", archiveCard.relativePath)
        assertEquals("${deleteCard.relativePath}/unarchive", unarchiveCard.relativePath)
        assertEquals(NextcloudApiMethod.PUT, archiveCard.method)
        assertEquals(NextcloudApiMethod.PUT, unarchiveCard.method)
    }

    @Test
    fun `label management uses the board resource and normalized fields`() {
        val create = routes.createLabel(
            access,
            DeckLabelDraft(title = "  Needs review  ", color = "#A970FF"),
        )
        val update = routes.updateLabel(
            access,
            labelId = 5,
            draft = DeckLabelDraft(title = "Approved", color = "42b983"),
        )
        val delete = routes.deleteLabel(access, labelId = 5)

        assertEquals(
            "/index.php/apps/deck/api/v1.1/boards/7/labels",
            create.relativePath,
        )
        assertEquals(NextcloudApiMethod.POST, create.method)
        assertEquals("Needs review", create.jsonBody().string("title"))
        assertEquals("a970ff", create.jsonBody().string("color"))
        assertEquals("${create.relativePath}/5", update.relativePath)
        assertEquals(NextcloudApiMethod.PUT, update.method)
        assertEquals("Approved", update.jsonBody().string("title"))
        assertEquals("42b983", update.jsonBody().string("color"))
        assertEquals(update.relativePath, delete.relativePath)
        assertEquals(NextcloudApiMethod.DELETE, delete.method)
    }

    @Test
    fun `comments use the official OCS endpoint and bounded pagination`() {
        val list = routes.comments(access, card, limit = 25, offset = 50)
        val create = routes.createComment(access, card, DeckCommentDraft("A useful comment"))

        assertEquals(
            "/ocs/v2.php/apps/deck/api/v1.1/cards/42/comments",
            list.relativePath,
        )
        assertEquals(mapOf("limit" to "25", "offset" to "50"), list.queryParameters)
        assertEquals(list.relativePath, create.relativePath)
        assertEquals("A useful comment", create.jsonBody().string("message"))
        assertEquals(JsonNull, create.jsonBody()["parentId"])

        assertFailsWith<IllegalArgumentException> { routes.comments(access, card, limit = 101) }
        assertFailsWith<IllegalArgumentException> { routes.comments(access, card, offset = -1) }
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
    fun `comment mutations require the parsed author and active card`() {
        val comment = DeckComment(
            id = 18,
            cardId = 42,
            message = "Original",
            authorId = "reviewer",
            authorDisplayName = "Reviewer",
            createdAt = null,
            mentions = emptyList(),
            replyToId = null,
        )

        val update = routes.updateComment(
            access = access,
            context = card,
            comment = comment,
            currentUserId = "reviewer",
            update = DeckCommentUpdate("Updated"),
        )
        val delete = routes.deleteComment(access, card, comment, currentUserId = "reviewer")

        assertEquals(
            "/ocs/v2.php/apps/deck/api/v1.1/cards/42/comments/18",
            update.relativePath,
        )
        assertEquals(update.relativePath, delete.relativePath)
        assertFailsWith<IllegalArgumentException> {
            routes.updateComment(
                access,
                card,
                comment,
                currentUserId = "another-user",
                update = DeckCommentUpdate("Not allowed"),
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
    fun `attachment routes preserve API version type semantics`() {
        val reference = DeckAttachmentReference(card, DeckAttachmentType.File, attachmentId = 9)
        val open = routes.openAttachment(access, reference)
        val delete = routes.deleteAttachment(access, reference)
        val restore = routes.restoreAttachment(access, reference)
        val upload = routes.attachmentUploadTarget(access, card)

        assertEquals(
            "/index.php/apps/deck/api/v1.1/boards/7/stacks/11/cards/42/attachments/file/9",
            open.relativePath,
        )
        assertEquals(open.relativePath, delete.relativePath)
        assertEquals(NextcloudApiMethod.DELETE, delete.method)
        assertEquals("${open.relativePath}/restore", restore.relativePath)
        assertEquals(NextcloudApiMethod.PUT, restore.method)
        assertEquals(
            "/index.php/apps/deck/api/v1.1/boards/7/stacks/11/cards/42/attachments",
            upload.relativePath,
        )
        assertTrue(upload.requiresRfc7578Multipart)
        assertEquals("type", upload.typeFieldName)
        assertEquals("file", upload.fileFieldName)

        val v10 = DeckWriteRoutePlan(DeckApiVersion("1.0"))
        assertFailsWith<IllegalArgumentException> { v10.openAttachment(access, reference) }
        assertFailsWith<IllegalArgumentException> {
            v10.attachmentUploadTarget(access, card, DeckAttachmentType.File)
        }
    }

    @Test
    fun `attachment open target rejects non Deck and traversal paths`() {
        assertFailsWith<IllegalArgumentException> {
            DeckAttachmentOpenTarget(NextcloudApiMethod.POST, "/index.php/apps/deck/api/v1.1")
        }
        assertFailsWith<IllegalArgumentException> {
            DeckAttachmentOpenTarget(
                NextcloudApiMethod.GET,
                "/index.php/apps/deck/api/v1.1/boards/7/stacks/11/cards/42/attachments/../../files",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            DeckAttachmentOpenTarget(
                NextcloudApiMethod.GET,
                "/ocs/v2.php/apps/files/api/v1/directEditing",
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
        assertFailsWith<IllegalArgumentException> {
            DeckCardDraft(title = "Card", dueAt = "not-a-date")
        }
        assertFailsWith<IllegalArgumentException> { DeckCommentDraft(" ".repeat(10)) }
        assertFailsWith<IllegalArgumentException> { routes.assignLabel(access, card, labelId = -1) }
        assertFailsWith<IllegalArgumentException> { routes.assignLabel(access, card, labelId = 99) }
        assertFailsWith<IllegalArgumentException> {
            routes.assignUser(access, card, userId = "not-a-board-member")
        }
        assertFailsWith<IllegalArgumentException> {
            routes.assignUser(access, card, userId = "participant\u0000other")
        }
        assertNull(DeckCardDraft("Card").normalizedDueAt)
    }

    @Test
    fun `permission and board identity gates reject unavailable writes`() {
        val readOnly = DeckBoardAccess.from(
            editableBoard(
                permissions = DeckPermissions(
                    canRead = true,
                    canEdit = false,
                    canManage = false,
                    canShare = false,
                ),
            ),
        )
        val otherBoard = DeckBoardAccess.from(editableBoard(id = 99))
        val archivedBoard = DeckBoardAccess.from(editableBoard(archived = true))

        assertFailsWith<IllegalArgumentException> {
            routes.createBoard(capabilities.copy(canCreateBoards = false), DeckBoardDraft("Board", "a970ff"))
        }
        assertFailsWith<IllegalArgumentException> {
            routes.createStack(readOnly, DeckStackDraft("Stack", 0))
        }
        assertFailsWith<IllegalArgumentException> {
            routes.createCard(readOnly, sourceStack, DeckCardDraft("Card"))
        }
        assertFailsWith<IllegalArgumentException> {
            routes.createCard(otherBoard, sourceStack, DeckCardDraft("Card"))
        }
        assertFailsWith<IllegalArgumentException> {
            routes.createStack(archivedBoard, DeckStackDraft("Stack", 0))
        }
        assertEquals(
            NextcloudApiMethod.PUT,
            routes.updateBoard(
                archivedBoard,
                DeckBoardUpdate("Restored", "a970ff", archived = false),
            ).method,
        )
        assertEquals(
            "/ocs/v2.php/apps/deck/api/v1.1/cards/42/comments",
            routes.comments(readOnly, card).relativePath,
        )
    }

    private fun NextcloudApiRequest.jsonBody(): JsonObject {
        val bytes = body ?: error("Expected a JSON body.")
        return Json.parseToJsonElement(bytes.decodeToString()) as JsonObject
    }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
    private fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.long
    private fun JsonObject.boolean(key: String): Boolean = getValue(key).jsonPrimitive.boolean

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
        permissions: DeckPermissions = DeckPermissions(
            canRead = true,
            canEdit = true,
            canManage = true,
            canShare = true,
        ),
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
    )
}
