package dev.obiente.nextcloudnative.app

import kotlin.test.*

class AccountPlaybackRetirementTest {
    private val gate = AccountPrivateMemoryGate()
    private val registry = AccountPlaybackRetirementRegistry(gate)
    private val first = "a".repeat(64)
    private val second = "b".repeat(64)
    private fun retire(account: String) = gate.retireAccount(account) { registry.purgeRetiredAccount(account) }

    @Test
    fun retirementClearsPendingCredentialsAndCancelsOnlyMatchingPlayback() {
        val pending = AccountPlaybackRetirementOwner(registry)
        val playing = AccountPlaybackRetirementOwner(registry)
        val other = AccountPlaybackRetirementOwner(registry)
        var pendingCredential: String? = "synthetic"
        var cancelled = 0
        var otherCancelled = 0
        val producer = assertNotNull(gate.producer(first))
        assertTrue(pending.bind(producer, { pendingCredential = null }, {}))
        assertTrue(playing.bind(producer, { cancelled += 1 }, {}))
        assertTrue(other.bind(assertNotNull(gate.producer(second)), { otherCancelled += 1 }, {}))
        retire(first)
        assertNull(pendingCredential)
        assertEquals(1, cancelled)
        assertEquals(0, otherCancelled)
        retire(first)
        assertEquals(1, cancelled)
    }

    @Test
    fun replacedOrReleasedQueueDoesNotRetainAnOldRetirementCallback() {
        val owner = AccountPlaybackRetirementOwner(registry)
        var oldCancelled = 0
        var newCancelled = 0
        owner.bind(assertNotNull(gate.producer(first)), { oldCancelled += 1 }, {})
        owner.bind(assertNotNull(gate.producer(second)), { newCancelled += 1 }, {})
        retire(first)
        assertEquals(0, oldCancelled)
        assertEquals(0, newCancelled)
        owner.clear()
        retire(second)
        assertEquals(0, newCancelled)
    }

    @Test
    fun retiredProducerCannotPublishAfterAccountReactivation() {
        val owner = AccountPlaybackRetirementOwner(registry)
        val old = assertNotNull(gate.producer(first))
        retire(first)
        gate.activateAccount(first)
        var published = 0
        assertFalse(owner.bind(old, {}, { published += 1 }))
        assertTrue(owner.bind(assertNotNull(gate.producer(first)), {}, { published += 1 }))
        assertEquals(1, published)
    }

    @Test
    fun delayedPlatformStopUsesCapturedQueueIdentityAfterReactivation() {
        val owner = AccountPlaybackRetirementOwner(registry)
        val pendingStops = mutableListOf<() -> Unit>()
        val oldQueue = Any()
        val newQueue = Any()
        var active: Any? = oldQueue
        owner.bind(assertNotNull(gate.producer(first)), {
            pendingStops += { if (active === oldQueue) active = null }
        }, {})
        retire(first)
        gate.activateAccount(first)
        owner.bind(assertNotNull(gate.producer(first)), {}, { active = newQueue })
        pendingStops.forEach { it() }
        assertSame(newQueue, active)
    }
}