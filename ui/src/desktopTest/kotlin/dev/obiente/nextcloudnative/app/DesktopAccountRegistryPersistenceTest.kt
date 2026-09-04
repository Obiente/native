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
    fun largeLegacyAccountMigratesThroughChunkedPreferences() = withPreferences { preferences ->
        val session = NextcloudSession(
            serverUrl = "https://cloud.example.test/" + "a".repeat(8_050),
            loginName = "alice",
            appPassword = "private-app-password",
        )
        val diagnostics = mutableListOf<SupportDiagnosticEventDraft>()

        restoreDesktopAccountRegistry(preferences, session, diagnostics::add)

        val encoded = requireNotNull(DesktopAccountRegistryPreferenceStore(preferences).read())
        assertEquals(session.accountId, requireNotNull(decodeNextcloudAccountRegistry(encoded)).activeAccountId)
        assertTrue(encoded.length > Preferences.MAX_VALUE_LENGTH)
        assertNull(preferences.get(DESKTOP_ACCOUNT_REGISTRY_KEY, null))
        assertTrue(diagnostics.isEmpty())
    }

    @Test
    fun preparingALargeRegistryDoesNotWriteMetadata() = withPreferences { preferences ->
        val session = NextcloudSession(
            serverUrl = "https://cloud.example.test/" + "a".repeat(8_050),
            loginName = "alice",
            appPassword = "private-app-password",
        )
        preferences.put("server", "existing-server")
        preferences.put("login", "existing-login")

        val encoded = prepareDesktopAccountRegistry(session)

        assertTrue(encoded.length > Preferences.MAX_VALUE_LENGTH)
        assertEquals("existing-server", preferences.get("server", null))
        assertEquals("existing-login", preferences.get("login", null))
        assertNull(preferences.get(DESKTOP_ACCOUNT_REGISTRY_KEY, null))
    }

    @Test
    fun maximumAccountCountRoundTripsAcrossBoundedPreferenceChunks() = withPreferences { preferences ->
        val accounts = (0 until MAX_LOCAL_ACCOUNTS).map { index ->
            NextcloudSession(
                serverUrl = "https://cloud-$index.example.test/nextcloud",
                loginName = "person-$index-${"x".repeat(120)}",
                appPassword = "not-persisted",
            ).accountRecord()
        }
        val registry = NextcloudAccountRegistry(accounts, accounts.last().id)
        val encoded = encodeNextcloudAccountRegistry(registry)
        val store = DesktopAccountRegistryPreferenceStore(preferences)

        assertTrue(encoded.length > Preferences.MAX_VALUE_LENGTH)
        store.write(encoded)

        assertEquals(encoded, DesktopAccountRegistryPreferenceStore(preferences).read())
        assertTrue(
            preferences.keys()
                .filter { key -> key.startsWith("account_registry_v2.") }
                .map { key -> requireNotNull(preferences.get(key, null)) }
                .all { value -> value.length <= Preferences.MAX_VALUE_LENGTH },
        )
    }

    @Test
    fun failedInactiveGenerationWriteKeepsThePreviouslyCommittedRegistry() = withPreferences { preferences ->
        val session = NextcloudSession(
            serverUrl = "https://cloud.example.test/${"a".repeat(8_050)}",
            loginName = "alice",
            appPassword = "not-persisted",
        )
        val first = prepareDesktopAccountRegistry(session)
        val second = prepareDesktopAccountRegistry(session.copy(loginName = "bob"))
        DesktopAccountRegistryPreferenceStore(preferences).write(first)
        val failingStore = DesktopAccountRegistryPreferenceStore(preferences) {
            error("synthetic inactive generation flush failure")
        }

        assertFailsWith<IllegalStateException> { failingStore.write(second) }

        assertEquals(first, DesktopAccountRegistryPreferenceStore(preferences).read())
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
