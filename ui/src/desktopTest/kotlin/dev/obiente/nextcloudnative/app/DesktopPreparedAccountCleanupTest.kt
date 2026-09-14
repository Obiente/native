package dev.obiente.nextcloudnative.app

import java.util.UUID
import java.util.prefs.Preferences
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopPreparedAccountCleanupTest {
    @Test
    fun preparedCleanupFromAnAbortedRemovalPreservesExistingPairs() = runBlocking {
        val events = mutableListOf<String>()

        retryDesktopAccountSyncPairCleanup(
            cleanup = DesktopAccountSyncPairCleanup(
                CLEANUP_ACCOUNT_ID,
                DesktopAccountSyncPairCleanupPhase.Prepared,
            ),
            accountOwnership = { DesktopAccountOwnership.Present },
            removeSyncPairs = { events += "remove-pairs" },
            clearCleanup = { events += "clear-cleanup" },
        )

        assertEquals(listOf("clear-cleanup"), events)
    }

    @Test
    fun preparedCleanupPreservesPairsAndJournalWhenCredentialOwnershipIsUnknown() = runBlocking {
        val events = mutableListOf<String>()

        retryDesktopAccountSyncPairCleanup(
            cleanup = DesktopAccountSyncPairCleanup(
                CLEANUP_ACCOUNT_ID,
                DesktopAccountSyncPairCleanupPhase.Prepared,
            ),
            accountOwnership = { DesktopAccountOwnership.Unknown },
            removeSyncPairs = { events += "remove-pairs" },
            clearCleanup = { events += "clear-cleanup" },
        )

        assertTrue(events.isEmpty())
    }

    @Test
    fun futureCleanupFormatRemainsBlockedAndUntouchedWhenCredentialsAreAbsent() = runBlocking {
        val preferences = Preferences.userRoot().node("desktop-account-cleanup-test-${UUID.randomUUID()}")
        val futureValue = "v99|committed|future-private-state"
        preferences.put("fsac.$CLEANUP_ACCOUNT_ID", futureValue)
        val journal = DesktopAccountSyncPairCleanupJournal(preferences)
        var ownershipChecks = 0
        val events = mutableListOf<String>()

        try {
            val cleanup = journal.pending().single()
            assertEquals(DesktopAccountSyncPairCleanupPhase.Unknown, cleanup.phase)
            assertTrue(journal.blocksAccountActivation(CLEANUP_ACCOUNT_ID))

            retryDesktopAccountSyncPairCleanup(
                cleanup = cleanup,
                accountOwnership = {
                    ownershipChecks += 1
                    DesktopAccountOwnership.Absent
                },
                removeSyncPairs = { events += "remove-pairs" },
                clearCleanup = { events += "clear-cleanup" },
            )

            assertEquals(0, ownershipChecks)
            assertTrue(events.isEmpty())
            assertEquals(futureValue, preferences.get("fsac.$CLEANUP_ACCOUNT_ID", null))
            assertTrue(journal.blocksAccountActivation(CLEANUP_ACCOUNT_ID))
            assertTrue(journal.blocksAccountActivation("9".repeat(64), ACCOUNT_STORAGE_KEY))
        } finally {
            preferences.removeNode()
        }
    }

    @Test
    fun preparedCleanupUsesCredentialFreeOwnershipToRecover() = runBlocking {
        val absentEvents = mutableListOf<String>()
        retryDesktopAccountSyncPairCleanup(
            cleanup = DesktopAccountSyncPairCleanup(
                CLEANUP_ACCOUNT_ID,
                DesktopAccountSyncPairCleanupPhase.Prepared,
            ),
            accountOwnership = { DesktopAccountOwnership.Absent },
            removeSyncPairs = { absentEvents += "remove-pairs" },
            clearCleanup = { absentEvents += "clear-cleanup" },
        )
        assertEquals(listOf("remove-pairs", "clear-cleanup"), absentEvents)

        val presentEvents = mutableListOf<String>()
        retryDesktopAccountSyncPairCleanup(
            cleanup = DesktopAccountSyncPairCleanup(
                CLEANUP_ACCOUNT_ID,
                DesktopAccountSyncPairCleanupPhase.Prepared,
            ),
            accountOwnership = { DesktopAccountOwnership.Present },
            removeSyncPairs = { presentEvents += "remove-pairs" },
            clearCleanup = { presentEvents += "clear-cleanup" },
        )
        assertEquals(listOf("clear-cleanup"), presentEvents)
    }

    private companion object {
        const val CLEANUP_ACCOUNT_ID = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val ACCOUNT_STORAGE_KEY = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
    }
}
