package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.runtime.boardRoutes
import dev.obiente.nextcloudnative.nativeui.runtime.forApp
import dev.obiente.nextcloudnative.nativeui.runtime.resolveSemanticBoardContract
import dev.obiente.nextcloudnative.nativeui.runtime.semanticDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeckBoardToolsTest {
    private val routes = SemanticBoardRoutes(
        requireNotNull(
            resolveSemanticBoardContract(
                semanticDescriptor("planner", "9.0.0", boardRoutes(lane = "stacks", laneId = "stackId").forApp("planner")),
                writesAuthorized = true,
            ),
        ),
    )

    @Test
    fun `archived cards are grouped by list`() {
        val groups = parseDeckArchivedCards(
            7,
            deckToolResponse(
                """[{"id":1,"boardId":7,"title":"Todo","order":0,"cards":[
                {"id":10,"stackId":1,"title":"Old","archived":true},{"id":11,"stackId":1,"title":"Live","archived":false}]},
                {"id":2,"boardId":7,"title":"Done","order":1,"cards":[]}]""",
            ),
        )
        assertEquals(1, groups.size)
        assertEquals(listOf(10L), groups.single().cards.map(DeckCard::id))
        assertEquals("/apps/planner/api/v2/boards/7/stacks/archived", routes.archivedStacks(7).relativePath)
        assertFailsWith<IllegalArgumentException> {
            parseDeckArchivedCards(7, deckToolResponse("""[{"id":1,"boardId":9,"title":"X","cards":[]}]"""))
        }
    }

    @Test
    fun `list moves swap distinct orders and renumber ties`() {
        val stacks = listOf(stack(1, 0), stack(2, 5), stack(3, 9))
        assertEquals(
            listOf(2L to 9L, 3L to 5L),
            planDeckStackMove(stacks, 2, DeckStackMoveDirection.Later).map { it.stack.id to it.order },
        )
        assertTrue(planDeckStackMove(stacks, 1, DeckStackMoveDirection.Earlier).isEmpty())
        val tied = listOf(stack(1, 0), stack(2, 0), stack(3, 0))
        assertEquals(
            // Stack 2 already has order 0, so only the stacks whose value changes are sent.
            listOf(1L to 1L, 3L to 2L),
            planDeckStackMove(tied, 2, DeckStackMoveDirection.Earlier).map { it.stack.id to it.order },
        )
        assertFailsWith<IllegalArgumentException> { planDeckStackMove(stacks, 99, DeckStackMoveDirection.Later) }
        assertFalse(stack(3, 9).canMove(stacks, DeckStackMoveDirection.Later))
    }

    @Test
    fun `list move reports partial failure without retrying`() = runBlocking<Unit> {
        val sent = mutableListOf<String>()
        val message = runDeckStackMove(
            execute = { request ->
                sent += request.relativePath
                if (sent.size == 1) deckToolResponse("""{"id":2}""") else deckToolResponse("{}", 403)
            },
            plan = routes,
            board = board(),
            updates = planDeckStackMove(listOf(stack(1, 0), stack(2, 5)), 2, DeckStackMoveDirection.Earlier),
        )
        assertEquals(2, sent.size)
        assertTrue(message.orEmpty().startsWith("Some lists moved"))
    }

    @Test
    fun `verified write distinguishes rejection, confirmation, and unknown outcomes`() = runBlocking<Unit> {
        val request = NextcloudApiRequest(NextcloudApiMethod.PUT, "/apps/planner/api/v2/boards/7")
        val rejected = runVerifiedDeckWrite(
            execute = { deckToolResponse("{}", 403) },
            request = request,
            reread = { error("must not reread after a definite rejection") },
            verify = { _: Unit -> DeckWriteVerification.Confirmed },
            actionLabel = "test",
        )
        assertIs<DeckVerifiedWriteOutcome.Rejected<Unit>>(rejected)

        val confirmedAfterTimeout = runVerifiedDeckWrite(
            execute = { throw IllegalStateException("timeout") },
            request = request,
            reread = { 1 },
            verify = { value -> if (value == 1) DeckWriteVerification.Confirmed else DeckWriteVerification.NotApplied("no") },
            actionLabel = "test",
        )
        assertIs<DeckVerifiedWriteOutcome.Confirmed<Int>>(confirmedAfterTimeout)

        val unknown = runVerifiedDeckWrite(
            execute = { deckToolResponse("{}", 502) },
            request = request,
            reread = { error("offline") },
            verify = { _: Unit -> DeckWriteVerification.Confirmed },
            actionLabel = "test",
        )
        assertIs<DeckVerifiedWriteOutcome.Unknown>(unknown)

        assertFailsWith<CancellationException> {
            runVerifiedDeckWrite(
                execute = { throw CancellationException("left") },
                request = request,
                reread = { Unit },
                verify = { DeckWriteVerification.Confirmed },
                actionLabel = "test",
            )
        }
    }

    @Test
    fun `sharing controller verifies an added share and blocks writes after an unknown result`() = runBlocking<Unit> {
        var boardJson = boardJson(acl = "")
        var failRead = false
        val calls = mutableListOf<NextcloudApiMethod>()
        val refreshed = mutableListOf<DeckBoard>()
        val controller = DeckBoardSharingController(
            context(
                execute = { request ->
                    calls += request.method
                    when (request.method) {
                        NextcloudApiMethod.POST -> {
                            boardJson = boardJson(acl = ACL_BOB)
                            deckToolResponse("""{"id":40}""")
                        }
                        NextcloudApiMethod.DELETE -> throw IllegalStateException("connection reset")
                        else -> if (failRead) throw IllegalStateException("offline") else deckToolResponse(boardJson)
                    }
                },
                onBoardRefreshed = { refreshed += it },
            ),
        )
        controller.open(parseDeckBoard(deckToolResponse(boardJson)))
        controller.add(DeckSharee("bob", "Bob", DeckAclType.User))
        val afterAdd = checkNotNull(controller.state)
        assertEquals("Shared with Bob", afterAdd.notice)
        assertEquals(listOf("bob"), afterAdd.board.acl.map { it.participant.id })
        assertEquals(1, refreshed.size)

        failRead = true
        controller.requestRemoval(afterAdd.board.acl.single())
        controller.confirmRemoval()
        val afterUnknown = checkNotNull(controller.state)
        assertTrue(afterUnknown.outcomeUnknown)
        assertFalse(afterUnknown.writesEnabled)
        val deleteCalls = calls.count { it == NextcloudApiMethod.DELETE }
        controller.requestRemoval(afterUnknown.board.acl.single())
        controller.confirmRemoval()
        assertEquals(deleteCalls, calls.count { it == NextcloudApiMethod.DELETE })

        failRead = false
        controller.refresh()
        assertTrue(checkNotNull(controller.state).writesEnabled)
    }

    @Test
    fun `archive controller restores a card and reloads the board`() = runBlocking<Unit> {
        var archived = true
        var contentChanged = 0
        val controller = DeckArchivedCardsController(
            context(
                execute = { request ->
                    if (request.relativePath.endsWith("/unarchive")) {
                        archived = false
                        deckToolResponse("""{"id":10}""")
                    } else {
                        deckToolResponse(
                            """[{"id":1,"boardId":7,"title":"Todo","cards":[
                            {"id":10,"stackId":1,"title":"Old","archived":$archived}]}]""",
                        )
                    }
                },
                onBoardContentChanged = { contentChanged += 1 },
            ),
        )
        controller.open(board())
        controller.load()
        controller.unarchive(checkNotNull(controller.state).groups.single().cards.single())
        val state = checkNotNull(controller.state)
        assertTrue(state.groups.isEmpty())
        assertEquals(1, contentChanged)
        assertEquals("Old is back on the board", state.notice)
    }

    private fun context(
        execute: suspend (NextcloudApiRequest) -> NextcloudApiResponse,
        onBoardRefreshed: (DeckBoard) -> Unit = {},
        onBoardContentChanged: () -> Unit = {},
    ) = DeckBoardToolContext(
        execute = execute,
        routes = routes,
        currentUserId = "owner",
        onBoardRefreshed = onBoardRefreshed,
        onBoardContentChanged = onBoardContentChanged,
    )

    private fun boardJson(acl: String) =
        """{"id":7,"title":"Plan","color":"0082c9","owner":{"uid":"owner","displayname":"Owner"},
        "permissions":{"PERMISSION_READ":true,"PERMISSION_EDIT":true,"PERMISSION_MANAGE":true,"PERMISSION_SHARE":true},
        "acl":[$acl],"settings":{"notify-due":"assigned","calendar":"1"}}"""

    private fun ocs(data: String) = """{"ocs":{"meta":{"statuscode":200},"data":$data}}"""

    private fun board(edit: Boolean = true) = DeckBoard(
        id = 7,
        title = "Plan",
        color = "0082c9",
        archived = false,
        owner = DeckUser("owner", "Owner"),
        labels = emptyList(),
        permissions = DeckPermissions(canRead = true, canEdit = edit, canManage = true, canShare = true),
        shared = false,
        lastModified = 1L,
        etag = null,
        settings = mapOf("notify-due" to "assigned"),
    )

    private fun stack(id: Long, order: Long) = DeckStack(
        id = id,
        boardId = 7,
        title = "List $id",
        order = order,
        doneColumn = false,
        cards = emptyList(),
        lastModified = null,
        etag = null,
    )

    private companion object {
        const val ACL_BOB = """{"id":40,"boardId":7,"type":0,"participant":{"uid":"bob","displayname":"Bob"},
            "permissionEdit":false,"permissionShare":false,"permissionManage":false}"""
    }
}
