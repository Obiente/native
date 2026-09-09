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
import java.util.prefs.BackingStoreException
import java.util.prefs.Preferences
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
        assertEquals(DesktopSecretStoreUnavailableReason.ProviderMissing, failure.reason)
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

        val failure = assertFailsWith<NextcloudSessionLegacyMigrationUnavailableException> {
            MigratingDesktopSecretStore(primary, legacy, adoption).load(reference)
        }

        assertTrue(failure.message.orEmpty().contains("legacy secure-storage provider"))
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
        val store = MacOsKeychainSecretStore(
            api,
            releasedItems::add,
            RecordingMacOsKeychainDeletionRecovery(),
        )
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
    fun unavailableAdoptionMetadataCannotBlockAValidKeychainValue() {
        val reference = desktopSessionSecretReference("https://cloud.invalid", "alice")
        val expected = "keychain-session-secret".encodeToByteArray()
        val store = MigratingDesktopSecretStore(
            primary = RecordingSecretStore(mutableMapOf(reference.targetName to expected)),
            legacy = RecordingSecretStore(),
            adoption = RecordingSecretStoreAdoption(failWrites = true),
        )

        assertContentEquals(expected, store.load(reference))
    }

    @Test
    fun unavailableAdoptionAndLegacyCleanupCannotExposeAKeychainValue() {
        val reference = desktopSessionSecretReference("https://cloud.invalid", "alice")
        val expected = "keychain-session-secret".encodeToByteArray()
        val store = MigratingDesktopSecretStore(
            primary = RecordingSecretStore(mutableMapOf(reference.targetName to expected)),
            legacy = RecordingSecretStore(
                mutableMapOf(reference.targetName to "stale-session-secret".encodeToByteArray()),
                failClear = true,
            ),
            adoption = RecordingSecretStoreAdoption(failWrites = true),
        )

        assertFailsWith<DesktopSecretStoreUnavailableException> { store.load(reference) }
    }

    @Test
    fun queuedLegacyCleanupCannotBlockLocalSignOut() {
        val reference = desktopSessionSecretReference("https://cloud.invalid", "alice")
        val primary = RecordingSecretStore(
            mutableMapOf(reference.targetName to "keychain-session-secret".encodeToByteArray()),
        )
        val legacy = RecordingSecretStore(
            mutableMapOf(reference.targetName to "legacy-session-secret".encodeToByteArray()),
            failClearAttempts = 1,
        )
        val adoption = RecordingSecretStoreAdoption()
        val store = MigratingDesktopSecretStore(primary, legacy, adoption)

        store.clear(reference)

        assertNull(primary.load(reference))
        assertTrue(legacy.values.containsKey(reference.targetName))
        assertEquals(
            DesktopSecretStoreAdoptionState.AdoptedPendingLegacyCleanup,
            adoption.state(reference),
        )

        assertNull(store.load(reference))

        assertNull(legacy.load(reference))
        assertEquals(2, legacy.clearAttempts)
        assertEquals(DesktopSecretStoreAdoptionState.AdoptedAndClean, adoption.state(reference))
    }

    @Test
    fun missingLegacyProviderCannotBlockLocalSignOut() {
        val reference = desktopSessionSecretReference("https://cloud.invalid", "alice")
        val primary = RecordingSecretStore(
            mutableMapOf(reference.targetName to "keychain-session-secret".encodeToByteArray()),
        )
        val adoption = RecordingSecretStoreAdoption()
        val store = MigratingDesktopSecretStore(
            primary,
            SecretToolDesktopSecretStore(
                startProcess = { throw java.io.IOException("synthetic missing executable") },
            ),
            adoption,
        )

        store.clear(reference)

        assertNull(primary.load(reference))
        assertEquals(
            DesktopSecretStoreAdoptionState.AdoptedPendingLegacyCleanup,
            adoption.state(reference),
        )
    }

    @Test
    fun unqueuedLegacyCleanupFailureRemainsActionable() {
        val reference = desktopSessionSecretReference("https://cloud.invalid", "alice")
        val primary = RecordingSecretStore(
            mutableMapOf(reference.targetName to "keychain-session-secret".encodeToByteArray()),
        )
        val legacy = RecordingSecretStore(
            mutableMapOf(reference.targetName to "legacy-session-secret".encodeToByteArray()),
            failClear = true,
        )
        val store = MigratingDesktopSecretStore(
            primary,
            legacy,
            RecordingSecretStoreAdoption(failWrites = true),
        )

        assertFailsWith<DesktopSecretLegacyCleanupUnavailableException> { store.clear(reference) }

        assertNull(primary.load(reference))
        assertTrue(legacy.values.containsKey(reference.targetName))
    }

    @Test
    fun failedKeychainClearStillRemovesTheLegacyCredential() {
        val reference = desktopSessionSecretReference("https://cloud.invalid", "alice")
        val primary = RecordingSecretStore(
            mutableMapOf(reference.targetName to "keychain-session-secret".encodeToByteArray()),
            failClear = true,
        )
        val legacy = RecordingSecretStore(
            mutableMapOf(reference.targetName to "legacy-session-secret".encodeToByteArray()),
        )
        val store = MigratingDesktopSecretStore(primary, legacy, RecordingSecretStoreAdoption())

        assertFailsWith<IllegalStateException> { store.clear(reference) }

        assertNull(legacy.load(reference))
        assertEquals(1, legacy.clearAttempts)
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
    fun freshMacOsDraftKeyCanBypassAMissingLegacyProvider() {
        val primary = RecordingSecretStore()
        val migrating = MigratingDesktopSecretStore(
            primary = primary,
            legacy = SecretToolDesktopSecretStore(
                startProcess = { throw java.io.IOException("synthetic missing executable") },
            ),
            adoption = RecordingSecretStoreAdoption(),
        )
        val provider = PlatformDeckDraftKeyProvider(
            secretStore = migrating,
            legacySecretRequired = { false },
        )

        val key = provider.encryptionKey()

        assertEquals(DesktopDeckCardDraftStore.AES_KEY_BYTES, key.size)
        assertTrue(primary.values.containsKey(desktopDeckDraftSecretReference().targetName))
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
    fun existingDraftNeverCreatesAReplacementForAConfirmedMissingKey() {
        val secrets = RecordingSecretStore()
        val provider = PlatformDeckDraftKeyProvider(
            secretStore = secrets,
            legacySecretRequired = { true },
        )

        assertFailsWith<DeckCardDraftResetRequiredException> { provider.encryptionKey() }
        assertFalse(secrets.values.containsKey(desktopDeckDraftSecretReference().targetName))
    }

    @Test
    fun existingDraftNeverCreatesAReplacementForAMalformedKey() {
        val reference = desktopDeckDraftSecretReference()
        val secrets = RecordingSecretStore(
            mutableMapOf(reference.targetName to "not-base64".encodeToByteArray()),
        )
        val provider = PlatformDeckDraftKeyProvider(
            secretStore = secrets,
            legacySecretRequired = { true },
        )

        assertFailsWith<DeckCardDraftResetRequiredException> { provider.encryptionKey() }
        assertContentEquals("not-base64".encodeToByteArray(), secrets.values.getValue(reference.targetName))
    }

    @Test
    fun macOsKeychainDenialIsActionableAndDoesNotExposeCredentialIdentity() {
        val store = MacOsKeychainSecretStore(
            api = FakeMacOsKeychainApi(findFailure = -25_293),
            releaseItem = {},
            deletionRecovery = RecordingMacOsKeychainDeletionRecovery(),
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
    fun malformedMacOsKeychainValueIsRemovedAndReturnsToSignIn() {
        val api = FakeMacOsKeychainApi(initialSecret = ByteArray(2_561))
        val store = MacOsKeychainSecretStore(
            api,
            releaseItem = {},
            deletionRecovery = RecordingMacOsKeychainDeletionRecovery(),
        )
        val reference = desktopSessionSecretReference("https://cloud.invalid", "alice")

        assertNull(store.load(reference))
        assertEquals(1, api.deleteAttempts)
        assertNull(store.load(reference))
    }

    @Test
    fun macOsKeychainConcurrentAddRaceUpdatesTheExistingItem() {
        val api = FakeMacOsKeychainApi(duplicateOnFirstAdd = true)
        val store = MacOsKeychainSecretStore(
            api,
            releaseItem = {},
            deletionRecovery = RecordingMacOsKeychainDeletionRecovery(),
        )
        val reference = desktopSessionSecretReference("https://cloud.invalid", "alice")
        val expected = "replacement-synthetic-secret".encodeToByteArray()

        store.save(reference, "alice", expected)

        assertContentEquals(expected, store.load(reference))
    }

    @Test
    fun failedMacOsKeychainDeletionRetriesAfterProcessRestart() {
        val api = FakeMacOsKeychainApi(
            initialSecret = "synthetic-session-secret".encodeToByteArray(),
            deleteFailureAttempts = 1,
        )
        val recovery = RecordingMacOsKeychainDeletionRecovery()
        val reference = desktopSessionSecretReference("https://cloud.invalid", "alice")
        val firstProcess = MacOsKeychainSecretStore(api, releaseItem = {}, deletionRecovery = recovery)

        assertFailsWith<DesktopSecretStoreUnavailableException> { firstProcess.clear(reference) }
        assertEquals(setOf(reference.targetName), recovery.pendingTargetNames())
        assertEquals(1, api.deleteAttempts)

        val restarted = MacOsKeychainSecretStore(api, releaseItem = {}, deletionRecovery = recovery)

        assertNull(restarted.load(reference))
        assertEquals(emptySet(), recovery.pendingTargetNames())
        assertEquals(2, api.deleteAttempts)
    }

    @Test
    fun ambiguousDeletionCompletionRemainsRetryableBeforeReplacementSave() {
        val api = FakeMacOsKeychainApi(initialSecret = "old-synthetic-secret".encodeToByteArray())
        val recovery = RecordingMacOsKeychainDeletionRecovery(failCompleteAttempts = 1)
        val reference = desktopSessionSecretReference("https://cloud.invalid", "alice")
        val store = MacOsKeychainSecretStore(api, releaseItem = {}, deletionRecovery = recovery)

        assertFailsWith<DesktopSecretStoreUnavailableException> { store.clear(reference) }
        assertEquals(setOf(reference.targetName), recovery.pendingTargetNames())

        val replacement = "replacement-synthetic-secret".encodeToByteArray()
        store.save(reference, "alice", replacement)

        assertContentEquals(replacement, store.load(reference))
        assertEquals(emptySet(), recovery.pendingTargetNames())
        assertEquals(1, api.deleteAttempts)
    }

    @Test
    fun unavailableRecoveryJournalPreventsUntrackedKeychainDeletion() {
        val api = FakeMacOsKeychainApi(initialSecret = "synthetic-session-secret".encodeToByteArray())
        val recovery = RecordingMacOsKeychainDeletionRecovery(failPending = true)
        val reference = desktopSessionSecretReference("https://cloud.invalid", "alice")
        val store = MacOsKeychainSecretStore(api, releaseItem = {}, deletionRecovery = recovery)

        assertFailsWith<DesktopSecretDeletionRecoveryUnavailableException> { store.clear(reference) }

        assertEquals(0, api.deleteAttempts)
        assertContentEquals("synthetic-session-secret".encodeToByteArray(), store.load(reference))
    }

    @Test
    fun ambiguousJournalCompletionStaysPendingUntilDurablyRecorded() {
        val preferences = Preferences.userRoot().node(
            "dev/obiente/nextcloudnative/test-keychain-deletion/${UUID.randomUUID()}",
        )
        var flushAttempts = 0
        val recovery = PreferencesMacOsKeychainDeletionRecovery(preferences) { node ->
            flushAttempts += 1
            if (flushAttempts == 2) throw BackingStoreException("Synthetic flush failure.")
            node.flush()
        }
        val targetName = desktopSessionSecretReference("https://cloud.invalid", "alice").targetName
        try {
            recovery.markPending(targetName)

            assertFailsWith<BackingStoreException> { recovery.markComplete(targetName) }
            assertEquals(setOf(targetName), recovery.pendingTargetNames())

            recovery.markComplete(targetName)
            assertEquals(emptySet(), recovery.pendingTargetNames())
        } finally {
            preferences.removeNode()
        }
    }

    private class FakeMacOsKeychainApi(
        private val findFailure: Int? = null,
        private val duplicateOnFirstAdd: Boolean = false,
        initialSecret: ByteArray? = null,
        private var deleteFailureAttempts: Int = 0,
    ) : MacOsKeychainApi {
        private var secret: ByteArray? = initialSecret
        private var addAttempted = false
        private var returnedSecret: Memory? = null
        private val item = Memory(1)
        var lastService: String? = null
            private set
        var lastAccount: String? = null
            private set
        var deleteAttempts: Int = 0
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
            deleteAttempts += 1
            if (deleteFailureAttempts > 0) {
                deleteFailureAttempts -= 1
                return -25_308
            }
            secret = null
            return 0
        }

        override fun SecKeychainItemFreeContent(attributes: Pointer?, secretData: Pointer?): Int {
            returnedSecret?.clear()
            returnedSecret = null
            return 0
        }
    }

    private class RecordingMacOsKeychainDeletionRecovery(
        private val failPending: Boolean = false,
        private var failCompleteAttempts: Int = 0,
    ) : MacOsKeychainDeletionRecovery {
        private val pending = linkedSetOf<String>()

        override fun pendingTargetNames(): Set<String> = pending.toSet()

        override fun markPending(targetName: String) {
            if (failPending) error("Synthetic unavailable deletion recovery.")
            pending += targetName
        }

        override fun markComplete(targetName: String) {
            if (failCompleteAttempts > 0) {
                failCompleteAttempts -= 1
                error("Synthetic ambiguous deletion completion.")
            }
            pending -= targetName
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

    private class RecordingSecretStoreAdoption(
        private val failWrites: Boolean = false,
    ) : DesktopSecretStoreAdoption {
        private val states = mutableMapOf<String, DesktopSecretStoreAdoptionState>()

        override fun state(reference: DesktopSecretReference): DesktopSecretStoreAdoptionState =
            states[reference.targetName] ?: DesktopSecretStoreAdoptionState.NotAdopted

        override fun markAdopted(reference: DesktopSecretReference) {
            if (failWrites) error("Synthetic unavailable adoption metadata.")
            states[reference.targetName] = DesktopSecretStoreAdoptionState.AdoptedPendingLegacyCleanup
        }

        override fun markLegacyCleanupComplete(reference: DesktopSecretReference) {
            if (failWrites) error("Synthetic unavailable adoption metadata.")
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
            label = "nati.ve test credential",
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
