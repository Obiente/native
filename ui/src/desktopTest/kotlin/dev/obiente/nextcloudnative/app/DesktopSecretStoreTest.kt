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
    fun secretLookupTimeoutIsReportedAsUnavailableSecureStorage() {
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

        val failure = assertFailsWith<DesktopSecretStoreUnavailableException> { store.load(reference) }

        assertTrue(failure.message.orEmpty().contains("running and unlocked"))
        assertTrue(System.nanoTime() - startedAt < 1_000_000_000L)
    }

    @Test
    fun failedSecretLookupCannotBeMistakenForConfirmedAbsenceDuringMigration() {
        val reference = desktopDeckDraftSecretReference()
        val legacy = SecretToolDesktopSecretStore(
            startProcess = { throw java.io.IOException("synthetic missing executable") },
        )
        val primary = RecordingSecretStore()
        val adoption = RecordingSecretStoreAdoption()

        assertFailsWith<DesktopSecretStoreUnavailableException> {
            MigratingDesktopSecretStore(primary, legacy, adoption).load(reference)
        }

        assertNull(primary.load(reference))
        assertEquals(DesktopSecretStoreAdoptionState.NotAdopted, adoption.state(reference))
    }

    @Test
    fun rejectedSecretLookupCannotBeMistakenForConfirmedAbsenceDuringMigration() {
        val reference = desktopDeckDraftSecretReference()
        val legacy = SecretToolDesktopSecretStore(
            startProcess = { CompletedProcess(exitCode = 1) },
        )

        assertFailsWith<DesktopSecretStoreUnavailableException> {
            MigratingDesktopSecretStore(
                RecordingSecretStore(),
                legacy,
                RecordingSecretStoreAdoption(),
            ).load(reference)
        }
    }

    @Test
    fun emptyUnlockedSearchConfirmsThatNoLegacySecretExists() {
        val reference = desktopDeckDraftSecretReference()
        val legacy = SecretToolDesktopSecretStore(
            startProcess = { command ->
                CompletedProcess(exitCode = if (command[1] == "search") 0 else 1)
            },
        )
        val adoption = RecordingSecretStoreAdoption()

        assertNull(MigratingDesktopSecretStore(RecordingSecretStore(), legacy, adoption).load(reference))

        assertEquals(DesktopSecretStoreAdoptionState.NotAdopted, adoption.state(reference))
    }

    @Test
    fun failedLegacyClearIsReportedUnlessSearchConfirmsTheItemIsGone() {
        val reference = desktopDeckDraftSecretReference()
        val stillPresent = SecretToolDesktopSecretStore(
            startProcess = { command ->
                if (command[1] == "search") {
                    CompletedProcess(0, "synthetic matching item".encodeToByteArray())
                } else {
                    CompletedProcess(1)
                }
            },
        )
        val absent = SecretToolDesktopSecretStore(
            startProcess = { command ->
                CompletedProcess(exitCode = if (command[1] == "search") 0 else 1)
            },
        )

        assertFailsWith<DesktopSecretStoreUnavailableException> { stillPresent.clear(reference) }
        absent.clear(reference)
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

    private class CompletedProcess(
        private val exitCode: Int,
        private val input: ByteArray = ByteArray(0),
    ) : Process() {
        private val output = ByteArrayOutputStream()

        override fun getOutputStream(): OutputStream = output
        override fun getInputStream(): InputStream = ByteArrayInputStream(input)
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
        assertEquals(64, api.lastAccount?.length)
        assertFalse(api.lastAccount.orEmpty().contains("alice"))
        assertFalse(api.lastService.orEmpty().contains("cloud.invalid"))
        assertFalse(api.lastService.orEmpty().contains("alice"))
        assertTrue(releasedItems.isNotEmpty())
    }

    @Test
    fun existingSecretServiceSessionsAndDraftKeysMigrateBeforeKeychainAdoption() {
        val sessionReference = desktopSessionSecretReference("https://cloud.invalid", "alice")
        val draftReference = desktopDeckDraftSecretReference()
        val legacy = RecordingSecretStore(
            mutableMapOf(
                sessionReference.targetName to "session-secret".encodeToByteArray(),
                draftReference.targetName to "draft-secret".encodeToByteArray(),
            ),
        )
        val primary = RecordingSecretStore()
        val adoption = RecordingSecretStoreAdoption()
        val store = MigratingDesktopSecretStore(primary, legacy, adoption)

        assertContentEquals("session-secret".encodeToByteArray(), store.load(sessionReference))
        assertContentEquals("draft-secret".encodeToByteArray(), store.load(draftReference))

        assertContentEquals("session-secret".encodeToByteArray(), primary.load(sessionReference))
        assertContentEquals("draft-secret".encodeToByteArray(), primary.load(draftReference))
        assertNull(legacy.load(sessionReference))
        assertNull(legacy.load(draftReference))
        assertEquals(DesktopSecretStoreAdoptionState.AdoptedAndClean, adoption.state(sessionReference))
        assertEquals(DesktopSecretStoreAdoptionState.AdoptedAndClean, adoption.state(draftReference))
    }

    @Test
    fun adoptedKeychainReferenceNeverResurrectsAStaleLegacySecret() {
        val reference = desktopSessionSecretReference("https://cloud.invalid", "alice")
        val stale = "stale-session-secret".encodeToByteArray()
        val legacy = RecordingSecretStore(
            mutableMapOf(reference.targetName to stale),
            ignoreClear = true,
        )
        val primary = RecordingSecretStore()
        val adoption = RecordingSecretStoreAdoption()
        val store = MigratingDesktopSecretStore(primary, legacy, adoption)

        store.save(reference, "alice", "current-session-secret".encodeToByteArray())
        legacy.values[reference.targetName] = stale
        store.clear(reference)

        assertNull(store.load(reference))
        assertContentEquals(stale, legacy.values.getValue(reference.targetName))
    }

    @Test
    fun failedKeychainMigrationLeavesTheLegacySecretRetryable() {
        val reference = desktopSessionSecretReference("https://cloud.invalid", "alice")
        val expected = "legacy-session-secret".encodeToByteArray()
        val legacy = RecordingSecretStore(mutableMapOf(reference.targetName to expected))
        val primary = RecordingSecretStore(failSave = true)
        val adoption = RecordingSecretStoreAdoption()
        val store = MigratingDesktopSecretStore(primary, legacy, adoption)

        assertFailsWith<DesktopSecretStoreUnavailableException> { store.load(reference) }

        assertContentEquals(expected, legacy.load(reference))
        assertEquals(DesktopSecretStoreAdoptionState.NotAdopted, adoption.state(reference))
    }

    @Test
    fun throwingLegacyCleanupCannotBlockAnAdoptedKeychainValue() {
        val reference = desktopSessionSecretReference("https://cloud.invalid", "alice")
        val expected = "keychain-session-secret".encodeToByteArray()
        val legacy = RecordingSecretStore(
            mutableMapOf(reference.targetName to "legacy-session-secret".encodeToByteArray()),
            failClear = true,
        )
        val primary = RecordingSecretStore(mutableMapOf(reference.targetName to expected))
        val adoption = RecordingSecretStoreAdoption()
        val store = MigratingDesktopSecretStore(primary, legacy, adoption)

        assertContentEquals(expected, store.load(reference))
        assertContentEquals(expected, store.load(reference))
        assertEquals(2, legacy.clearAttempts)
        assertEquals(
            DesktopSecretStoreAdoptionState.AdoptedPendingLegacyCleanup,
            adoption.state(reference),
        )
    }

    @Test
    fun failedLegacyCleanupRetriesWithoutReadingTheStaleValueAgain() {
        val reference = desktopSessionSecretReference("https://cloud.invalid", "alice")
        val expected = "keychain-session-secret".encodeToByteArray()
        val legacy = RecordingSecretStore(
            mutableMapOf(reference.targetName to "legacy-session-secret".encodeToByteArray()),
            failClearAttempts = 1,
        )
        val primary = RecordingSecretStore(mutableMapOf(reference.targetName to expected))
        val adoption = RecordingSecretStoreAdoption()
        val store = MigratingDesktopSecretStore(primary, legacy, adoption)

        assertContentEquals(expected, store.load(reference))
        assertEquals(DesktopSecretStoreAdoptionState.AdoptedPendingLegacyCleanup, adoption.state(reference))
        assertContentEquals(expected, store.load(reference))

        assertEquals(2, legacy.clearAttempts)
        assertEquals(DesktopSecretStoreAdoptionState.AdoptedAndClean, adoption.state(reference))
        assertNull(legacy.load(reference))
    }

    @Test
    fun freshDraftKeyCanBeCreatedWhenNoDraftDependsOnAnUnavailableLegacyStore() {
        val secrets = RecordingSecretStore(failLoadAttempts = 1)
        val provider = PlatformDeckDraftKeyProvider(
            secretStore = secrets,
            legacySecretRequired = { false },
        )

        val key = provider.encryptionKey()

        assertEquals(DesktopDeckCardDraftStore.AES_KEY_BYTES, key.size)
        assertTrue(secrets.values.containsKey(desktopDeckDraftSecretReference().targetName))
    }

    @Test
    fun existingDraftNeverCreatesAReplacementKeyAfterAmbiguousLegacyLookup() {
        val provider = PlatformDeckDraftKeyProvider(
            secretStore = RecordingSecretStore(failLoad = true),
            legacySecretRequired = { true },
        )

        assertFailsWith<DesktopSecretStoreUnavailableException> { provider.encryptionKey() }
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

    private class RecordingSecretStore(
        val values: MutableMap<String, ByteArray> = mutableMapOf(),
        private val failSave: Boolean = false,
        private val failLoad: Boolean = false,
        private var failLoadAttempts: Int = 0,
        private val ignoreClear: Boolean = false,
        private val failClear: Boolean = false,
        private var failClearAttempts: Int = 0,
    ) : DesktopSecretStore {
        var clearAttempts = 0
            private set

        override fun load(reference: DesktopSecretReference): ByteArray? {
            if (failLoad || failLoadAttempts > 0) {
                if (failLoadAttempts > 0) failLoadAttempts -= 1
                throw DesktopSecretStoreUnavailableException("Synthetic unavailable store.")
            }
            return values[reference.targetName]?.copyOf()
        }

        override fun save(reference: DesktopSecretReference, username: String?, secret: ByteArray) {
            if (failSave) throw DesktopSecretStoreUnavailableException("Synthetic unavailable store.")
            values[reference.targetName] = secret.copyOf()
        }

        override fun clear(reference: DesktopSecretReference) {
            clearAttempts += 1
            if (failClear || failClearAttempts > 0) {
                if (failClearAttempts > 0) failClearAttempts -= 1
                error("Synthetic legacy cleanup failure.")
            }
            if (!ignoreClear) values.remove(reference.targetName)
        }
    }

    private class RecordingSecretStoreAdoption : DesktopSecretStoreAdoption {
        private val states = mutableMapOf<String, DesktopSecretStoreAdoptionState>()

        override fun state(reference: DesktopSecretReference): DesktopSecretStoreAdoptionState =
            states[reference.targetName] ?: DesktopSecretStoreAdoptionState.NotAdopted

        override fun markAdopted(reference: DesktopSecretReference) {
            states[reference.targetName] = DesktopSecretStoreAdoptionState.AdoptedPendingLegacyCleanup
        }

        override fun markLegacyCleanupComplete(reference: DesktopSecretReference) {
            check(state(reference) != DesktopSecretStoreAdoptionState.NotAdopted)
            states[reference.targetName] = DesktopSecretStoreAdoptionState.AdoptedAndClean
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
