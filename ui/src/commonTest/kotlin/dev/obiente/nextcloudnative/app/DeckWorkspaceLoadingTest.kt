package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.runtime.boardRoutes
import dev.obiente.nextcloudnative.nativeui.runtime.forApp
import dev.obiente.nextcloudnative.nativeui.runtime.get
import dev.obiente.nextcloudnative.nativeui.runtime.resolveSemanticBoardContract
import dev.obiente.nextcloudnative.nativeui.runtime.semanticDescriptor
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeckWorkspaceLoadingTest {
    @Test
    fun `lists past the per-list read bound are marked as not loaded instead of empty`() = runBlocking<Unit> {
        val listCount = MAX_SEPARATE_CARD_LIST_READS + 2
        val reads = mutableListOf<String>()
        val routes = SemanticBoardRoutes(
            requireNotNull(
                resolveSemanticBoardContract(
                    semanticDescriptor(
                        "kanban_x",
                        "42.0.0",
                        (boardRoutes() + get("/apps/APP/api/v2/boards/{boardId}/lists/{listId}/cards")).forApp("kanban_x"),
                    ),
                    writesAuthorized = true,
                ),
            ),
        )
        val lists = (1..listCount).joinToString(",", "[", "]") { id -> """{"id":$id,"title":"List $id","order":$id}""" }

        val stacks = fetchBoardStacks(
            execute = { request ->
                reads += request.relativePath
                val body = if (request.relativePath.endsWith("/lists")) lists else """[{"id":900,"title":"Card","order":1}]"""
                NextcloudApiResponse(200, body.encodeToByteArray(), "application/json", null)
            },
            routes = routes,
            boardId = 7,
        )

        assertEquals(listCount, stacks.size)
        assertEquals(1 + MAX_SEPARATE_CARD_LIST_READS, reads.size)
        assertTrue(stacks.take(MAX_SEPARATE_CARD_LIST_READS).all { it.cardsLoaded && it.cards.size == 1 })
        assertTrue(stacks.drop(MAX_SEPARATE_CARD_LIST_READS).all { !it.cardsLoaded && it.cards.isEmpty() })
        assertFalse(stacks.last().cardsLoaded)
    }
}
