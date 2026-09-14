package dev.obiente.nextcloudnative

import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AndroidCredentialSlotRepairCancellationTest {
    @Test
    fun cancelledSlotRepairStopsSessionPublication() {
        val cancelled = CancellationException("synthetic cancellation")
        var published = false
        assertSame(cancelled, assertFailsWith<CancellationException> {
            repairAndroidRecoveredCredentialSlot { throw cancelled }
            published = true
        })
        assertFalse(published)
    }

    @Test
    fun unavailableOptionalSlotRepairKeepsVerifiedAggregateUsable() {
        repairAndroidRecoveredCredentialSlot { throw IllegalStateException("synthetic storage failure") }
        var repaired = false
        repairAndroidRecoveredCredentialSlot { repaired = true }
        assertTrue(repaired)
    }

    @Test
    fun fatalRepairFailureIsNotARecoveredSession() {
        assertFailsWith<AssertionError> {
            repairAndroidRecoveredCredentialSlot { throw AssertionError("synthetic fatal failure") }
        }
    }
}
