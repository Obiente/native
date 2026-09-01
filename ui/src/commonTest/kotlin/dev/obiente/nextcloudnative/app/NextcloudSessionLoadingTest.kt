package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.CancellationException

class NextcloudSessionLoadingTest {
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
    fun unrelatedProgrammingFailureIsNotPresentedAsUnavailableStorage() {
        assertFailsWith<IllegalStateException> {
            loadNextcloudSessionSafely { error("synthetic invariant failure") }
        }
    }
}
