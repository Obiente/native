package dev.obiente.nextcloudnative

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class AndroidAccountRemovalOrderingTest {
    @Test
    fun inactiveAccountRemovalNotifiesDocumentRootsAfterCredentialCommit() = runBlocking {
        val events = mutableListOf<String>()

        removeAndroidAccountCredentialData(
            active = false,
            removeQueuedUploads = { events += "remove-uploads" },
            clearActiveAccount = { events += "clear-account" },
            rollbackActiveRemoval = { events += "rollback-active" },
            persistInactiveRemoval = { events += "persist-inactive" },
            rollbackInactiveRemoval = { events += "rollback-inactive" },
            onInactiveRemovalCommitted = { events += "notify-roots" },
            completeCommittedCleanup = { events += "complete-cleanup" },
        )

        assertEquals(
            listOf("persist-inactive", "notify-roots", "remove-uploads", "complete-cleanup"),
            events,
        )
    }
}
