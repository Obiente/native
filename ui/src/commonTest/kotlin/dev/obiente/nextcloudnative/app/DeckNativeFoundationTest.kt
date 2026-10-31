package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeckNativeFoundationTest {
    @Test
    fun `lists and cards parse by field roles from any board app`() {
        val stacks = parseDeckStacks(
            7,
            NextcloudApiResponse(
                200,
                """
                [{"id":3,"name":"Backlog","position":2,"boardId":7,"tasks":[
                  {"id":9,"name":"Plan","listId":3,"position":1,"dueDate":"2026-08-01T10:00:00Z","completed":true}
                ]},
                 {"id":4,"title":"Later","order":5}]
                """.trimIndent().encodeToByteArray(),
                "application/json",
                null,
            ),
        )

        assertEquals(listOf("Backlog", "Later"), stacks.map(DeckStack::title))
        val card = stacks.first().cards.single()
        assertEquals("Plan", card.title)
        assertEquals(3L, card.stackId)
        assertEquals("2026-08-01T10:00:00Z", card.dueAt)
        assertEquals(COMPLETED_WITHOUT_TIMESTAMP, card.completedAt)
        assertTrue(stacks.first().cardsEmbedded)
        assertFalse(stacks.last().cardsEmbedded)
    }

    @Test
    fun `a board record without permission evidence leaves enforcement to the server`() {
        val board = parseDeckBoard(
            NextcloudApiResponse(200, """{"id":7,"name":"Roadmap"}""".encodeToByteArray(), "application/json", null),
        )

        assertEquals("Roadmap", board.title)
        assertTrue(board.permissions.canEdit && board.permissions.canManage && board.permissions.canShare)
        assertEquals(mapOf("id" to "7", "name" to "Roadmap"), board.rawFields)
    }

    @Test
    fun `one board remains a board picker until the user selects it`() {
        val boards = parseDeckBoards(
            jsonResponse(
                """
                [{
                  "id": 7,
                  "title": "Product",
                  "color": "A970FF",
                  "archived": false,
                  "shared": 0,
                  "lastModified": 123,
                  "permissions": {
                    "PERMISSION_READ": true,
                    "PERMISSION_EDIT": true,
                    "PERMISSION_MANAGE": false,
                    "PERMISSION_SHARE": false
                  },
                  "owner": {"uid": "owner", "displayname": "Owner"},
                  "users": [
                    {"participant": {"uid": "member", "displayname": "Member"}},
                    {"uid": "owner", "displayname": "Owner"}
                  ],
                  "labels": [{"id": 4, "title": "Bug", "color": "ff0000"}]
                }]
                """.trimIndent(),
            ),
        )

        val state = deckBoardState(
            boards = boards,
            selectedBoardId = null,
            stacks = null,
            canCreateBoards = true,
        )

        val picker = assertIs<DeckWorkspaceState.BoardPicker>(state)
        assertEquals("Product", picker.boards.single().title)
        assertEquals("a970ff", picker.boards.single().color)
        assertTrue(picker.boards.single().permissions.canEdit)
        assertEquals(listOf("Owner", "Member"), picker.boards.single().users.map(DeckUser::displayName))
    }

    @Test
    fun `authoritative board detail preserves revision and current permissions`() {
        val board = parseDeckBoard(
            NextcloudApiResponse(
                status = 200,
                body = """
                    {
                      "id": 7,
                      "title": "Current product",
                      "color": "A970FF",
                      "archived": false,
                      "shared": 0,
                      "lastModified": 456,
                      "permissions": {
                        "PERMISSION_READ": true,
                        "PERMISSION_EDIT": false,
                        "PERMISSION_MANAGE": false,
                        "PERMISSION_SHARE": false
                      },
                      "labels": [],
                      "users": []
                    }
                """.trimIndent().encodeToByteArray(),
                contentType = "application/json",
                etag = "board-revision-2",
            ),
        )

        assertEquals(7L, board.id)
        assertEquals("board-revision-2", board.etag)
        assertFalse(board.permissions.canEdit)
        assertFalse(board.permissions.canManage)
    }

    @Test
    fun `stack response becomes ordered native lanes and cards`() {
        val stacks = parseDeckStacks(
            boardId = 7,
            response = jsonResponse(
                """
                [
                  {
                    "id": 11,
                    "boardId": 7,
                    "title": "Doing",
                    "order": 20,
                    "cards": [{
                      "id": 42,
                      "stackId": 11,
                      "title": "Native board",
                      "description": "Use **semantic** cards",
                      "owner": "card-owner",
                      "color": "A970FF",
                      "order": 2,
                      "archived": false,
                      "done": null,
                      "duedate": "2026-07-26T10:00:00+00:00",
                      "labels": [{"id": 4, "title": "UX", "color": "a970ff"}],
                      "assignedUsers": [{
                        "participant": {"uid": "person", "displayname": "Person"}
                      }],
                      "attachmentCount": 3,
                      "commentsUnread": 2,
                      "ETag": "card-etag"
                    }]
                  },
                  {
                    "id": 10,
                    "boardId": 7,
                    "title": "To do",
                    "order": 10,
                    "isDoneColumn": false,
                    "cards": []
                  }
                ]
                """.trimIndent(),
            ),
        )

        assertEquals(listOf("To do", "Doing"), stacks.map(DeckStack::title))
        val card = stacks.last().cards.single()
        assertEquals(42L, card.id)
        assertEquals(7L, card.boardId)
        assertEquals(11L, card.stackId)
        assertEquals("Use **semantic** cards", card.descriptionMarkdown)
        assertEquals("card-owner", card.ownerId)
        assertEquals("a970ff", card.color)
        assertEquals("UX", card.labels.single().title)
        assertEquals("Person", card.assignees.single().displayName)
        assertEquals(3, card.attachmentCount)
        assertEquals(2, card.unreadCommentCount)
    }

    @Test
    fun `authoritative card detail uses the response header etag when the body omits it`() {
        val card = parseDeckCard(
            boardId = 7,
            stackId = 11,
            response = NextcloudApiResponse(
                status = 200,
                body = """
                    {
                      "id": 42,
                      "stackId": 11,
                      "title": "Current card",
                      "owner": "card-owner",
                      "order": 2,
                      "archived": false
                    }
                """.trimIndent().encodeToByteArray(),
                contentType = "application/json",
                etag = "\"card-revision-2\"",
            ),
        )

        assertEquals("\"card-revision-2\"", card.etag)
    }

    @Test
    fun `refresh error presents cached board without enabling stale mutations`() {
        val board = parseDeckBoard(
            jsonResponse(
                """
                {
                  "id": 7,
                  "title": "Product",
                  "color": "a970ff",
                  "archived": false,
                  "shared": 0,
                  "permissions": {
                    "PERMISSION_READ": true,
                    "PERMISSION_EDIT": true,
                    "PERMISSION_MANAGE": true,
                    "PERMISSION_SHARE": true
                  },
                  "labels": [],
                  "users": []
                }
                """.trimIndent(),
            ),
        )
        val cached = DeckWorkspaceState.Board(board, stacks = emptyList())
        val error = DeckWorkspaceState.Error(
            title = "Board could not load",
            message = "The server is unavailable.",
            cachedState = cached,
        )

        val stalePresentation = resolveDeckWorkspacePresentation(error, board)
        val loadedPresentation = resolveDeckWorkspacePresentation(cached, board)

        assertEquals(cached, stalePresentation.visibleState)
        assertEquals(board, stalePresentation.visibleBoard)
        assertEquals(error, stalePresentation.cachedError)
        assertFalse(stalePresentation.mutationsEnabled)
        assertTrue(loadedPresentation.mutationsEnabled)
    }

    @Test
    fun `wrong nested resource identity is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            parseDeckStacks(
                boardId = 7,
                response = jsonResponse(
                    """
                    [{"id": 11, "boardId": 99, "title": "Wrong", "order": 1}]
                    """.trimIndent(),
                ),
            )
        }
    }

    private fun jsonResponse(json: String, status: Int = 200) = NextcloudApiResponse(
        status = status,
        body = json.encodeToByteArray(),
        contentType = "application/json",
        etag = null,
    )
}
