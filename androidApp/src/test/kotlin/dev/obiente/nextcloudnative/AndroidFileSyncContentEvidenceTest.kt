package dev.obiente.nextcloudnative

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidFileSyncContentEvidenceTest {
    @Test
    fun identityReadBudgetIsSharedAndBounded() {
        val budget = AndroidFileSyncContentReadBudget(
            maximumFileBytes = 64L,
            maximumTotalBytes = 80L,
        )

        assertTrue(budget.reserve(40L))
        assertFalse(budget.reserve(70L))
        assertTrue(budget.reserve(40L))
        assertFalse(budget.reserve(1L))
        assertTrue(budget.reserve(0L))
        assertFalse(budget.reserve(null))
        assertEquals(0L, budget.remainingBytes)
    }
}
