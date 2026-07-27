package dev.obiente.nextcloudnative.app

/**
 * Result of placing a loaded Deck card at a final visible index in a loaded destination stack.
 *
 * [insertionIndex] is interpreted after the source card has been removed from the destination
 * when moving within the same stack. This matches the final index shown by a native board.
 */
sealed interface DeckCardInsertionPlan {
    data class MoveReady(
        val move: DeckCardMove,
    ) : DeckCardInsertionPlan

    data object Unchanged : DeckCardInsertionPlan
}

/**
 * Plans a Deck reorder without asking callers to construct board, stack, or card identifiers.
 *
 * Deck's reorder endpoint interprets [DeckCardMove.order] as the final zero-based position and
 * authoritatively rewrites the affected stack to consecutive positions. Existing card order
 * values are therefore identity-free display state, not sparse keys from which a midpoint should
 * be invented.
 */
fun planDeckCardInsertion(
    source: DeckCard,
    destination: DeckStack,
    insertionIndex: Int,
): DeckCardInsertionPlan {
    require(source.id > 0L) { "The Deck card id is invalid." }
    require(source.boardId > 0L) { "The Deck board id is invalid." }
    require(source.stackId > 0L) { "The Deck source stack id is invalid." }
    require(destination.id > 0L) { "The Deck destination stack id is invalid." }
    require(destination.boardId == source.boardId) {
        "A Deck card cannot be moved to a stack from another board."
    }

    val destinationCards = destination.cards
    destinationCards.forEach { card ->
        require(card.id > 0L) { "A Deck destination card id is invalid." }
        require(card.boardId == destination.boardId && card.stackId == destination.id) {
            "A Deck destination card has invalid parent context."
        }
    }
    val movingWithinDestination = source.stackId == destination.id
    val sourceOccurrences = destinationCards.count { it.id == source.id }
    if (movingWithinDestination) {
        require(sourceOccurrences == 1) {
            "The Deck source card must occur exactly once in its current stack."
        }
    } else {
        require(sourceOccurrences == 0) {
            "The Deck source card cannot already exist in the destination stack."
        }
    }

    val remainingCards = if (movingWithinDestination) {
        destinationCards.filterNot { it.id == source.id }
    } else {
        destinationCards
    }
    require(insertionIndex in 0..remainingCards.size) {
        "The Deck card insertion index is invalid."
    }

    if (movingWithinDestination) {
        val currentIndex = destinationCards.indexOfFirst { it.id == source.id }
        if (currentIndex == insertionIndex) {
            return DeckCardInsertionPlan.Unchanged
        }
    }

    return DeckCardInsertionPlan.MoveReady(
        DeckCardMove(
            source = DeckCardContext(
                stack = DeckStackContext(
                    boardId = DeckBoardId(source.boardId),
                    stackId = source.stackId,
                ),
                cardId = source.id,
            ),
            destinationStack = DeckStackContext(
                boardId = DeckBoardId(destination.boardId),
                stackId = destination.id,
            ),
            order = insertionIndex.toLong(),
        ),
    )
}
