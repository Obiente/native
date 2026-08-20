package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AppCoroutineResultTest {
    @Test
    fun `cancellation remains coroutine control flow`() {
        assertFailsWith<CancellationException> {
            runCatchingPreservingCancellation<Unit> {
                throw CancellationException("cancelled")
            }
        }
    }

    @Test
    fun `ordinary failures remain inspectable`() {
        val result = runCatchingPreservingCancellation<Unit> {
            error("failed")
        }

        assertEquals("failed", result.exceptionOrNull()?.message)
    }

    @Test
    fun `fatal errors are not converted into recoverable results`() {
        assertFailsWith<AssertionError> {
            runCatchingPreservingCancellation<Unit> {
                throw AssertionError("fatal")
            }
        }
    }
}
