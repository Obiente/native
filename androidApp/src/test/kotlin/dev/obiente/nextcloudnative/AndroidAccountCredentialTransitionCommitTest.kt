package dev.obiente.nextcloudnative

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class AndroidAccountCredentialTransitionCommitTest {
    @Test
    fun rootsNotificationFailureKeepsAnActiveCredentialRemovalCommitted() = runBlocking {
        val events = mutableListOf<String>()

        removeAndroidAccountCredentialData(
            active = true,
            removeQueuedUploads = { events += "remove-uploads" },
            clearActiveAccount = {
                events += "commit-removal"
                notifyAndroidDocumentRootsAfterCommittedTransition(
                    notify = { error("synthetic roots notification failure") },
                    recordFailure = { events += "diagnose-notification" },
                )
            },
            rollbackActiveRemoval = { events += "rollback-removal" },
            persistInactiveRemoval = {},
            rollbackInactiveRemoval = {},
        )

        assertEquals(listOf("commit-removal", "diagnose-notification", "remove-uploads"), events)
    }
}
