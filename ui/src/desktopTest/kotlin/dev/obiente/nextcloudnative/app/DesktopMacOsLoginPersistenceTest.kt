package dev.obiente.nextcloudnative.app

import java.util.UUID
import java.util.prefs.Preferences
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopMacOsLoginPersistenceTest {
    private val session = NextcloudSession("https://cloud.example.test", "alice", "synthetic-app-password")

    @Test
    fun `native macOS Keychain saves and restores a fresh account without the legacy executable`() {
        org.junit.Assume.assumeTrue(System.getProperty("os.name").startsWith("Mac"))
        withFixture { prefs, _, adoption ->
            val primary = MacOsKeychainSecretStore(deletionRecovery = PreferencesMacOsKeychainDeletionRecovery(prefs.node("cleanup")))
            val account = session.copy(loginName = "synthetic-${UUID.randomUUID()}")
            val reference = desktopAccountSecretReference(account.accountId)
            try {
                val persistence = DesktopAccountCredentialPersistence(prefs, migrating(primary, adoption), {})
                assertEquals(account, persistence.saveSession(account))
                assertEquals(account, DesktopAccountCredentialPersistence(prefs, migrating(primary, adoption), {}).loadActiveSession())
            } finally {
                primary.clear(reference)
            }
        }
    }

    @Test
    fun `fresh account persists and restores through Keychain when secret tool is missing`() = withFixture { prefs, primary, adoption ->
        val store = migrating(primary, adoption)
        val persistence = DesktopAccountCredentialPersistence(prefs, store, {})
        assertNull(persistence.loadActiveSession())
        assertEquals(session, persistence.saveSession(session))
        assertContentEquals(session.appPassword.encodeToByteArray(), primary.load(desktopAccountSecretReference(session.accountId)))
        assertEquals(session, DesktopAccountCredentialPersistence(prefs, migrating(primary, adoption), {}).loadActiveSession())
        assertEquals(DesktopSecretStoreAdoptionState.AdoptedPendingLegacyCleanup, adoption.state(desktopAccountSecretReference(session.accountId)))
        assertNull(prefs.get("accountCredentialSavePhase", null))
    }

    @Test
    fun `registered credential with unavailable migration is preserved during replacement`() = withFixture { prefs, primary, adoption ->
        val registryStore = DesktopAccountRegistryPreferenceStore(prefs)
        registryStore.write(encodeNextcloudAccountRegistry(NextcloudAccountRegistry.Empty.upsertAndSelect(session.accountRecord())))
        val before = registryStore.read()
        val persistence = DesktopAccountCredentialPersistence(prefs, migrating(primary, adoption), {})
        assertFailsWith<NextcloudSessionLegacyMigrationUnavailableException> {
            persistence.saveSession(session.copy(appPassword = "replacement"))
        }
        assertEquals(before, registryStore.read())
        assertTrue(primary.values.isEmpty())
        assertEquals(DesktopSecretStoreAdoptionState.NotAdopted, adoption.state(desktopAccountSecretReference(session.accountId)))
    }

    @Test
    fun `legacy account metadata still requires migration before adding an account`() = withFixture { prefs, primary, adoption ->
        prefs.put("server", session.serverUrl)
        prefs.put("login", session.loginName)
        val persistence = DesktopAccountCredentialPersistence(prefs, migrating(primary, adoption), {})
        assertFailsWith<NextcloudSessionLegacyMigrationUnavailableException> { persistence.saveSession(session) }
        assertEquals(session.serverUrl, prefs.get("server", null))
        assertTrue(primary.values.isEmpty())
    }

    @Test
    fun `locked primary store and cancellation cannot be mistaken for a new account`() = withFixture { prefs, primary, adoption ->
        val persistence = DesktopAccountCredentialPersistence(prefs, migrating(primary, adoption), {})
        primary.failure = DesktopSecretStoreUnavailableException("Synthetic locked Keychain")
        assertFailsWith<DesktopSecretStoreUnavailableException> { persistence.saveSession(session) }
        primary.failure = CancellationException("synthetic cancellation")
        assertFailsWith<CancellationException> { persistence.saveSession(session) }
        assertTrue(primary.values.isEmpty())
        assertNull(DesktopAccountRegistryPreferenceStore(prefs).read())
    }

    @Test
    fun `an installed but locked legacy provider still blocks replacement`() = withFixture { prefs, primary, adoption ->
        val legacy = MemorySecrets().apply { failure = DesktopSecretStoreUnavailableException("Synthetic locked legacy store") }
        val persistence = DesktopAccountCredentialPersistence(prefs, MigratingDesktopSecretStore(primary, legacy, adoption), {})
        assertFailsWith<DesktopSecretStoreUnavailableException> { persistence.saveSession(session) }
        assertTrue(primary.values.isEmpty())
        assertNull(DesktopAccountRegistryPreferenceStore(prefs).read())
    }

    @Test
    fun `failed registry publication removes new Keychain secret and permits retry`() = withFixture { prefs, primary, adoption ->
        var failPublication = true
        val persistence = DesktopAccountCredentialPersistence(prefs, migrating(primary, adoption), {}, {
            if (failPublication && DesktopAccountRegistryPreferenceStore(prefs).read() != null) {
                failPublication = false
                error("Synthetic registry publication failure")
            }
            prefs.flush()
        })
        assertFailsWith<IllegalStateException> { persistence.saveSession(session) }
        assertNull(primary.load(desktopAccountSecretReference(session.accountId)))
        assertNull(DesktopAccountRegistryPreferenceStore(prefs).read())
        assertEquals(session, persistence.saveSession(session))
    }

    private fun migrating(primary: DesktopSecretStore, adoption: Adoption) = MigratingDesktopSecretStore(
        primary,
        SecretToolDesktopSecretStore(startProcess = { throw java.io.IOException("Synthetic missing legacy executable") }),
        adoption,
    )

    private fun withFixture(block: (Preferences, MemorySecrets, Adoption) -> Unit) {
        val prefs = Preferences.userRoot().node("dev/obiente/nextcloudnative/tests/mac-login/${UUID.randomUUID()}")
        try { block(prefs, MemorySecrets(), Adoption()) } finally { prefs.removeNode() }
    }

    private class MemorySecrets : DesktopSecretStore {
        val values = mutableMapOf<String, ByteArray>()
        var failure: RuntimeException? = null
        override fun load(reference: DesktopSecretReference): ByteArray? {
            failure?.let { throw it }
            return values[reference.targetName]?.copyOf()
        }
        override fun save(reference: DesktopSecretReference, username: String?, secret: ByteArray) {
            failure?.let { throw it }
            values[reference.targetName] = secret.copyOf()
        }
        override fun clear(reference: DesktopSecretReference) { values.remove(reference.targetName) }
    }

    private class Adoption : DesktopSecretStoreAdoption {
        private val states = mutableMapOf<String, DesktopSecretStoreAdoptionState>()
        override fun state(reference: DesktopSecretReference) = states[reference.targetName] ?: DesktopSecretStoreAdoptionState.NotAdopted
        override fun markAdopted(reference: DesktopSecretReference) { states[reference.targetName] = DesktopSecretStoreAdoptionState.AdoptedPendingLegacyCleanup }
        override fun markLegacyCleanupComplete(reference: DesktopSecretReference) { states[reference.targetName] = DesktopSecretStoreAdoptionState.AdoptedAndClean }
    }
}
