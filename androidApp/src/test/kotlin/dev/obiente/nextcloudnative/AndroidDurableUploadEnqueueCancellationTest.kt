package dev.obiente.nextcloudnative

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidDurableUploadEnqueueCancellationTest {
    @Test
    fun `cancellation releases a selection with no durable owner`() = runBlocking {
        val expected = CancellationException("screen closed")
        var releases = 0

        val actual = assertFailsWith<CancellationException> {
            runDurableUploadEnqueueWithCancellationCleanup<Unit>(
                enqueue = { throw expected },
                releaseUnownedSelection = {
                    assertTrue(
                        releaseUnownedDurableUploadSelection(
                            selectionId = "selection-123456",
                            hasActiveSelection = { false },
                            releaseSelection = {
                                releases += 1
                                true
                            },
                        ),
                    )
                },
            )
        }

        assertTrue(actual === expected)
        assertEquals(1, releases)
    }

    @Test
    fun `cancellation retains a selection owned by a queued job`() = runBlocking {
        var releases = 0

        assertFailsWith<CancellationException> {
            runDurableUploadEnqueueWithCancellationCleanup<Unit>(
                enqueue = { throw CancellationException("scheduling cancelled") },
                releaseUnownedSelection = {
                    assertFalse(
                        releaseUnownedDurableUploadSelection(
                            selectionId = "selection-123456",
                            hasActiveSelection = { true },
                            releaseSelection = {
                                releases += 1
                                true
                            },
                        ),
                    )
                },
            )
        }

        assertEquals(0, releases)
    }

    @Test
    fun `unreadable ownership state retains the selection`() {
        var releases = 0

        val released = releaseUnownedDurableUploadSelection(
            selectionId = "selection-123456",
            hasActiveSelection = { error("journal unavailable") },
            releaseSelection = {
                releases += 1
                true
            },
        )

        assertFalse(released)
        assertEquals(0, releases)
    }

    @Test
    fun `successful enqueue does not run cancellation cleanup`() = runBlocking {
        var cleanupCalls = 0

        val result = runDurableUploadEnqueueWithCancellationCleanup(
            enqueue = { "queued" },
            releaseUnownedSelection = { cleanupCalls += 1 },
        )

        assertEquals("queued", result)
        assertEquals(0, cleanupCalls)
    }
}
