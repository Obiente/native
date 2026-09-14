package dev.obiente.nextcloudnative.app

import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DesktopCredentialRollbackCompletionTest {
    @Test
    fun journalClearFailureAfterRestorationCanRetryWithoutTheBackupSecret() = withStore { fixture ->
        listOf("rollback", "secret-writing").forEach { phase ->
            fixture.preparePendingRollback(phase)
            var failClear = true
            val persistence = fixture.persistence {
                if (failClear && fixture.preferences.get(PHASE_KEY, null) == null) {
                    failClear = false
                    error("synthetic journal-clear flush failure")
                }
                fixture.preferences.flush()
            }

            assertFailsWith<DesktopCredentialRollbackRecoveryUnavailableException> {
                persistence.loadActiveSession()
            }

            assertEquals("rollback-completed", fixture.preferences.get(PHASE_KEY, null))
            assertEquals(fixture.original.appPassword, fixture.primarySecret())
            assertNull(fixture.secrets.load(fixture.rollbackReference))
            fixture.assertRestartRecovered()
        }
    }

    @Test
    fun completionMarkerFlushFailureRetainsTheBackupForAnotherRollback() = withStore { fixture ->
        fixture.preparePendingRollback("rollback")
        var failCompletion = true
        val persistence = fixture.persistence {
            if (failCompletion && fixture.preferences.get(PHASE_KEY, null) == "rollback-completed") {
                failCompletion = false
                error("synthetic rollback-completion flush failure")
            }
            fixture.preferences.flush()
        }

        assertFailsWith<DesktopCredentialRollbackRecoveryUnavailableException> {
            persistence.loadActiveSession()
        }

        assertEquals("rollback", fixture.preferences.get(PHASE_KEY, null))
        assertNotNull(fixture.secrets.load(fixture.rollbackReference))
        fixture.assertRestartRecovered()
    }

    @Test
    fun restartAfterRecoveryDeletesItsBackupKeepsTheOriginalActiveAccount() = withStore { fixture ->
        fixture.preparePendingRollback("secret-writing")
        fixture.crashAfterBackupDeletion()

        assertFailsWith<SimulatedRollbackProcessExit> { fixture.persistence().loadActiveSession() }

        assertEquals("rollback-completed", fixture.preferences.get(PHASE_KEY, null))
        assertNull(fixture.secrets.load(fixture.rollbackReference))
        fixture.assertRestartRecovered()
    }

    @Test
    fun restartAfterImmediateRollbackDeletesItsBackupKeepsTheOriginalActiveAccount() = withStore { fixture ->
        fixture.secrets.failNextSaveTarget = fixture.primaryReference.targetName
        fixture.crashAfterBackupDeletion()

        assertFailsWith<SimulatedRollbackProcessExit> {
            fixture.persistence().saveSession(fixture.original.copy(appPassword = "replacement-password"))
        }

        assertEquals("rollback-completed", fixture.preferences.get(PHASE_KEY, null))
        assertEquals(fixture.original.appPassword, fixture.primarySecret())
        assertNull(fixture.secrets.load(fixture.rollbackReference))
        fixture.assertRestartRecovered()
    }

    @Test
    fun immediateRollbackJournalClearFailureCanRetryWithoutTheBackupSecret() = withStore { fixture ->
        fixture.secrets.failNextSaveTarget = fixture.primaryReference.targetName
        var failClear = true
        val persistence = fixture.persistence {
            if (failClear && fixture.preferences.get(PHASE_KEY, null) == null) {
                failClear = false
                error("synthetic immediate rollback journal-clear failure")
            }
            fixture.preferences.flush()
        }

        assertFailsWith<DesktopCredentialRollbackRecoveryUnavailableException> {
            persistence.saveSession(fixture.original.copy(appPassword = "replacement-password"))
        }

        assertEquals("rollback-completed", fixture.preferences.get(PHASE_KEY, null))
        assertNull(fixture.secrets.load(fixture.rollbackReference))
        fixture.assertRestartRecovered()
    }

    private fun withStore(test: (RollbackFixture) -> Unit) {
        val preferences = Preferences.userRoot().node("desktop-rollback-completion-test-${UUID.randomUUID()}")
        try {
            test(RollbackFixture(preferences))
        } finally {
            preferences.removeNode()
        }
    }
}

private class RollbackFixture(val preferences: Preferences) {
    val secrets = RollbackSecretStore()
    val original = NextcloudSession("https://cloud.example.test", "alice", "original-password")
    private val active = NextcloudSession("https://other.example.test", "bob", "other-password")
    val primaryReference = desktopAccountSecretReference(original.accountId)
    val rollbackReference = desktopAccountCredentialRollbackReference(original.accountId)

    init {
        persistence().saveSession(original)
        persistence().saveSession(active)
    }

    fun persistence(flush: () -> Unit = preferences::flush) =
        DesktopAccountCredentialPersistence(preferences, secrets, recordDiagnostic = {}, flushPreferences = flush)

    fun preparePendingRollback(phase: String) {
        secrets.save(primaryReference, original.loginName, "replacement-password".encodeToByteArray())
        secrets.save(rollbackReference, original.loginName, original.appPassword.encodeToByteArray())
        preferences.put("accountCredentialSaveServer", original.serverUrl)
        preferences.put("accountCredentialSaveLogin", original.loginName)
        preferences.put(PHASE_KEY, phase)
        preferences.flush()
    }

    fun crashAfterBackupDeletion() {
        secrets.afterClear = { reference ->
            if (reference == rollbackReference) {
                secrets.afterClear = {}
                throw SimulatedRollbackProcessExit()
            }
        }
    }

    fun primarySecret(): String? = secrets.load(primaryReference)?.decodeToString()

    fun assertRestartRecovered() {
        val restarted = persistence()
        assertEquals(active, restarted.loadActiveSession())
        assertEquals(original, restarted.loadSession(original.accountId))
        assertNull(secrets.load(rollbackReference))
        assertNull(preferences.get(PHASE_KEY, null))
        assertNull(preferences.get("accountCredentialSaveServer", null))
        assertNull(preferences.get("accountCredentialSaveLogin", null))
    }
}

private class RollbackSecretStore : DesktopSecretStore {
    private val values = mutableMapOf<String, ByteArray>()
    var failNextSaveTarget: String? = null
    var afterClear: (DesktopSecretReference) -> Unit = {}

    override fun load(reference: DesktopSecretReference): ByteArray? = values[reference.targetName]?.copyOf()

    override fun save(reference: DesktopSecretReference, username: String?, secret: ByteArray) {
        if (reference.targetName == failNextSaveTarget) {
            failNextSaveTarget = null
            error("synthetic primary credential save failure")
        }
        values[reference.targetName] = secret.copyOf()
    }

    override fun clear(reference: DesktopSecretReference) {
        values.remove(reference.targetName)
        afterClear(reference)
    }
}

private class SimulatedRollbackProcessExit : Error()
private const val PHASE_KEY = "accountCredentialSavePhase"
