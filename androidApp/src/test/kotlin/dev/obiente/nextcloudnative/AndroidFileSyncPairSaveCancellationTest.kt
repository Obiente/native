package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.FileSyncConfiguration
import dev.obiente.nextcloudnative.app.FileSyncCoordinatorState
import dev.obiente.nextcloudnative.app.FileSyncPair
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AndroidFileSyncPairSaveCancellationTest {
    @Test
    fun cancellationAfterDurableSaveRetainsOwnershipWithoutContinuingToSchedule() {
        val cancelled = CancellationException("synthetic save cancellation")
        var persisted = AndroidFileSyncPersistedState()
        var abandoned = false
        var scheduled = false
        val failure = assertFailsWith<CancellationException> {
            bindAndPersistFileSyncPair(
                pair.id, bindReady = {},
                persist = {
                    persisted = AndroidFileSyncPersistedState(coordinator = FileSyncCoordinatorState(listOf(pair)))
                    throw cancelled
                },
                load = { persisted }, abandonUncommittedPair = { abandoned = true; true },
            )
            scheduled = true
        }
        assertSame(cancelled, failure)
        assertEquals(listOf(pair), persisted.coordinator.pairs)
        assertFalse(abandoned)
        assertFalse(scheduled)
    }

    @Test
    fun cancellationBeforeSaveReleasesOnlyAuthoritativelyUncommittedOwnership() {
        val cancelled = CancellationException("synthetic precommit cancellation")
        var abandoned = false
        val failure = assertFailsWith<CancellationException> {
            bindAndPersistFileSyncPair(
                pair.id, bindReady = {}, persist = { throw cancelled },
                load = { AndroidFileSyncPersistedState() },
                abandonUncommittedPair = { abandoned = true; true },
            )
        }
        assertSame(cancelled, failure)
        assertTrue(abandoned)
    }

    @Test
    fun cancellationDuringAuthoritativeReloadNeverAbandonsUnknownOwnership() {
        for (initialCancelled in listOf(false, true)) {
            val initial = if (initialCancelled) CancellationException("save cancelled") else IllegalStateException("save failed")
            val reload = CancellationException("reload cancelled")
            var abandoned = false
            val failure = assertFailsWith<CancellationException> {
                bindAndPersistFileSyncPair(
                    pair.id, bindReady = {}, persist = { throw initial }, load = { throw reload },
                    abandonUncommittedPair = { abandoned = true; true },
                )
            }
            assertSame(if (initialCancelled) initial else reload, failure)
            assertFalse(abandoned)
        }
    }

    private val pair = FileSyncPair(
        id = "00000000-0000-0000-0000-000000000001", accountId = "synthetic-account",
        localRootId = "content://example.documents/tree/notes", remoteRootPath = "Notes",
        configuration = FileSyncConfiguration(deviceLabel = "Test device"),
    )
}
