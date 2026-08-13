package dev.obiente.nextcloudnative.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DesktopSecretStoreTest {
    @Test
    fun missingSecretToolProducesActionableSecureStorageError() {
        val store = SecretToolDesktopSecretStore(
            startProcess = { throw java.io.IOException("synthetic missing executable") },
        )
        val failure = assertFailsWith<DesktopSecretStoreUnavailableException> {
            store.save(
                reference = DesktopSecretReference(
                    targetName = "test/missing",
                    label = "Missing tool test",
                    attributes = mapOf("application" to "test"),
                ),
                username = "synthetic-user",
                secret = "synthetic-secret".encodeToByteArray(),
            )
        }

        assertTrue(failure.message.orEmpty().contains("libsecret-tools"))
        assertTrue(failure.message.orEmpty().contains("libsecret"))
        assertFalse(failure.message.orEmpty().contains("Cannot run program"))
        assertFalse(failure.message.orEmpty().contains("synthetic-user"))
        assertFalse(failure.message.orEmpty().contains("synthetic-secret"))
    }

    @Test
    fun rejectedSecretStoreKeepsSignInRetryableWithoutExposingTheSecret() {
        val store = SecretToolDesktopSecretStore(
            startProcess = { CompletedProcess(exitCode = 1) },
        )
        val failure = assertFailsWith<DesktopSecretStoreUnavailableException> {
            store.save(
                reference = DesktopSecretReference(
                    targetName = "test/rejected",
                    label = "Rejected store test",
                    attributes = mapOf("application" to "test"),
                ),
                username = "synthetic-user",
                secret = "synthetic-secret".encodeToByteArray(),
            )
        }

        assertTrue(failure.message.orEmpty().contains("running and unlocked"))
        assertFalse(failure.message.orEmpty().contains("synthetic-user"))
        assertFalse(failure.message.orEmpty().contains("synthetic-secret"))
    }

    @Test
    fun secretLookupTimeoutIncludesReadingStandardOutput() {
        val store = SecretToolDesktopSecretStore(
            timeoutMillis = 100,
            startProcess = { NeverCompletingProcess() },
        )
        val reference = DesktopSecretReference(
            targetName = "test/timeout",
            label = "Timeout test",
            attributes = mapOf("application" to "test"),
        )
        val startedAt = System.nanoTime()

        val failure = assertFailsWith<IllegalStateException> { store.load(reference) }

        assertTrue(failure.message.orEmpty().contains("Timed out"))
        assertTrue(System.nanoTime() - startedAt < 1_000_000_000L)
    }

    private class NeverCompletingProcess : Process() {
        private val completion = CountDownLatch(1)
        private val output = ByteArrayOutputStream()
        private val input = object : InputStream() {
            override fun read(): Int = try {
                completion.await()
                -1
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                -1
            }
        }

        override fun getOutputStream(): OutputStream = output
        override fun getInputStream(): InputStream = input
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int {
            completion.await()
            return 0
        }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = completion.await(timeout, unit)
        override fun exitValue(): Int = throw IllegalThreadStateException("Process is still running")
        override fun destroy() = completion.countDown()
        override fun destroyForcibly(): Process = apply { completion.countDown() }
        override fun isAlive(): Boolean = completion.count > 0L
    }

    private class CompletedProcess(private val exitCode: Int) : Process() {
        private val output = ByteArrayOutputStream()

        override fun getOutputStream(): OutputStream = output
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = exitCode
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true
        override fun exitValue(): Int = exitCode
        override fun destroy() = Unit
        override fun isAlive(): Boolean = false
    }

    @Test
    fun platformSelectionUsesWindowsCredentialManagerOnlyOnWindows() {
        assertEquals(
            DesktopSecretStoreKind.WindowsCredentialManager,
            desktopSecretStoreKind("Windows 11"),
        )
        assertEquals(DesktopSecretStoreKind.SecretService, desktopSecretStoreKind("Linux"))
        assertEquals(DesktopSecretStoreKind.SecretService, desktopSecretStoreKind("Mac OS X"))
    }

    @Test
    fun sessionCredentialTargetIsStableScopedAndDoesNotExposeAccountDetails() {
        val first = desktopSessionSecretReference("https://cloud.invalid", "alice")
        val same = desktopSessionSecretReference("https://cloud.invalid", "alice")
        val other = desktopSessionSecretReference("https://cloud.invalid", "bob")

        assertEquals(first.targetName, same.targetName)
        assertFalse(first.targetName.contains("cloud.invalid"))
        assertFalse(first.targetName.contains("alice"))
        assertFalse(first.targetName == other.targetName)
        assertEquals("https://cloud.invalid", first.attributes.getValue("server"))
        assertEquals("alice", first.attributes.getValue("login"))
    }

    @Test
    fun windowsCredentialManagerRoundTripUsesCurrentUserCredentialSet() {
        if (desktopSecretStoreKind() != DesktopSecretStoreKind.WindowsCredentialManager) return
        val store = WindowsCredentialManagerSecretStore()
        val reference = DesktopSecretReference(
            targetName = "Obiente/NextcloudNative/test/${UUID.randomUUID()}",
            label = "Nextcloud Native test credential",
            attributes = mapOf("application" to "dev.obiente.nextcloudnative.test"),
        )
        val secret = "synthetic-app-password".encodeToByteArray()
        try {
            store.save(reference, "synthetic-user", secret)
            assertContentEquals(secret, store.load(reference))
        } finally {
            store.clear(reference)
        }
        assertNull(store.load(reference))
    }
}
