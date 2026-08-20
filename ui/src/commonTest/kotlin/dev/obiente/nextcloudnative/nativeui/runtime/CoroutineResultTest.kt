package dev.obiente.nextcloudnative.nativeui.runtime

import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CoroutineResultTest {
    @Test
    fun `cancellation escapes result capture`() {
        assertFailsWith<CancellationException> {
            runCatchingUnlessCancelled<Unit> {
                throw CancellationException("cancelled")
            }
        }
    }

    @Test
    fun `ordinary failure remains available as a result`() {
        val result = runCatchingUnlessCancelled<Unit> {
            error("failed")
        }

        assertTrue(result.isFailure)
        assertEquals("failed", result.exceptionOrNull()?.message)
    }

    @Test
    fun `fatal errors escape result capture`() {
        assertFailsWith<AssertionError> {
            runCatchingUnlessCancelled<Unit> {
                throw AssertionError("fatal")
            }
        }
    }
}
