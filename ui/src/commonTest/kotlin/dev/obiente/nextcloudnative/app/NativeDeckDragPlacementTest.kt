package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeDeckDragPlacementTest {
    @Test
    fun `drop position follows card midpoints`() {
        val source = stack(id = 10, cards = listOf(card(id = 1, stackId = 10)))
        val destinationCards = listOf(
            card(id = 2, stackId = 20),
            card(id = 3, stackId = 20),
        )
        val destination = stack(id = 20, cards = destinationCards)
        val zones = listOf(
            stackZone(
                destination,
                cardZones = listOf(
                    cardZone(destinationCards[0], top = 100f, bottom = 180f),
                    cardZone(destinationCards[1], top = 200f, bottom = 280f),
                ),
            ),
        )

        val target = resolveDeckUiCardDropTarget(
            pointerX = 150f,
            pointerY = 190f,
            zones = zones,
            draggedCard = source.cards.single(),
        )

        assertEquals(destination, target?.stack)
        assertEquals(1, target?.insertionIndex)
    }

    @Test
    fun `empty destination accepts a card at the first position`() {
        val draggedCard = card(id = 1, stackId = 10)
        val destination = stack(id = 20)

        val target = resolveDeckUiCardDropTarget(
            pointerX = 120f,
            pointerY = 400f,
            zones = listOf(stackZone(destination)),
            draggedCard = draggedCard,
        )

        assertEquals(0, target?.insertionIndex)
    }

    @Test
    fun `source card is excluded from same-list insertion indexes`() {
        val cards = listOf(
            card(id = 1, stackId = 10),
            card(id = 2, stackId = 10),
            card(id = 3, stackId = 10),
        )
        val source = stack(id = 10, cards = cards)
        val target = resolveDeckUiCardDropTarget(
            pointerX = 120f,
            pointerY = 400f,
            zones = listOf(
                stackZone(
                    source,
                    cardZones = listOf(
                        cardZone(cards[0], top = 100f, bottom = 180f),
                        cardZone(cards[1], top = 200f, bottom = 280f),
                        cardZone(cards[2], top = 300f, bottom = 380f),
                    ),
                ),
            ),
            draggedCard = cards[1],
        )

        assertEquals(2, target?.insertionIndex)
        assertFalse(target!!.isNoOpFor(cards[1], listOf(source)))
    }

    @Test
    fun `same-list original position is a no-op`() {
        val cards = listOf(
            card(id = 1, stackId = 10),
            card(id = 2, stackId = 10),
            card(id = 3, stackId = 10),
        )
        val source = stack(id = 10, cards = cards)

        val target = resolveDeckUiCardDropTarget(
            pointerX = 120f,
            pointerY = 250f,
            zones = listOf(
                stackZone(
                    source,
                    cardZones = listOf(
                        cardZone(cards[0], top = 100f, bottom = 180f),
                        cardZone(cards[1], top = 200f, bottom = 280f),
                        cardZone(cards[2], top = 300f, bottom = 380f),
                    ),
                ),
            ),
            draggedCard = cards[1],
        )

        assertEquals(1, target?.insertionIndex)
        assertTrue(target!!.isNoOpFor(cards[1], listOf(source)))
    }

    @Test
    fun `pointer outside all lists has no drop target`() {
        val draggedCard = card(id = 1, stackId = 10)
        val destination = stack(id = 20)

        val target = resolveDeckUiCardDropTarget(
            pointerX = 500f,
            pointerY = 400f,
            zones = listOf(stackZone(destination)),
            draggedCard = draggedCard,
        )

        assertNull(target)
    }

    @Test
    fun `disposing an old lazy item cannot remove its replacement bounds`() {
        val registry = DeckUiBoundsRegistry<Long>()
        val oldOwner = Any()
        val newOwner = Any()
        val staleBounds = DeckUiRect(0f, 0f, 100f, 100f)
        val currentBounds = DeckUiRect(120f, 0f, 220f, 100f)

        registry.update(7L, oldOwner, staleBounds)
        registry.update(7L, newOwner, currentBounds)
        registry.remove(7L, oldOwner)

        assertEquals(currentBounds, registry.bounds(7L))
        registry.remove(7L, newOwner)
        assertNull(registry.bounds(7L))
    }

    private fun stackZone(
        stack: DeckStack,
        cardZones: List<DeckUiCardDropZone> = emptyList(),
    ) = DeckUiStackDropZone(
        stack = stack,
        bounds = DeckUiRect(left = 50f, top = 50f, right = 350f, bottom = 700f),
        cards = cardZones,
    )

    private fun cardZone(
        card: DeckCard,
        top: Float,
        bottom: Float,
    ) = DeckUiCardDropZone(
        card = card,
        bounds = DeckUiRect(left = 70f, top = top, right = 330f, bottom = bottom),
    )

    private fun stack(
        id: Long,
        cards: List<DeckCard> = emptyList(),
    ) = DeckStack(
        id = id,
        boardId = 5,
        title = "List $id",
        order = id,
        doneColumn = false,
        cards = cards,
        lastModified = null,
        etag = null,
    )

    private fun card(
        id: Long,
        stackId: Long,
    ) = DeckCard(
        id = id,
        boardId = 5,
        stackId = stackId,
        title = "Card $id",
        descriptionMarkdown = null,
        ownerId = null,
        color = null,
        order = id,
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
