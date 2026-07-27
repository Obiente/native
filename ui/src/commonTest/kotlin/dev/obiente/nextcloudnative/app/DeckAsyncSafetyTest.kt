package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeckAsyncSafetyTest {
    @Test
    fun `only the newest load for the active card can publish`() {
        val gate = DeckCardLoadGate()
        val cardA = gate.begin(11L)
        val cardB = gate.begin(22L)

        assertFalse(gate.accepts(cardA, activeCardId = 11L))
        assertFalse(gate.accepts(cardB, activeCardId = 11L))
        assertTrue(gate.accepts(cardB, activeCardId = 22L))
    }

    @Test
    fun `a repeated refresh invalidates an older response for the same card`() {
        val gate = DeckCardLoadGate()
        val first = gate.begin(11L)
        val refresh = gate.begin(11L)

        assertFalse(gate.accepts(first, activeCardId = 11L))
        assertTrue(gate.accepts(refresh, activeCardId = 11L))
        gate.invalidate()
        assertFalse(gate.accepts(refresh, activeCardId = 11L))
    }

    @Test
    fun `navigation invalidates a pending authoritative card action`() {
        val gate = DeckCardLoadGate()
        val pendingAction = gate.begin(11L)

        gate.invalidate()

        assertFalse(gate.accepts(pendingAction, activeCardId = 11L))
    }

    @Test
    fun `Deck response parsing uses its background dispatcher`() = runBlocking {
        val dispatcher = RecordingDispatcher()

        val result = parseDeckResponseOffUi(dispatcher) {
            "parsed"
        }

        assertEquals("parsed", result)
        assertTrue(dispatcher.dispatched)
    }

    private class RecordingDispatcher : CoroutineDispatcher() {
        var dispatched = false

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatched = true
            block.run()
        }
    }
}
