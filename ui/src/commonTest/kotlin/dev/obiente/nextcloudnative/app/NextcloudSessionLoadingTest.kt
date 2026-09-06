package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class NextcloudSessionLoadingTest {
    @Test
    fun coordinatorDefersSessionMigrationUntilItsEffectRuns() = runBlocking {
        var loads = 0
        val expected = NextcloudSession("https://cloud.invalid", "alice", "synthetic-secret")
        val coordinator = NextcloudSessionLoadCoordinator {
            loads += 1
            expected
        }

        assertNull(coordinator.state)
        assertEquals(0, loads)

        val loaded = assertIs<NextcloudSessionLoadState.Loaded>(coordinator.load(Dispatchers.Unconfined))

        assertEquals(expected, loaded.session)
        assertEquals(loaded, coordinator.state)
        assertEquals(1, loads)
    }

    @Test
    fun secureStorageFailureBecomesRetryableWithoutExposingItsMessage() {
        var attempts = 0
        val expected = NextcloudSession("https://cloud.invalid", "alice", "synthetic-secret")
        val load = {
            attempts += 1
            if (attempts == 1) throw NextcloudSessionStorageUnavailableException("private provider failure")
            expected
        }

        assertEquals(
            NextcloudSessionLoadState.SecureStorageUnavailable,
            loadNextcloudSessionSafely(load),
        )
        val recovered = assertIs<NextcloudSessionLoadState.Loaded>(loadNextcloudSessionSafely(load))
        assertEquals(expected, recovered.session)
    }

    @Test
    fun cancellationRemainsControlFlow() {
        assertFailsWith<CancellationException> {
            loadNextcloudSessionSafely { throw CancellationException("cancelled") }
        }
    }

    @Test
    fun missingLegacyMigrationProviderKeepsItsRecoveryCategory() {
        assertEquals(
            NextcloudSessionLoadState.LegacyMigrationUnavailable,
            loadNextcloudSessionSafely {
                throw NextcloudSessionLegacyMigrationUnavailableException(
                    NextcloudSessionStorageUnavailableException("private provider failure"),
                )
            },
        )
    }

    @Test
    fun unrelatedProgrammingFailureIsNotPresentedAsUnavailableStorage() {
        assertFailsWith<IllegalStateException> {
            loadNextcloudSessionSafely { error("synthetic invariant failure") }
        }
    }
}
