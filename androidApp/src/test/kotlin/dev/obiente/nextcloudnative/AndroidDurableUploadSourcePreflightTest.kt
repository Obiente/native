package dev.obiente.nextcloudnative

import java.io.FileNotFoundException
import java.io.IOException
import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidDurableUploadSourcePreflightTest {
    @Test
    fun `missing or mismatched private metadata terminally fails and releases`() = runBlocking {
        listOf("missing", "mismatched").forEach { reason ->
            var providerOpened = false
            var queued = true
            var retained = true

            val result = processQueuedDurableUploadSource(
                requireCapability = {
                    throw AndroidLocalUploadCapabilityUnavailableException(reason)
                },
                openSource = { providerOpened = true },
                onCapabilityUnavailable = {
                    queued = false
                    retained = false
                    "failed"
                },
                onProviderUnavailable = { "retried" },
                onReady = { "started" },
            )

            assertEquals("failed", result)
            assertFalse(providerOpened)
            assertFalse(queued)
            assertFalse(retained)
        }
    }

    @Test
    fun `permanently unavailable provider source terminally fails and releases`() = runBlocking {
        listOf(
            FileNotFoundException("document removed"),
            SecurityException("grant revoked"),
        ).forEach { failure ->
            var queued = true
            var retained = true
            var starts = 0

            val result = processQueuedDurableUploadSource(
                requireCapability = { },
                openSource = { throw failure },
                onCapabilityUnavailable = {
                    queued = false
                    retained = false
                    "failed"
                },
                onProviderUnavailable = { "retried" },
                onReady = {
                    starts += 1
                    "started"
                },
            )

            assertEquals("failed", result)
            assertFalse(queued)
            assertFalse(retained)
            assertEquals(0, starts)
        }
    }

    @Test
    fun `transient provider failure leaves queued capability retained`() = runBlocking {
        var queued = true
        var retained = true
        var starts = 0

        val result = processQueuedDurableUploadSource(
            requireCapability = { },
            openSource = { throw IOException("provider unavailable") },
            onCapabilityUnavailable = {
                queued = false
                retained = false
                "failed"
            },
            onProviderUnavailable = { "retried" },
            onReady = {
                starts += 1
                "started"
            },
        )

        assertEquals("retried", result)
        assertTrue(queued)
        assertTrue(retained)
        assertEquals(0, starts)
    }

    @Test
    fun `transient capability metadata failure leaves queued capability retained`() = runBlocking {
        var queued = true
        var retained = true
        var starts = 0

        val result = processQueuedDurableUploadSource(
            requireCapability = {
                throw AndroidLocalUploadCapabilityReadException(
                    "credential store unavailable",
                    IOException("keystore restarting"),
                )
            },
            openSource = { starts += 1 },
            onCapabilityUnavailable = {
                queued = false
                retained = false
                "failed"
            },
            onProviderUnavailable = { "retried" },
            onReady = {
                starts += 1
                "started"
            },
        )

        assertEquals("retried", result)
        assertTrue(queued)
        assertTrue(retained)
        assertEquals(0, starts)
    }

    @Test
    fun `malformed capability metadata is terminal while storage failures stay retryable`() {
        assertFailsWith<AndroidLocalUploadCapabilityUnavailableException> {
            readAndroidLocalUploadCapability<Unit> {
                throw AndroidLocalUploadCapabilityMalformedException("invalid JSON")
            }
        }
        assertFailsWith<AndroidLocalUploadCapabilityUnavailableException> {
            readAndroidLocalUploadCapability {
                readAndroidLocalUploadCapabilityPreference {
                    throw ClassCastException("not a string")
                }
            }
        }
        assertFailsWith<AndroidLocalUploadCapabilityReadException> {
            readAndroidLocalUploadCapability {
                readAndroidLocalUploadCapabilityPreference {
                    throw IOException("preferences unavailable")
                }
            }
        }
        assertFailsWith<AndroidLocalUploadCapabilityReadException> {
            readAndroidLocalUploadCapability {
                decryptAndroidLocalUploadCapability {
                    throw GeneralSecurityException("keystore temporarily unavailable")
                }
            }
        }
    }

    @Test
    fun `invalid encrypted capability envelope and authentication terminally fail the job`() = runBlocking {
        listOf(
            InvalidSessionCiphertextException(
                "invalid base64 envelope",
                IllegalArgumentException("bad base64"),
            ),
            InvalidSessionCiphertextException(
                "authentication failed",
                AEADBadTagException("bad tag"),
            ),
        ).forEach { failure ->
            var queued = true
            var terminalDispositions = 0
            var transientRetries = 0
            val result = processQueuedDurableUploadSource(
                requireCapability = {
                    readAndroidLocalUploadCapability {
                        decryptAndroidLocalUploadCapability { throw failure }
                    }
                },
                openSource = { error("Corrupt capability metadata must not open the provider.") },
                onCapabilityUnavailable = {
                    queued = false
                    terminalDispositions += 1
                    "failed"
                },
                onProviderUnavailable = {
                    transientRetries += 1
                    "retried"
                },
                onReady = { error("Corrupt capability metadata must not start an upload.") },
            )

            assertEquals("failed", result)
            assertFalse(queued)
            assertEquals(1, terminalDispositions)
            assertEquals(0, transientRetries)
        }
    }

    @Test
    fun `later provider success starts exactly once`() = runBlocking {
        var providerAttempts = 0
        var starts = 0

        suspend fun attempt(): String = processQueuedDurableUploadSource(
            requireCapability = { },
            openSource = {
                providerAttempts += 1
                if (providerAttempts == 1) throw IOException("provider restarting")
            },
            onCapabilityUnavailable = { "failed" },
            onProviderUnavailable = { "retried" },
            onReady = {
                starts += 1
                "started"
            },
        )

        assertEquals("retried", attempt())
        assertEquals(0, starts)
        assertEquals("started", attempt())
        assertEquals(1, starts)
    }

    @Test
    fun `cancellation is preserved without running a disposition`() = runBlocking {
        listOf(true, false).forEach { cancelDuringCapabilityRead ->
            var dispositions = 0
            val expected = CancellationException("worker stopped")

            val actual = assertFailsWith<CancellationException> {
                processQueuedDurableUploadSource(
                    requireCapability = {
                        if (cancelDuringCapabilityRead) throw expected
                    },
                    openSource = {
                        if (!cancelDuringCapabilityRead) throw expected
                    },
                    onCapabilityUnavailable = {
                        dispositions += 1
                        Unit
                    },
                    onProviderUnavailable = {
                        dispositions += 1
                        Unit
                    },
                    onReady = {
                        dispositions += 1
                        Unit
                    },
                )
            }

            assertTrue(actual === expected)
            assertEquals(0, dispositions)
        }
    }
}
