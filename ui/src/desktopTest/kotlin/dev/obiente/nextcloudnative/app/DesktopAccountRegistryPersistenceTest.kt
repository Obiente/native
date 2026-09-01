package dev.obiente.nextcloudnative.app

import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
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
    fun unsupportedFutureRegistryIsReportedWithoutBeingOverwritten() = withPreferences { preferences ->
        val session = session()
        val diagnostics = mutableListOf<SupportDiagnosticEventDraft>()
        val futureRegistry = """{"version":2,"futureAccounts":[{"id":"future"}]}"""
        preferences.put(DESKTOP_ACCOUNT_REGISTRY_KEY, futureRegistry)

        restoreDesktopAccountRegistry(preferences, session, diagnostics::add)

        assertEquals(futureRegistry, preferences.get(DESKTOP_ACCOUNT_REGISTRY_KEY, null))
        assertEquals(listOf("ACCOUNT_REGISTRY_VERSION_UNSUPPORTED"), diagnostics.mapNotNull { it.code })
    }

    @Test
    fun oversizedMigrationReportsABoundedCauseWithoutChangingPreferences() = withPreferences { preferences ->
        val session = NextcloudSession(
            serverUrl = "https://cloud.example.test/" + "a".repeat(8_050),
            loginName = "alice",
            appPassword = "private-app-password",
        )
        val diagnostics = mutableListOf<SupportDiagnosticEventDraft>()

        restoreDesktopAccountRegistry(preferences, session, diagnostics::add)

        assertNull(preferences.get(DESKTOP_ACCOUNT_REGISTRY_KEY, null))
        val diagnostic = diagnostics.single()
        assertEquals("ACCOUNT_REGISTRY_MIGRATION_FAILED", diagnostic.code)
        assertNotNull(diagnostic.exception)
        assertNull(diagnostic.exception.message)
        assertFalse(diagnostic.toString().contains(session.appPassword))
        assertFalse(diagnostic.toString().contains(session.serverUrl))
    }

    @Test
    fun desktopValueLimitIsValidatedBeforeAnyMetadataWrite() = withPreferences { preferences ->
        val session = NextcloudSession(
            serverUrl = "https://cloud.example.test/" + "a".repeat(8_050),
            loginName = "alice",
            appPassword = "private-app-password",
        )
        preferences.put("server", "existing-server")
        preferences.put("login", "existing-login")

        assertFailsWith<IllegalArgumentException> { prepareDesktopAccountRegistry(session) }

        assertEquals("existing-server", preferences.get("server", null))
        assertEquals("existing-login", preferences.get("login", null))
        assertNull(preferences.get(DESKTOP_ACCOUNT_REGISTRY_KEY, null))
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
