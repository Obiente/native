package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.FileSyncConfiguration
import dev.obiente.nextcloudnative.app.FileSyncCoordinatorState
import dev.obiente.nextcloudnative.app.FileSyncPair
import dev.obiente.nextcloudnative.app.NextcloudSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AndroidFileSyncScheduleRecoveryTest {
    @Test fun emptyStoreDoesNotLoadCredentialsOrScheduleWork() = runBlocking<Unit> {
        restoreAndroidFileSyncSchedulesAtStartup({ AndroidFileSyncPersistedState() },
            { error("No credentials needed") }, { error("No work needed") })
    }

    @Test fun onlyTheRestoredAccountsPairsRequestScheduleRecovery() = runBlocking<Unit> {
        val queued = mutableListOf<String>()
        val account = NextcloudDocumentIds.accountKey(session)
        restoreAndroidFileSyncSchedulesAtStartup({ state(account) }, { session }, queued::add)
        assertEquals(listOf(account), queued)
        restoreAndroidFileSyncSchedulesAtStartup({ state("another-account") }, { session }, queued::add)
        restoreAndroidFileSyncSchedulesAtStartup({ state(account) }, { null }, queued::add)
        assertEquals(listOf(account), queued)
    }

    @Test fun cancellationNeverEnqueuesRestoration() = runBlocking<Unit> {
        assertFailsWith<CancellationException> {
            restoreAndroidFileSyncSchedulesAtStartup({ state("account") },
                { throw CancellationException("Stopped") }, { error("Cancelled recovery scheduled") })
        }
    }

    private val session = NextcloudSession("https://sync.example.invalid", "sample", "synthetic-app-password")
    private fun state(account: String) = AndroidFileSyncPersistedState(FileSyncCoordinatorState(listOf(
        FileSyncPair("pair", account, "synthetic-root", "Documents", FileSyncConfiguration(deviceLabel = "Device")),
    )))
}
