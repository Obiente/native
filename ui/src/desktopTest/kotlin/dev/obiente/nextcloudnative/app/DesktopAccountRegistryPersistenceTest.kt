package dev.obiente.nextcloudnative.app

import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopAccountRegistryPersistenceTest {
    @Test
    fun legacyPreferencesMigrateAndRestartWithTheSameActiveAccount() = withPreferences { preferences ->
        val session = session()
        val diagnostics = mutableListOf<SupportDiagnosticEventDraft>()

        restoreDesktopAccountRegistry(preferences, session, diagnostics::add)
        val migrated = preferences.get(DESKTOP_ACCOUNT_REGISTRY_KEY, null)
        val registry = decodeNextcloudAccountRegistry(requireNotNull(migrated))

        assertEquals(session.accountId, requireNotNull(registry).activeAccountId)
        assertTrue(diagnostics.isEmpty())

        restoreDesktopAccountRegistry(preferences, session, diagnostics::add)
        assertEquals(migrated, preferences.get(DESKTOP_ACCOUNT_REGISTRY_KEY, null))
        assertTrue(diagnostics.isEmpty())
    }

    @Test
    fun malformedRegistryIsReplacedWithoutLeakingPrivateSessionValues() = withPreferences { preferences ->
        val session = session()
        val diagnostics = mutableListOf<SupportDiagnosticEventDraft>()
        preferences.put(DESKTOP_ACCOUNT_REGISTRY_KEY, "{not-json")

        restoreDesktopAccountRegistry(preferences, session, diagnostics::add)

        val restored = decodeNextcloudAccountRegistry(
            requireNotNull(preferences.get(DESKTOP_ACCOUNT_REGISTRY_KEY, null)),
        )
        assertEquals(session.accountId, requireNotNull(restored).activeAccountId)
        assertEquals(listOf("ACCOUNT_REGISTRY_MALFORMED"), diagnostics.mapNotNull { it.code })
        val renderedDiagnostics = diagnostics.joinToString()
        assertFalse(renderedDiagnostics.contains(session.serverUrl))
        assertFalse(renderedDiagnostics.contains(session.loginName))
        assertFalse(renderedDiagnostics.contains(session.appPassword))
    }

    @Test
    fun explicitSaveAndRemovalOwnOnlyCredentialFreeMetadata() = withPreferences { preferences ->
        val session = session()

        persistDesktopAccountRegistry(preferences, session)
        val encoded = requireNotNull(preferences.get(DESKTOP_ACCOUNT_REGISTRY_KEY, null))

        assertFalse(encoded.contains(session.appPassword))
        assertNotNull(decodeNextcloudAccountRegistry(encoded))
        clearDesktopAccountRegistry(preferences)
        assertNull(preferences.get(DESKTOP_ACCOUNT_REGISTRY_KEY, null))
    }

    private fun session() = NextcloudSession(
        serverUrl = "https://cloud.example.test",
        loginName = "alice",
        appPassword = "private-app-password",
    )

    private fun withPreferences(block: (Preferences) -> Unit) {
        val preferences = Preferences.userRoot().node(
            "dev/obiente/nextcloudnative/tests/account-registry/${UUID.randomUUID()}",
        )
        try {
            block(preferences)
        } finally {
            preferences.removeNode()
        }
    }
}
