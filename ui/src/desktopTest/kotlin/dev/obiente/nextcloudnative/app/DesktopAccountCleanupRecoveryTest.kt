package dev.obiente.nextcloudnative.app

import java.util.UUID
import java.util.prefs.Preferences
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopAccountCleanupRecoveryTest {
    @Test
    fun preparedCleanupFromAnAbortedRemovalPreservesExistingPairs() = runBlocking {
        val events = mutableListOf<String>()
        val session = NextcloudSession("https://cloud.example.test", "alice", "password")
        val memoryCache = DynamicNativeMemoryCache()
        memoryCache.retireAccount(session.accountId.storageKey)

        retryDesktopAccountSyncPairCleanup(
            cleanup = DesktopAccountSyncPairCleanup(
                CLEANUP_ACCOUNT_ID,
                DesktopAccountSyncPairCleanupPhase.Prepared,
                MUTATION_SCOPE,
                session.accountId.storageKey,
            ),
            accountOwnership = { DesktopAccountOwnership.Present },
            removeSyncPairs = { events += "remove-pairs" },
            clearCleanup = { events += "clear-cleanup" },
            reactivatePresentAccount = {
                events += "activate-memory"
                memoryCache.activateAccount(requireNotNull(it.accountStorageKey))
            },
        )

        assertEquals(listOf("clear-cleanup", "activate-memory"), events)
        assertTrue(memoryCache.producer(session) != null)
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
            reactivatePresentAccount = { events += "activate-memory" },
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

    @Test
    fun malformedCleanupRemainsFailClosedRegardlessOfCredentialOwnership() = runBlocking {
        val absentEvents = mutableListOf<String>()
        retryDesktopAccountSyncPairCleanup(
            cleanup = DesktopAccountSyncPairCleanup(
                CLEANUP_ACCOUNT_ID,
                DesktopAccountSyncPairCleanupPhase.Unknown,
            ),
            accountOwnership = { DesktopAccountOwnership.Absent },
            removeSyncPairs = { absentEvents += "remove-pairs" },
            clearCleanup = { absentEvents += "clear-cleanup" },
        )
        assertTrue(absentEvents.isEmpty())

        val presentEvents = mutableListOf<String>()
        retryDesktopAccountSyncPairCleanup(
            cleanup = DesktopAccountSyncPairCleanup(
                CLEANUP_ACCOUNT_ID,
                DesktopAccountSyncPairCleanupPhase.Unknown,
            ),
            accountOwnership = { DesktopAccountOwnership.Present },
            removeSyncPairs = { presentEvents += "remove-pairs" },
            clearCleanup = { presentEvents += "clear-cleanup" },
        )
        assertTrue(presentEvents.isEmpty())
    }

    private companion object {
        const val CLEANUP_ACCOUNT_ID = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val MUTATION_SCOPE = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val ACCOUNT_STORAGE_KEY = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
