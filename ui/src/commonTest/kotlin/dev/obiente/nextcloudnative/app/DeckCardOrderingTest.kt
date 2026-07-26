package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

class DeckCardOrderingTest {
    @Test
    fun `empty stack starts at zero and derives typed move context`() {
        val source = card(id = 42, stackId = 11, order = 50)
        val result = planDeckCardInsertion(
            source = source,
            destination = stack(id = 12),
            insertionIndex = 0,
        )

        val move = assertIs<DeckCardInsertionPlan.MoveReady>(result).move
        assertEquals(42L, move.source.cardId)
        assertEquals(11L, move.source.stack.stackId)
        assertEquals(12L, move.destinationStack.stackId)
        assertEquals(7L, move.destinationStack.boardId.value)
        assertEquals(0L, move.order)
    }

    @Test
    fun `top middle and bottom insertion use final server positions`() {
        val source = card(id = 42, stackId = 11, order = 50)
        val destination = stack(
            id = 12,
            cards = listOf(
                card(id = 1, stackId = 12, order = 20),
                card(id = 2, stackId = 12, order = 40),
            ),
        )

        assertEquals(
            0L,
            planDeckCardInsertion(source, destination, insertionIndex = 0).readyMove().order,
        )
        assertEquals(
            1L,
            planDeckCardInsertion(source, destination, insertionIndex = 1).readyMove().order,
        )
        assertEquals(
            2L,
            planDeckCardInsertion(source, destination, insertionIndex = 2).readyMove().order,
        )
    }

    @Test
    fun `same stack insertion removes source before calculating neighbors`() {
        val first = card(id = 1, stackId = 12, order = 10)
        val source = card(id = 2, stackId = 12, order = 20)
        val last = card(id = 3, stackId = 12, order = 30)
        val destination = stack(id = 12, cards = listOf(first, source, last))

        assertEquals(
            0L,
            planDeckCardInsertion(source, destination, insertionIndex = 0).readyMove().order,
        )
        assertEquals(
            2L,
            planDeckCardInsertion(source, destination, insertionIndex = 2).readyMove().order,
        )
        assertSame(
            DeckCardInsertionPlan.Unchanged,
            planDeckCardInsertion(source, destination, insertionIndex = 1),
        )
    }

    @Test
    fun `same stack card can move into an available middle slot`() {
        val source = card(id = 3, stackId = 12, order = 30)
        val destination = stack(
            id = 12,
            cards = listOf(
                card(id = 1, stackId = 12, order = 0),
                card(id = 2, stackId = 12, order = 10),
                source,
            ),
        )

        assertEquals(
            1L,
            planDeckCardInsertion(source, destination, insertionIndex = 1).readyMove().order,
        )
    }

    @Test
    fun `server positions remain valid when loaded orders have no sparse gaps`() {
        val source = card(id = 42, stackId = 11, order = 50)

        val top = planDeckCardInsertion(
            source,
            stack(id = 12, cards = listOf(card(id = 1, stackId = 12, order = 0))),
            insertionIndex = 0,
        )
        assertEquals(0L, top.readyMove().order)

        val middle = planDeckCardInsertion(
            source,
            stack(
                id = 12,
                cards = listOf(
                    card(id = 1, stackId = 12, order = 10),
                    card(id = 2, stackId = 12, order = 11),
                ),
            ),
            insertionIndex = 1,
        )
        assertEquals(1L, middle.readyMove().order)

        val bottom = planDeckCardInsertion(
            source,
            stack(
                id = 12,
                cards = listOf(card(id = 1, stackId = 12, order = Long.MAX_VALUE)),
            ),
            insertionIndex = 1,
        )
        assertEquals(1L, bottom.readyMove().order)
    }

    @Test
    fun `loaded duplicate order values do not change requested final position`() {
        val source = card(id = 42, stackId = 11, order = 50)
        val result = planDeckCardInsertion(
            source,
            stack(
                id = 12,
                cards = listOf(
                    card(id = 1, stackId = 12, order = 10),
                    card(id = 2, stackId = 12, order = 10),
                ),
            ),
            insertionIndex = 1,
        )

        assertEquals(1L, result.readyMove().order)
    }

    @Test
    fun `invalid parent relationships and insertion indexes are rejected`() {
        val source = card(id = 42, stackId = 11, order = 50)

        assertFailsWith<IllegalArgumentException> {
            planDeckCardInsertion(
                source,
                stack(id = 12, boardId = 99),
                insertionIndex = 0,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            planDeckCardInsertion(source, stack(id = 12), insertionIndex = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            planDeckCardInsertion(
                source.copy(stackId = 12),
                stack(id = 12),
                insertionIndex = 0,
            )
        }
    }

    private fun DeckCardInsertionPlan.readyMove(): DeckCardMove =
        assertIs<DeckCardInsertionPlan.MoveReady>(this).move

    private fun stack(
        id: Long,
        boardId: Long = 7,
        cards: List<DeckCard> = emptyList(),
    ) = DeckStack(
        id = id,
        boardId = boardId,
        title = "Stack $id",
        order = 0,
        doneColumn = false,
        cards = cards,
        lastModified = null,
        etag = null,
    )

    private fun card(
        id: Long,
        stackId: Long,
        order: Long,
        boardId: Long = 7,
    ) = DeckCard(
        id = id,
        boardId = boardId,
        stackId = stackId,
        title = "Card $id",
        descriptionMarkdown = null,
        ownerId = null,
        color = null,
        order = order,
        dueAt = null,
        startAt = null,
        completedAt = null,
        archived = false,
        overdue = false,
        labels = emptyList(),
        assignees = emptyList(),
        attachmentCount = 0,
        unreadCommentCount = 0,
        etag = null,
    )
}
