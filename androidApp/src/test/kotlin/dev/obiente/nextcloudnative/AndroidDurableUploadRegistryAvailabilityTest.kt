package dev.obiente.nextcloudnative

import kotlinx.coroutines.CancellationException
import kotlin.test.Test
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
}
