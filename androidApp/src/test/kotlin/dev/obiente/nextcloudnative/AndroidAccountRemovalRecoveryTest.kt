package dev.obiente.nextcloudnative

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AndroidAccountRemovalRecoveryTest {
    @Test
    fun restoredAccountRetriesMarkerClearWithoutDeletingOwnedWork() = runBlocking {
        var failClear = true
        val events = mutableListOf<String>()
        val retry = suspend {
            retryAndroidAccountRemovalCleanup(
                accountOwnedByRegistry = true,
                removeAccountOwnedWork = { events += "remove-owned-work" },
                clearCleanup = {
                    events += "clear-cleanup"
                    if (failClear) {
                        failClear = false
                        error("synthetic cleanup marker commit failure")
                    }
                },
            )
        }

        assertFailsWith<IllegalStateException> { retry() }
        retry()

        assertEquals(listOf("clear-cleanup", "clear-cleanup"), events)
    }

    @Test
    fun unknownAccountOwnershipFailsClosedBeforeCleanup() = runBlocking {
        val events = mutableListOf<String>()

        assertFailsWith<IllegalStateException> {
            retryAndroidAccountRemovalCleanup(
                accountOwnedByRegistry = null,
                removeAccountOwnedWork = { events += "remove-owned-work" },
                clearCleanup = { events += "clear-cleanup" },
            )
        }

        assertTrue(events.isEmpty())
    }
}
