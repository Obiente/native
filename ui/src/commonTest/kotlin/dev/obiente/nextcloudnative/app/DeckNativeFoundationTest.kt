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
    fun `advertised API versions select 1_1 and never expose apiVersion as user input`() {
        val negotiation = negotiateDeckReadRoutes(
            DeckCapabilities(
                appVersion = "1.18.2",
                apiVersions = listOf("1.0", "1.1"),
                canCreateBoards = true,
            ),
        )
        val route = negotiation.candidates.first()
        val request = route.stacks(7)

        assertTrue(negotiation.capabilityBacked)
        assertEquals("1.1", route.version.value)
        assertEquals("/index.php/apps/deck/api/v1.1/boards/7/stacks", request.relativePath)
        assertTrue(request.ocsApiRequest)
        assertTrue(request.queryParameters.isEmpty())
    }

    @Test
    fun `read fallback advances only for route mismatch responses`() {
        val negotiation = negotiateDeckReadRoutes(null)
        val first = negotiation.candidates.first()

        assertFalse(negotiation.capabilityBacked)
        assertEquals("1.0", negotiation.nextAfter(first, 404)?.version?.value)
        assertNull(negotiation.nextAfter(first, 401))
        assertNull(negotiation.nextAfter(first, 403))
        assertNull(negotiation.nextAfter(first, 429))
        assertNull(negotiation.nextAfter(first, 500))
    }

    @Test
    fun `capability response parses exact Deck support`() {
        val capabilities = parseDeckCapabilities(
            jsonResponse(
                """
                {
                  "ocs": {
                    "data": {
                      "capabilities": {
                        "deck": {
                          "version": "1.18.2",
                          "canCreateBoards": true,
                          "apiVersions": ["1.0", "1.1"]
                        }
                      }
                    }
                  }
                }
                """.trimIndent(),
            ),
        )

        assertEquals("1.18.2", capabilities?.appVersion)
        assertEquals(listOf("1.0", "1.1"), capabilities?.apiVersions)
        assertEquals(true, capabilities?.canCreateBoards)
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
