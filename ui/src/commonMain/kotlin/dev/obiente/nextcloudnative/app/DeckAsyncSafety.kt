package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class DeckCardLoadToken(
    val cardId: Long,
    val generation: Long,
)

/**
 * Correlates a response with both the newest request and the card that still owns the surface.
 */
internal class DeckCardLoadGate {
    private var generation = 0L
    private var requestedCardId: Long? = null

    fun begin(cardId: Long): DeckCardLoadToken {
        require(cardId > 0L) { "A Deck card load needs a valid card id." }
        generation += 1L
        requestedCardId = cardId
        return DeckCardLoadToken(cardId, generation)
    }

    fun invalidate() {
        generation += 1L
        requestedCardId = null
    }

    fun accepts(token: DeckCardLoadToken, activeCardId: Long?): Boolean =
        token.generation == generation &&
            token.cardId == requestedCardId &&
            token.cardId == activeCardId
}

internal suspend fun <T> parseDeckResponseOffUi(
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    parse: () -> T,
): T = withContext(dispatcher) {
    parse()
}
