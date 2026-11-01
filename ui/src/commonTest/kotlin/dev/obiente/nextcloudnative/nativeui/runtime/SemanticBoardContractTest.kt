package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.ProvenanceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SemanticBoardContractTest {
    @Test
    fun `board surface and writes follow the contract shape, not the app identity or version`() {
        val familiar = assertNotNull(resolve("deck", "1.18.2", boardRoutes(lane = "stacks", laneId = "stackId")))
        val unknown = assertNotNull(resolve("kanban_x", "42.0.0", boardRoutes()))

        assertEquals(familiar.operations.keys, unknown.operations.keys)
        val expected = setOf(
            BoardOperation.ListBoards, BoardOperation.ReadBoard, BoardOperation.CreateBoard,
            BoardOperation.UpdateBoard, BoardOperation.DeleteBoard, BoardOperation.ListLanes,
            BoardOperation.ListArchivedLanes, BoardOperation.CreateLane, BoardOperation.UpdateLane,
            BoardOperation.DeleteLane, BoardOperation.CreateCard, BoardOperation.ReadCard,
            BoardOperation.UpdateCard, BoardOperation.DeleteCard, BoardOperation.ArchiveCard,
            BoardOperation.UnarchiveCard, BoardOperation.MoveCard, BoardOperation.AssignLabel,
            BoardOperation.RemoveLabel, BoardOperation.CreateLabel, BoardOperation.ListComments,
            BoardOperation.CreateComment, BoardOperation.AddShare, BoardOperation.AssignUser,
            BoardOperation.UnassignUser, BoardOperation.UpdateLabel, BoardOperation.DeleteLabel,
            BoardOperation.UpdateComment, BoardOperation.DeleteComment, BoardOperation.ListAttachments,
            BoardOperation.ReadAttachment, BoardOperation.DeleteAttachment, BoardOperation.RestoreBoard,
            BoardOperation.UpdateShare, BoardOperation.DeleteShare,
        )
        assertEquals(expected, unknown.operations.keys)
        assertNotNull(unknown.settings)
    }

    @Test
    fun `move binds the lane by its role and keeps the card in the path`() {
        val move = assertNotNull(resolve("kanban_x", "42.0.0", boardRoutes())).binding(BoardOperation.MoveCard)

        assertEquals(mapOf("cardId" to BoardValueRole.Identity(BoardEntity.Card)), move.pathRoles)
        assertEquals(BoardValueRole.Identity(BoardEntity.Lane), move.bodyRoles["listId"])
        assertEquals(BoardValueRole.Field(BoardField.Order), move.bodyRoles["order"])
    }

    @Test
    fun `a contract without a card move offers no move or drag`() {
        val contract = assertNotNull(resolve("kanban_x", "42.0.0", boardRoutes(includeMove = false)))

        assertFalse(contract.supports(BoardOperation.MoveCard))
        assertTrue(contract.supports(BoardOperation.UpdateCard))
    }

    @Test
    fun `a flat titled list is not a board`() {
        val routes = listOf(
            get("/apps/APP/api/items"),
            get("/apps/APP/api/items/{id}"),
            write(HttpMethod.POST, "/apps/APP/api/items", mapOf("title" to "string"), setOf("title")),
        ).forApp("listy")

        assertNull(resolve("listy", "1.0.0", routes, raw = true))
    }

    @Test
    fun `a projects-and-members ledger is not a board`() {
        val project = "/apps/APP/api/v1/projects/{projectId}"
        val routes = listOf(
            get("/apps/APP/api/v1/projects"),
            get(project),
            write(HttpMethod.POST, "/apps/APP/api/v1/projects", mapOf("name" to "string"), setOf("name")),
            get("$project/members"),
            write(HttpMethod.POST, "$project/members", mapOf("name" to "string", "weight" to "number"), setOf("name")),
            get("$project/bills"),
            get("$project/bills/{billId}"),
            write(HttpMethod.POST, "$project/bills", mapOf("what" to "string", "amount" to "number", "payer" to "integer")),
        ).forApp("ledger_x")

        assertNull(resolve("ledger_x", "2.0.0", routes, raw = true))
    }

    @Test
    fun `a label inside a card relates it to the card and never deletes the board label`() {
        val contract = assertNotNull(resolve("kanban_x", "1.15.0", nestedBoardRoutes()))

        val remove = contract.binding(BoardOperation.RemoveLabel)
        assertEquals("/apps/kanban_x/cards/{cardId}/label/{labelId}", remove.action.binding.path)
        assertEquals(BoardValueRole.Identity(BoardEntity.Label), remove.pathRoles["labelId"])
        assertFalse(contract.supports(BoardOperation.DeleteLabel))
        assertTrue(contract.supports(BoardOperation.AssignUser) && contract.supports(BoardOperation.UnassignUser))
        assertTrue(contract.supports(BoardOperation.RestoreBoard))
    }

    @Test
    fun `boards without lanes inside a board are not boards`() {
        val routes = listOf(
            get("/apps/APP/api/boards"),
            get("/apps/APP/api/boards/{boardId}"),
            get("/apps/APP/api/lists"),
        ).forApp("halfboard")

        assertNull(resolve("halfboard", "3.0.0", routes, raw = true))
    }

    @Test
    fun `an unverified contract version keeps the board readable but offers no writes`() {
        val descriptor = semanticDescriptor("kanban_x", "42.0.0", boardRoutes().forApp("kanban_x"))
        val contract = assertNotNull(resolveSemanticBoardContract(descriptor, writesAuthorized = false))

        assertTrue(contract.operations.keys.none(BoardOperation::write))
        assertTrue(contract.supports(BoardOperation.ListLanes))
        assertNull(contract.settings?.write)
    }

    @Test
    fun `writes observed only from successful reads are never offered`() {
        val routes = boardRoutes().forApp("kanban_x").map { route ->
            if (route.method == HttpMethod.GET) route else route.copy(provenance = ProvenanceKind.successfulReadObservation)
        }
        val contract = assertNotNull(
            resolveSemanticBoardContract(semanticDescriptor("kanban_x", "42.0.0", routes), writesAuthorized = true),
        )

        assertTrue(contract.operations.keys.none(BoardOperation::write))
    }

    @Test
    fun `newest api route wins over an equivalent web route and older version`() {
        val routes = boardRoutes().forApp("kanban_x") + listOf(
            get("/apps/kanban_x/boards"),
            get("/apps/kanban_x/api/v1/boards"),
        )
        val contract = assertNotNull(resolve("kanban_x", "42.0.0", routes, raw = true))

        assertEquals("/apps/kanban_x/api/v2/boards", contract.binding(BoardOperation.ListBoards).action.binding.path)
    }

    @Test
    fun `a required input the client cannot name or read back makes a create unavailable`() {
        val routes = boardRoutes().forApp("kanban_x").map { route ->
            if (route.method == HttpMethod.POST && route.path.endsWith("/boards")) {
                route.copy(body = route.body + ("secretToken" to "string"), required = route.required + "secretToken")
            } else {
                route
            }
        }
        val contract = assertNotNull(resolve("kanban_x", "42.0.0", routes, raw = true))

        assertFalse(contract.supports(BoardOperation.CreateBoard))
    }

    private fun resolve(
        appId: String,
        version: String,
        routes: List<FixtureRoute>,
        raw: Boolean = false,
    ): SemanticBoardContract? = resolveSemanticBoardContract(
        semanticDescriptor(appId, version, if (raw) routes else routes.forApp(appId)),
        writesAuthorized = true,
    )
}
