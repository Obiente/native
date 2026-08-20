package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AppCoroutineResultTest {
    @Test
    fun `cancellation remains coroutine control flow`() {
        runBlocking {
            assertFailsWith<CancellationException> {
                runCatchingPreservingCancellation<Unit> {
                    throw CancellationException("cancelled")
                }
            }
        }
    }

    @Test
    fun `ordinary failures remain inspectable`() {
        runBlocking {
            val result = runCatchingPreservingCancellation<Unit> {
                error("failed")
            }

            assertEquals("failed", result.exceptionOrNull()?.message)
        }
    }

    @Test
    fun `fatal errors are not converted into recoverable results`() {
        runBlocking {
            assertFailsWith<AssertionError> {
                runCatchingPreservingCancellation<Unit> {
                    throw AssertionError("fatal")
                }
            }
        }
    }
}
