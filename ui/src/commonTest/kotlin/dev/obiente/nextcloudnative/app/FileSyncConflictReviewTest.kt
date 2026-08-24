package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileSyncConflictReviewTest {
    @Test
    fun `only deletion propagation is destructive`() {
        FileSyncDecisionChoice.entries.forEach { choice ->
            if (choice == FileSyncDecisionChoice.PropagateDeletion) {
                assertTrue(choice.isDestructiveSyncDecision())
            } else {
                assertFalse(choice.isDestructiveSyncDecision())
            }
        }
    }
}
