package dev.obiente.nextcloudnative.app

import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
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
    fun platformSelectionUsesEachNativeCredentialStore() {
        assertEquals(
            DesktopSecretStoreKind.WindowsCredentialManager,
            desktopSecretStoreKind("Windows 11"),
        )
        assertEquals(DesktopSecretStoreKind.SecretService, desktopSecretStoreKind("Linux"))
        assertEquals(DesktopSecretStoreKind.MacOsKeychain, desktopSecretStoreKind("Mac OS X"))
    }

    @Test
    fun macOsKeychainAddsUpdatesLoadsAndClearsWithoutPuttingSecretsInIdentityFields() {
        val api = FakeMacOsKeychainApi()
        val releasedItems = mutableListOf<Pointer>()
        val store = MacOsKeychainSecretStore(api, releasedItems::add)
        val reference = desktopSessionSecretReference("https://cloud.invalid", "alice")
        val first = "first-synthetic-secret".encodeToByteArray()
        val second = "second-synthetic-secret".encodeToByteArray()

        assertNull(store.load(reference))
        store.save(reference, "alice", first)
        assertContentEquals(first, store.load(reference))
        store.save(reference, "alice", second)
        assertContentEquals(second, store.load(reference))
        store.clear(reference)
        assertNull(store.load(reference))

        assertEquals(reference.targetName, api.lastService)
        assertEquals("alice", api.lastAccount)
        assertFalse(api.lastService.orEmpty().contains("cloud.invalid"))
        assertFalse(api.lastService.orEmpty().contains("alice"))
        assertTrue(releasedItems.isNotEmpty())
    }

    @Test
    fun macOsKeychainDenialIsActionableAndDoesNotExposeCredentialIdentity() {
        val store = MacOsKeychainSecretStore(
            api = FakeMacOsKeychainApi(findFailure = -25_293),
            releaseItem = {},
        )
        val reference = desktopSessionSecretReference("https://private.invalid", "synthetic-user")

        val failure = assertFailsWith<DesktopSecretStoreUnavailableException> {
            store.load(reference)
        }

        assertTrue(failure.message.orEmpty().contains("denied"))
        assertFalse(failure.message.orEmpty().contains("private.invalid"))
        assertFalse(failure.message.orEmpty().contains("synthetic-user"))
    }

    @Test
    fun macOsKeychainConcurrentAddRaceUpdatesTheExistingItem() {
        val api = FakeMacOsKeychainApi(duplicateOnFirstAdd = true)
        val store = MacOsKeychainSecretStore(api, releaseItem = {})
        val reference = desktopSessionSecretReference("https://cloud.invalid", "alice")
        val expected = "replacement-synthetic-secret".encodeToByteArray()

        store.save(reference, "alice", expected)

        assertContentEquals(expected, store.load(reference))
    }

    private class FakeMacOsKeychainApi(
        private val findFailure: Int? = null,
        private val duplicateOnFirstAdd: Boolean = false,
    ) : MacOsKeychainApi {
        private var secret: ByteArray? = null
        private var addAttempted = false
        private var returnedSecret: Memory? = null
        private val item = Memory(1)
        var lastService: String? = null
            private set
        var lastAccount: String? = null
            private set

        override fun SecKeychainFindGenericPassword(
            keychainOrArray: Pointer?,
            serviceNameLength: Int,
            serviceName: ByteArray,
            accountNameLength: Int,
            accountName: ByteArray,
            secretLength: IntByReference?,
            secretData: PointerByReference?,
            itemRef: PointerByReference,
        ): Int {
            lastService = serviceName.copyOf(serviceNameLength).decodeToString()
            lastAccount = accountName.copyOf(accountNameLength).decodeToString()
            findFailure?.let { return it }
            val stored = secret ?: return -25_300
            if (secretLength != null && secretData != null) {
                returnedSecret = Memory(stored.size.toLong()).also { memory ->
                    memory.write(0, stored, 0, stored.size)
                    secretData.value = memory
                }
                secretLength.value = stored.size
            }
            itemRef.value = item
            return 0
        }

        override fun SecKeychainAddGenericPassword(
            keychain: Pointer?,
            serviceNameLength: Int,
            serviceName: ByteArray,
            accountNameLength: Int,
            accountName: ByteArray,
            secretLength: Int,
            secretData: ByteArray,
            itemRef: PointerByReference?,
        ): Int {
            if (duplicateOnFirstAdd && !addAttempted) {
                addAttempted = true
                secret = "concurrent-synthetic-secret".encodeToByteArray()
                return -25_299
            }
            secret = secretData.copyOf(secretLength)
            return 0
        }

        override fun SecKeychainItemModifyAttributesAndData(
            itemRef: Pointer,
            attributes: Pointer?,
            secretLength: Int,
            secretData: ByteArray,
        ): Int {
            secret = secretData.copyOf(secretLength)
            return 0
        }

        override fun SecKeychainItemDelete(itemRef: Pointer): Int {
            secret = null
            return 0
        }

        override fun SecKeychainItemFreeContent(attributes: Pointer?, secretData: Pointer?): Int {
            returnedSecret?.clear()
            returnedSecret = null
            return 0
        }
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
