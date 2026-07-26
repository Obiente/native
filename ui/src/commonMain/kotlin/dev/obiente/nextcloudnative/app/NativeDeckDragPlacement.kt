package dev.obiente.nextcloudnative.app

internal data class DeckUiRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun contains(x: Float, y: Float): Boolean =
        x in left..right && y in top..bottom
}

internal data class DeckUiCardDropZone(
    val card: DeckCard,
    val bounds: DeckUiRect,
)

internal data class DeckUiStackDropZone(
    val stack: DeckStack,
    val bounds: DeckUiRect,
    val cards: List<DeckUiCardDropZone>,
)

internal data class DeckUiCardDropTarget(
    val stack: DeckStack,
    val insertionIndex: Int,
)

internal fun resolveDeckUiCardDropTarget(
    pointerX: Float,
    pointerY: Float,
    zones: List<DeckUiStackDropZone>,
    draggedCard: DeckCard,
): DeckUiCardDropTarget? {
    val destination = zones.firstOrNull { zone ->
        zone.bounds.contains(pointerX, pointerY)
    } ?: return null

    val remainingCards = destination.cards
        .asSequence()
        .filterNot { it.card.id == draggedCard.id }
        .sortedBy { it.bounds.top }
        .toList()
    val insertionIndex = remainingCards.indexOfFirst { zone ->
        pointerY < (zone.bounds.top + zone.bounds.bottom) / 2f
    }.takeIf { it >= 0 } ?: remainingCards.size

    return DeckUiCardDropTarget(
        stack = destination.stack,
        insertionIndex = insertionIndex,
    )
}

internal fun DeckUiCardDropTarget.isNoOpFor(
    draggedCard: DeckCard,
    stacks: List<DeckStack>,
): Boolean {
    if (stack.id != draggedCard.stackId) return false
    val sourceIndex = stacks
        .firstOrNull { it.id == draggedCard.stackId }
        ?.cards
        ?.indexOfFirst { it.id == draggedCard.id }
        ?: return false
    return sourceIndex == insertionIndex
}
