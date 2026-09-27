package dev.obiente.nextcloudnative.app

import java.util.UUID
import java.util.prefs.Preferences
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DesktopCredentialMigrationCancellationTest {
    @Test
    fun cancelledLegacySecretMigrationDoesNotReturnAnActiveSessionOrReportFailure() = withFixture { fixture ->
        fixture.store.cancelSave = true
        assertSame(fixture.cancelled, assertFailsWith<CancellationException> { fixture.persistence.loadActiveSession() })
        assertTrue(fixture.events.isEmpty())
        assertTrue(fixture.store.values.containsKey(fixture.legacy.targetName))
        fixture.store.cancelSave = false
        assertEquals(fixture.session, fixture.persistence.loadActiveSession())
    }

    @Test
    fun cancelledMigrationRegistryFlushPreservesLegacySecretAndStopsActivation() = withFixture { fixture ->
        fixture.cancelFlush = true
        assertSame(fixture.cancelled, assertFailsWith<CancellationException> { fixture.persistence.loadActiveSession() })
        assertTrue(fixture.store.values.containsKey(fixture.legacy.targetName))
        assertTrue(fixture.events.none { it.toString().contains("MIGRATION_FAILED") })
        fixture.cancelFlush = false
        assertEquals(fixture.session, fixture.persistence.loadActiveSession())
    }

    private fun withFixture(test: (Fixture) -> Unit) {
        val preferences = Preferences.userRoot().node("dev/obiente/nextcloudnative/tests/migration-cancellation/${UUID.randomUUID()}")
        try {
            test(Fixture(preferences))
        } finally {
            preferences.removeNode()
        }
    }

    private class Fixture(val preferences: Preferences) {
        val session = NextcloudSession("https://cloud.example.test", "synthetic-user", "synthetic-password")
        val cancelled = CancellationException("synthetic cancellation")
        val legacy = desktopSessionSecretReference(session.serverUrl, session.loginName)
        val store = Store(cancelled)
        val events = mutableListOf<SupportDiagnosticEventDraft>()
        var cancelFlush = false
        val persistence = DesktopAccountCredentialPersistence(preferences, store, events::add) {
            if (cancelFlush) throw cancelled
            preferences.flush()
        }

        init {
            preferences.put("server", session.serverUrl)
            preferences.put("login", session.loginName)
            store.values[legacy.targetName] = session.appPassword.encodeToByteArray()
        }
    }

    private class Store(val cancelled: CancellationException) : DesktopSecretStore {
        val values = mutableMapOf<String, ByteArray>()
        var cancelSave = false
        override fun load(reference: DesktopSecretReference): ByteArray? = values[reference.targetName]?.copyOf()
        override fun save(reference: DesktopSecretReference, username: String?, secret: ByteArray) {
            if (cancelSave) throw cancelled
            values[reference.targetName] = secret.copyOf()
        }
        override fun clear(reference: DesktopSecretReference) {
            values.remove(reference.targetName)
        }
    }
}
