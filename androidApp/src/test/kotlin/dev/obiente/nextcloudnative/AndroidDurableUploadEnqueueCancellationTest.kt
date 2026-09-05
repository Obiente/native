package dev.obiente.nextcloudnative

import java.security.GeneralSecurityException
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
    fun `cancellation releases cached capability when encrypted storage is unreadable`() = runBlocking {
        val expected = CancellationException("screen closed")
        var cleanupResult: Boolean? = null
        var encryptedMetadata: String? = "unreadable-encrypted-capability"
        var loadAttempts = 0
        val events = mutableListOf<String>()

        val actual = assertFailsWith<CancellationException> {
            runDurableUploadEnqueueWithCancellationCleanup<Unit>(
                enqueue = { throw expected },
                releaseUnownedSelection = {
                    cleanupResult = releaseUnownedDurableUploadSelection(
                        selectionId = "selection-123456",
                        hasActiveSelection = { false },
                        releaseSelection = {
                            releaseStoredDurableUploadCapability(
                                cachedCapability = "content://cached/upload",
                                loadCapability = {
                                    loadAttempts += 1
                                    throw GeneralSecurityException("synthetic decryption failure")
                                },
                                releasePermission = { events += "permission:$it" },
                                removeMetadata = {
                                    events += "metadata"
                                    true.also { encryptedMetadata = null }
                                },
                            )
                        },
                    )
                },
            )
        }

        assertTrue(actual === expected)
        assertTrue(cleanupResult == true)
        assertEquals(0, loadAttempts)
        assertEquals(listOf("permission:content://cached/upload", "metadata"), events)
        assertEquals(null, encryptedMetadata)
    }

    @Test
    fun `unreadable ownership state persists cleanup intent without releasing`() {
        var releases = 0
        var ownershipChecksPending = 0

        val released = releaseUnownedDurableUploadSelection(
            selectionId = "selection-123456",
            hasActiveSelection = { error("journal unavailable") },
            releaseSelection = {
                releases += 1
                true
            },
            markOwnershipCheckPending = {
                ownershipChecksPending += 1
                true
            },
        )

        assertFalse(released)
        assertEquals(0, releases)
        assertEquals(1, ownershipChecksPending)
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
