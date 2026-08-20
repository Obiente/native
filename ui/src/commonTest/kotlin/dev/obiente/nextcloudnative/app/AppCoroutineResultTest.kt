package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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

    @Test
    fun `cancellation before handoff cleans up and remains control flow`() {
        var cleanedUp = false

        assertFailsWith<CancellationException> {
            runBlocking {
                runWithCleanupBeforeHandoff(cleanup = { cleanedUp = true }) {
                    throw CancellationException("cancelled")
                }
            }
        }

        assertTrue(cleanedUp)
    }

    @Test
    fun `cancellation after handoff retains the transferred resource`() {
        var cleanedUp = false

        assertFailsWith<CancellationException> {
            runBlocking {
                runWithCleanupBeforeHandoff(cleanup = { cleanedUp = true }) { markHandedOff ->
                    markHandedOff()
                    throw CancellationException("cancelled")
                }
            }
        }

        assertFalse(cleanedUp)
    }
}
