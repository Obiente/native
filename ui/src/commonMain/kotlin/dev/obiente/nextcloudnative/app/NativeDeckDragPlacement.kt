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

/**
 * Tracks positioned lazy items without allowing a disposed item to remove a newer placement for
 * the same resource identity.
 */
internal class DeckUiBoundsRegistry<Key> {
    private data class Entry(
        val owner: Any,
        val bounds: DeckUiRect,
    )

    private val entries = mutableMapOf<Key, Entry>()

    fun update(key: Key, owner: Any, bounds: DeckUiRect) {
        entries[key] = Entry(owner, bounds)
    }

    fun bounds(key: Key): DeckUiRect? = entries[key]?.bounds

    fun remove(key: Key, owner: Any) {
        if (entries[key]?.owner === owner) {
            entries.remove(key)
        }
    }
}

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
