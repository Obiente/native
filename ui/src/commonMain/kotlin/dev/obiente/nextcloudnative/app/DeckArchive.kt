package dev.obiente.nextcloudnative.app

/**
 * Archived board content and list ordering.
 *
 * The archived-lists read, card archive and unarchive, list order updates, and the board
 * `archived` flag are each offered only when the board app's own contract describes them.
 */
data class DeckArchivedCardGroup(
    val stack: DeckStack,
    val cards: List<DeckCard>,
)

/** Groups archived cards by list, keeping list order and dropping lists without archived cards. */
fun parseDeckArchivedCards(
    boardId: Long,
    response: NextcloudApiResponse,
): List<DeckArchivedCardGroup> = deckArchivedCardGroups(parseDeckStacks(boardId, response, includeArchivedCards = true))

internal fun deckArchivedCardGroups(stacks: List<DeckStack>): List<DeckArchivedCardGroup> =
    stacks.mapNotNull { stack ->
        val archived = stack.cards.filter(DeckCard::archived)
        archived.takeIf(List<DeckCard>::isNotEmpty)?.let { cards ->
            DeckArchivedCardGroup(stack.copy(cards = emptyList()), cards)
        }
    }

/** Whether the board is archived and can be restored by the signed-in user. */
fun DeckBoard.canChangeArchiveState(): Boolean = permissions.canManage

enum class DeckStackMoveDirection(val offset: Int, val label: String) {
    Earlier(-1, "Move list left"),
    Later(1, "Move list right"),
}

data class DeckStackOrderUpdate(
    val stack: DeckStack,
    val order: Long,
)

/**
 * Plans the smallest set of idempotent stack updates that moves one list by one position.
 *
 * Deck stores an independent `order` per stack and the documented REST update changes only the
 * named stack. When the neighbors have distinct orders the two values are swapped. Otherwise the
 * board is renumbered in its intended sequence and only the stacks whose value changes are sent.
 * Every update sets an absolute value, so repeating a plan after an unknown result is safe.
 */
fun planDeckStackMove(
    stacks: List<DeckStack>,
    stackId: Long,
    direction: DeckStackMoveDirection,
): List<DeckStackOrderUpdate> {
    val ordered = stacks.sortedWith(compareBy(DeckStack::order, DeckStack::id))
    require(ordered.map(DeckStack::boardId).distinct().size <= 1) {
        "Lists from different boards cannot be reordered together."
    }
    val index = ordered.indexOfFirst { it.id == stackId }
    require(index >= 0) { "The list is not part of this board." }
    val target = index + direction.offset
    if (target !in ordered.indices) return emptyList()
    val moving = ordered[index]
    val neighbor = ordered[target]
    if (moving.order != neighbor.order) {
        return listOf(
            DeckStackOrderUpdate(moving, neighbor.order),
            DeckStackOrderUpdate(neighbor, moving.order),
        )
    }
    val sequence = ordered.toMutableList().apply {
        removeAt(index)
        add(target, moving)
    }
    return sequence.mapIndexedNotNull { position, stack ->
        val order = position.toLong()
        if (stack.order == order) null else DeckStackOrderUpdate(stack, order)
    }
}

fun DeckStack.canMove(stacks: List<DeckStack>, direction: DeckStackMoveDirection): Boolean {
    val ordered = stacks.sortedWith(compareBy(DeckStack::order, DeckStack::id))
    val index = ordered.indexOfFirst { it.id == id }
    return index >= 0 && (index + direction.offset) in ordered.indices
}
