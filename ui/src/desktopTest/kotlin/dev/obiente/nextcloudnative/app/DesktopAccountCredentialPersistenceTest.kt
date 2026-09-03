package dev.obiente.nextcloudnative.app

import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopAccountCredentialPersistenceTest {
    @Test
    fun legacyCredentialMigratesAndRestartsWithTheExactActiveAccount() = withStore { preferences, secrets ->
        val session = firstSession()
        putLegacySession(preferences, secrets, session)
        val persistence = persistence(preferences, secrets)

        assertEquals(session, persistence.loadActiveSession())
        assertNull(secrets.load(desktopSessionSecretReference(session.serverUrl, session.loginName)))
        assertEquals(session.appPassword, secrets.load(desktopAccountSecretReference(session.accountId))?.decodeToString())

        val restarted = persistence(preferences, secrets)
        assertEquals(session, restarted.loadActiveSession())
        assertEquals(session.accountId, restarted.activeAccountId())
    }

    @Test
    fun twoCredentialSlotsRestartAndSelectTheRequestedAccount() = withStore { preferences, secrets ->
        val first = firstSession()
        val second = secondSession()
        val persistence = persistence(preferences, secrets)
        persistence.saveSession(first)
        persistence.saveSession(second)

        val restarted = persistence(preferences, secrets)

        assertEquals(setOf(first.accountRecord(), second.accountRecord()), restarted.listAccounts().toSet())
        assertEquals(second.accountId, restarted.activeAccountId())
        assertEquals(first, restarted.selectAccount(first.accountId))
        assertEquals(first, persistence(preferences, secrets).loadActiveSession())
    }

    @Test
    fun selectionFlushesRegistryAndLegacyMetadataBeforeReturning() = withStore { preferences, secrets ->
        var flushCount = 0
        val persistence = persistence(preferences, secrets) { flushCount += 1 }
        persistence.saveSession(firstSession())
        persistence.saveSession(secondSession())

        assertEquals(firstSession(), persistence.selectAccount(firstSession().accountId))
        assertEquals(3, flushCount)
        assertEquals(firstSession().serverUrl, preferences.get("server", null))
        assertEquals(firstSession().loginName, preferences.get("login", null))
    }

    @Test
    fun unsupportedFutureRegistryUsesLegacyCredentialWithoutOverwritingIt() = withStore { preferences, secrets ->
        val session = firstSession()
        val futureRegistry = """{"version":2,"futureAccounts":[{"id":"future"}]}"""
        putLegacySession(preferences, secrets, session)
        preferences.put(DESKTOP_ACCOUNT_REGISTRY_KEY, futureRegistry)
        val diagnostics = mutableListOf<SupportDiagnosticEventDraft>()

        val persistence = persistence(preferences, secrets, diagnostics)
        val restored = persistence.loadActiveSession()

        assertEquals(session, restored)
        assertEquals(listOf(session.accountRecord()), persistence.listAccounts())
        assertEquals(session.accountId, persistence.activeAccountId())
        assertEquals(futureRegistry, preferences.get(DESKTOP_ACCOUNT_REGISTRY_KEY, null))
        assertNull(secrets.load(desktopAccountSecretReference(session.accountId)))
        assertEquals(
            listOf("ACCOUNT_REGISTRY_VERSION_UNSUPPORTED"),
            diagnostics.mapNotNull { it.code }.distinct(),
        )
    }

    @Test
    fun malformedRegistryFallsBackWithoutDiscardingTheLegacyCredential() = withStore { preferences, secrets ->
        val session = firstSession()
        putLegacySession(preferences, secrets, session)
        preferences.put(DESKTOP_ACCOUNT_REGISTRY_KEY, "{not-json")
        val diagnostics = mutableListOf<SupportDiagnosticEventDraft>()

        val restored = persistence(preferences, secrets, diagnostics).loadActiveSession()

        assertEquals(session, restored)
        assertEquals(session.accountId, decodeRegistry(preferences).activeAccountId)
        assertEquals(listOf("ACCOUNT_REGISTRY_MALFORMED"), diagnostics.mapNotNull { it.code })
        assertDiagnosticsExcludePrivateValues(diagnostics)
    }

    @Test
    fun legacyMigrationFlushesBeforeDeletingTheOnlyLegacyCredential() = withStore { preferences, secrets ->
        val session = firstSession()
        val legacyReference = desktopSessionSecretReference(session.serverUrl, session.loginName)
        putLegacySession(preferences, secrets, session)
        var legacyPresentAtFlush = false

        val restored = persistence(preferences, secrets) {
            legacyPresentAtFlush = secrets.load(legacyReference) != null
        }.loadActiveSession()

        assertEquals(session, restored)
        assertTrue(legacyPresentAtFlush)
        assertNull(secrets.load(legacyReference))
    }

    @Test
    fun failedMigrationFlushKeepsLegacyCredentialAndRollsBackCachedMetadata() =
        withStore { preferences, secrets ->
            val session = firstSession()
            val legacyReference = desktopSessionSecretReference(session.serverUrl, session.loginName)
            putLegacySession(preferences, secrets, session)
            val diagnostics = mutableListOf<SupportDiagnosticEventDraft>()
            var flushAttempts = 0

            val restored = persistence(preferences, secrets, diagnostics) {
                flushAttempts += 1
                if (flushAttempts == 1) error("synthetic flush failure")
            }.loadActiveSession()

            assertEquals(session, restored)
            assertNull(preferences.get(DESKTOP_ACCOUNT_REGISTRY_KEY, null))
            assertNotNull(secrets.load(legacyReference))
            assertEquals(
                listOf("ACCOUNT_CREDENTIAL_STORE_WRITE_FAILED", "ACCOUNT_CREDENTIAL_STORE_MIGRATION_FAILED"),
                diagnostics.mapNotNull { it.code },
            )
        }

    @Test
    fun activeRegistryMismatchNeverBindsTheLegacyPasswordToAnotherAccount() = withStore { preferences, secrets ->
        val first = firstSession()
        val second = secondSession()
        putLegacySession(preferences, secrets, first)
        val registry = NextcloudAccountRegistry.Empty
            .upsertAndSelect(first.accountRecord())
            .upsertAndSelect(second.accountRecord())
        preferences.put(DESKTOP_ACCOUNT_REGISTRY_KEY, encodeNextcloudAccountRegistry(registry))
        val diagnostics = mutableListOf<SupportDiagnosticEventDraft>()

        val restored = persistence(preferences, secrets, diagnostics).loadActiveSession()

        assertNull(restored)
        assertEquals(registry, decodeRegistry(preferences))
        assertNull(secrets.load(desktopAccountSecretReference(second.accountId)))
        assertEquals(listOf("ACCOUNT_CREDENTIAL_ACTIVE_MISMATCH"), diagnostics.mapNotNull { it.code })
        assertDiagnosticsExcludePrivateValues(diagnostics)
    }

    @Test
    fun removingTheActiveAccountRetainsOtherCredentialsWithoutSelectingOne() = withStore { preferences, secrets ->
        val first = firstSession()
        val second = secondSession()
        val persistence = persistence(preferences, secrets)
        persistence.saveSession(first)
        persistence.saveSession(second)

        assertTrue(persistence.removeAccount(second.accountId))

        val restarted = persistence(preferences, secrets)
        assertNull(restarted.loadActiveSession())
        assertNull(restarted.activeAccountId())
        assertEquals(listOf(first.accountRecord()), restarted.listAccounts())
        assertNotNull(secrets.load(desktopAccountSecretReference(first.accountId)))
        assertNull(secrets.load(desktopAccountSecretReference(second.accountId)))
    }

    @Test
    fun activeAccountWithMissingCredentialCanStillBeRemoved() = withStore { preferences, secrets ->
        val first = firstSession()
        val second = secondSession()
        val persistence = persistence(preferences, secrets)
        persistence.saveSession(first)
        persistence.saveSession(second)
        secrets.clear(desktopAccountSecretReference(second.accountId))

        assertNull(persistence.loadActiveSession())
        assertTrue(persistence.removeAccount(second.accountId))

        val restarted = persistence(preferences, secrets)
        assertNull(restarted.activeAccountId())
        assertEquals(listOf(first.accountRecord()), restarted.listAccounts())
    }

    @Test
    fun failedCredentialDeletionKeepsTheAccountRegisteredForRetry() = withStore { preferences, secrets ->
        val first = firstSession()
        val second = secondSession()
        val persistence = persistence(preferences, secrets)
        persistence.saveSession(first)
        persistence.saveSession(second)
        secrets.failClears = true

        assertFailsWith<IllegalStateException> {
            persistence.removeAccount(second.accountId)
        }

        assertEquals(second.accountId, persistence.activeAccountId())
        assertEquals(setOf(first.accountRecord(), second.accountRecord()), persistence.listAccounts().toSet())
        assertNotNull(secrets.load(desktopAccountSecretReference(second.accountId)))

        secrets.failClears = false
        assertTrue(persistence.removeAccount(second.accountId))
        assertNull(persistence(preferences, secrets).activeAccountId())
        assertNull(secrets.load(desktopAccountSecretReference(second.accountId)))
    }

    @Test
    fun failedRegistryFlushAfterCredentialDeletionKeepsADeletionRetryPath() =
        withStore { preferences, secrets ->
            val first = firstSession()
            val second = secondSession()
            var flushAttempts = 0
            val persistence = persistence(preferences, secrets) {
                flushAttempts += 1
                if (flushAttempts == 3) error("synthetic removal flush failure")
                preferences.flush()
            }
            persistence.saveSession(first)
            persistence.saveSession(second)

            assertFailsWith<IllegalStateException> {
                persistence.removeAccount(second.accountId)
            }

            assertEquals(second.accountId, persistence.activeAccountId())
            assertEquals(setOf(first.accountRecord(), second.accountRecord()), persistence.listAccounts().toSet())
            assertNull(secrets.load(desktopAccountSecretReference(second.accountId)))
            assertTrue(persistence.removeAccount(second.accountId))
            assertNull(persistence(preferences, secrets).activeAccountId())
        }

    @Test
    fun oversizedRegistryFailsBeforeCredentialOrMetadataWrites() = withStore { preferences, secrets ->
        val session = NextcloudSession(
            serverUrl = "https://cloud.example.test/" + "a".repeat(8_050),
            loginName = "alice",
            appPassword = "private-app-password",
        )

        assertFailsWith<IllegalArgumentException> {
            persistence(preferences, secrets).saveSession(session)
        }

        assertNull(preferences.get(DESKTOP_ACCOUNT_REGISTRY_KEY, null))
        assertNull(preferences.get("server", null))
        assertNull(preferences.get("login", null))
        assertNull(secrets.load(desktopAccountSecretReference(session.accountId)))
    }

    @Test
    fun migrationFailureAttachesABoundedCauseWithoutPrivateValues() = withStore { preferences, secrets ->
        val session = firstSession()
        putLegacySession(preferences, secrets, session)
        secrets.failSaves = true
        val diagnostics = mutableListOf<SupportDiagnosticEventDraft>()

        val restored = persistence(preferences, secrets, diagnostics).loadActiveSession()

        assertEquals(session, restored)
        assertNull(preferences.get(DESKTOP_ACCOUNT_REGISTRY_KEY, null))
        val diagnostic = diagnostics.single { it.code == "ACCOUNT_CREDENTIAL_STORE_MIGRATION_FAILED" }
        assertNotNull(diagnostic.exception)
        assertNull(diagnostic.exception.message)
        assertDiagnosticsExcludePrivateValues(diagnostics)
    }

    private fun persistence(
        preferences: Preferences,
        secrets: MemorySecretStore,
        diagnostics: MutableList<SupportDiagnosticEventDraft> = mutableListOf(),
        flushPreferences: () -> Unit = preferences::flush,
    ) = DesktopAccountCredentialPersistence(preferences, secrets, diagnostics::add, flushPreferences)

    private fun putLegacySession(
        preferences: Preferences,
        secrets: MemorySecretStore,
        session: NextcloudSession,
    ) {
        preferences.put("server", session.serverUrl)
        preferences.put("login", session.loginName)
        secrets.save(
            desktopSessionSecretReference(session.serverUrl, session.loginName),
            session.loginName,
            session.appPassword.encodeToByteArray(),
        )
    }

    private fun decodeRegistry(preferences: Preferences): NextcloudAccountRegistry = requireNotNull(
        decodeNextcloudAccountRegistry(requireNotNull(preferences.get(DESKTOP_ACCOUNT_REGISTRY_KEY, null))),
    )

    private fun assertDiagnosticsExcludePrivateValues(diagnostics: List<SupportDiagnosticEventDraft>) {
        val rendered = diagnostics.joinToString()
        assertFalse(rendered.contains("private-app-password"))
        assertFalse(rendered.contains("second-private-password"))
        assertFalse(rendered.contains("alice"))
        assertFalse(rendered.contains("cloud.example.test"))
    }

    private fun firstSession() = NextcloudSession(
        serverUrl = "https://cloud.example.test",
        loginName = "alice",
        appPassword = "private-app-password",
    )

    private fun secondSession() = NextcloudSession(
        serverUrl = "https://second.example.test/nextcloud",
        loginName = "bob",
        appPassword = "second-private-password",
    )

    private fun withStore(block: (Preferences, MemorySecretStore) -> Unit) {
        val preferences = Preferences.userRoot().node(
            "dev/obiente/nextcloudnative/tests/account-credentials/${UUID.randomUUID()}",
        )
        try {
            block(preferences, MemorySecretStore())
        } finally {
            preferences.removeNode()
        }
    }

    private class MemorySecretStore : DesktopSecretStore {
        private val values = mutableMapOf<String, ByteArray>()
        var failSaves = false
        var failClears = false

        override fun load(reference: DesktopSecretReference): ByteArray? = values[reference.targetName]?.copyOf()

        override fun save(reference: DesktopSecretReference, username: String?, secret: ByteArray) {
            if (failSaves) error("private-app-password at cloud.example.test for alice")
            values[reference.targetName] = secret.copyOf()
        }

        override fun clear(reference: DesktopSecretReference) {
            if (failClears) error("synthetic secret deletion failure")
            values.remove(reference.targetName)
        }
    }
}
