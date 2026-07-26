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

    data class RebalanceRequired(
        val reason: DeckCardOrderRebalanceReason,
        val insertionIndex: Int,
        val previousOrder: Long?,
        val nextOrder: Long?,
    ) : DeckCardInsertionPlan
}

enum class DeckCardOrderRebalanceReason {
    InvalidOrderSequence,
    NoOrderBeforeFirstCard,
    NoOrderBetweenCards,
    NoOrderAfterLastCard,
}

/**
 * Plans a Deck reorder without asking callers to construct board, stack, or card identifiers.
 *
 * A move is returned only when one non-negative integer can place the card strictly between its
 * new neighbors. When no such integer exists, the caller must refresh and use an authoritative
 * stack rebalance workflow instead of guessing or sending a duplicate order.
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

    val invalidOrderPair = remainingCards.zipWithNext().firstOrNull { (previous, next) ->
        previous.order < 0L || next.order < 0L || previous.order >= next.order
    }
    if (remainingCards.firstOrNull()?.order?.let { it < 0L } == true || invalidOrderPair != null) {
        return DeckCardInsertionPlan.RebalanceRequired(
            reason = DeckCardOrderRebalanceReason.InvalidOrderSequence,
            insertionIndex = insertionIndex,
            previousOrder = invalidOrderPair?.first?.order,
            nextOrder = invalidOrderPair?.second?.order ?: remainingCards.firstOrNull()?.order,
        )
    }

    val previousOrder = remainingCards.getOrNull(insertionIndex - 1)?.order
    val nextOrder = remainingCards.getOrNull(insertionIndex)?.order
    val order = when {
        previousOrder == null && nextOrder == null -> 0L
        previousOrder == null -> {
            if (requireNotNull(nextOrder) == 0L) {
                return DeckCardInsertionPlan.RebalanceRequired(
                    reason = DeckCardOrderRebalanceReason.NoOrderBeforeFirstCard,
                    insertionIndex = insertionIndex,
                    previousOrder = null,
                    nextOrder = nextOrder,
                )
            }
            nextOrder / 2L
        }
        nextOrder == null -> {
            if (previousOrder == Long.MAX_VALUE) {
                return DeckCardInsertionPlan.RebalanceRequired(
                    reason = DeckCardOrderRebalanceReason.NoOrderAfterLastCard,
                    insertionIndex = insertionIndex,
                    previousOrder = previousOrder,
                    nextOrder = null,
                )
            }
            previousOrder + 1L
        }
        nextOrder - previousOrder <= 1L -> {
            return DeckCardInsertionPlan.RebalanceRequired(
                reason = DeckCardOrderRebalanceReason.NoOrderBetweenCards,
                insertionIndex = insertionIndex,
                previousOrder = previousOrder,
                nextOrder = nextOrder,
            )
        }
        else -> previousOrder + ((nextOrder - previousOrder) / 2L)
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
            order = order,
        ),
    )
}
