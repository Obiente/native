package dev.obiente.nextcloudnative

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class AndroidDurableUploadRegistryAvailabilityTest {
    @Test
    fun `non-string registry preference defers durable upload account resolution`() {
        assertFalse(
            durableUploadAccountResolutionAvailable {
                throw ClassCastException("synthetic non-string registry")
            },
        )
    }

    @Test
    fun `registry availability check preserves worker cancellation`() {
        assertFailsWith<CancellationException> {
            durableUploadAccountResolutionAvailable {
                throw CancellationException("worker stopped")
            }
        }
    }

    @Test
    fun `non-string registry stops startup recovery without another poll`() = runBlocking {
        var attempts = 0

        keepRetryingQueuedDurableUploadScheduling(
            reconcile = {
                attempts += 1
                if (durableUploadAccountResolutionAvailable { throw ClassCastException("wrong type") }) {
                    error("The uploader must not be constructed for an unreadable registry")
                }
                true
            },
            wait = { error("Permanent registry corruption must not be polled") },
        )

        assertEquals(1, attempts)
    }
}
