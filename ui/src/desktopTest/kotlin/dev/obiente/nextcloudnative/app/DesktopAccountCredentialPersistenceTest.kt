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
    fun credentialFreeAccountReadsDoNotRetrySecretCleanup() = withStore { preferences, secrets ->
        val session = firstSession()
        val persistence = persistence(preferences, secrets)
        persistence.saveSession(session)
        preferences.put("accountLegacyCleanupServer", session.serverUrl)
        preferences.put("accountLegacyCleanupLogin", session.loginName)
        preferences.put("accountCredentialSaveServer", session.serverUrl)
        preferences.put("accountCredentialSaveLogin", session.loginName)
        secrets.resetOperationCounts()

        assertEquals(listOf(session.accountRecord()), persistence.listAccounts())
        assertEquals(session.accountId, persistence.activeAccountId())
        assertEquals(0, secrets.loadCount)
        assertEquals(0, secrets.clearCount)
        assertEquals(session.serverUrl, preferences.get("accountLegacyCleanupServer", null))
        assertEquals(session.serverUrl, preferences.get("accountCredentialSaveServer", null))
    }

    @Test
    fun accountRemovalJournalsBothCurrentAndLegacyCredentialCleanup() = withStore { preferences, secrets ->
        val first = firstSession()
        val persistence = persistence(preferences, secrets)
        persistence.saveSession(first)
        persistence.saveSession(secondSession())
        secrets.save(
            desktopSessionSecretReference(first.serverUrl, first.loginName),
            first.loginName,
            first.appPassword.encodeToByteArray(),
        )
        preferences.put("accountLegacyCleanupServer", first.serverUrl)
        preferences.put("accountLegacyCleanupLogin", first.loginName)
        secrets.failClears = true

        assertTrue(persistence.removeAccount(first.accountId))
        assertFalse(persistence.listAccounts().any { account -> account.id == first.accountId })
        assertNotNull(secrets.load(desktopAccountSecretReference(first.accountId)))
        assertNotNull(secrets.load(desktopSessionSecretReference(first.serverUrl, first.loginName)))
        assertEquals(first.accountId.storageKey, preferences.get("accountCredentialRemovals", null))

        secrets.failClears = false
        persistence.loadActiveSession()
        assertNull(secrets.load(desktopAccountSecretReference(first.accountId)))
        assertNull(secrets.load(desktopSessionSecretReference(first.serverUrl, first.loginName)))
        assertNull(preferences.get("accountLegacyCleanupServer", null))
        assertNull(preferences.get("accountLegacyCleanupLogin", null))
    }

    @Test
    fun selectionFlushesRegistryAndLegacyMetadataBeforeReturning() = withStore { preferences, secrets ->
        var flushCount = 0
        val persistence = persistence(preferences, secrets) { flushCount += 1 }
        persistence.saveSession(firstSession())
        persistence.saveSession(secondSession())

        assertEquals(firstSession(), persistence.selectAccount(firstSession().accountId))
        assertEquals(10, flushCount)
        assertEquals(firstSession().serverUrl, preferences.get("server", null))
        assertEquals(firstSession().loginName, preferences.get("login", null))
    }

    @Test
    fun failedRegistryFlushRemovesANewlyCreatedCredentialSlot() = withStore { preferences, secrets ->
        val session = firstSession()
        val persistence = persistence(preferences, secrets) {
            error("synthetic registry flush failure")
        }

        assertFailsWith<IllegalStateException> { persistence.saveSession(session) }

        assertNull(preferences.get(DESKTOP_ACCOUNT_REGISTRY_KEY, null))
        assertNull(secrets.load(desktopAccountSecretReference(session.accountId)))
    }

    @Test
    fun failedNewCredentialRollbackRetainsTheRecoveryJournal() = withStore { preferences, secrets ->
        val session = firstSession()
        var flushCount = 0
        val persistence = persistence(preferences, secrets) {
            flushCount += 1
            if (flushCount == 2) error("synthetic registry flush failure")
            preferences.flush()
        }
        secrets.failClears = true

        assertFailsWith<IllegalStateException> { persistence.saveSession(session) }

        assertNotNull(secrets.load(desktopAccountSecretReference(session.accountId)))
        assertEquals(session.serverUrl, preferences.get("accountCredentialSaveServer", null))
        assertEquals(session.loginName, preferences.get("accountCredentialSaveLogin", null))

        secrets.failClears = false
        assertNull(persistence(preferences, secrets).loadActiveSession())
        assertNull(secrets.load(desktopAccountSecretReference(session.accountId)))
        assertNull(preferences.get("accountCredentialSaveServer", null))
        assertNull(preferences.get("accountCredentialSaveLogin", null))
    }

    @Test
    fun startupRecoveryRemovesANewCredentialWhoseRegistryCommitNeverCompleted() =
        withStore { preferences, secrets ->
            val session = firstSession()
            preferences.put("accountCredentialSaveServer", session.serverUrl)
            preferences.put("accountCredentialSaveLogin", session.loginName)
            secrets.save(
                desktopAccountSecretReference(session.accountId),
                session.loginName,
                session.appPassword.encodeToByteArray(),
            )

            assertNull(persistence(preferences, secrets).loadActiveSession())

            assertNull(secrets.load(desktopAccountSecretReference(session.accountId)))
            assertNull(preferences.get("accountCredentialSaveServer", null))
            assertNull(preferences.get("accountCredentialSaveLogin", null))
        }

    @Test
    fun startupRecoveryKeepsANewCredentialAfterItsRegistryCommitCompleted() =
        withStore { preferences, secrets ->
            val session = firstSession()
            preferences.put(DESKTOP_ACCOUNT_REGISTRY_KEY, encodeNextcloudAccountRegistry(
                NextcloudAccountRegistry.Empty.upsertAndSelect(session.accountRecord()),
            ))
            preferences.put("accountCredentialSaveServer", session.serverUrl)
            preferences.put("accountCredentialSaveLogin", session.loginName)
            secrets.save(
                desktopAccountSecretReference(session.accountId),
                session.loginName,
                session.appPassword.encodeToByteArray(),
            )

            assertEquals(session, persistence(preferences, secrets).loadActiveSession())

            assertNotNull(secrets.load(desktopAccountSecretReference(session.accountId)))
            assertNull(preferences.get("accountCredentialSaveServer", null))
            assertNull(preferences.get("accountCredentialSaveLogin", null))
        }

    @Test
    fun startupRecoveryPreservesPendingCredentialWhenRegistryVersionIsUnreadable() =
        withStore { preferences, secrets ->
            val session = firstSession()
            preferences.put(DESKTOP_ACCOUNT_REGISTRY_KEY, """{"version":2,"accounts":[]}""")
            preferences.put("accountCredentialSaveServer", session.serverUrl)
            preferences.put("accountCredentialSaveLogin", session.loginName)
            secrets.save(
                desktopAccountSecretReference(session.accountId),
                session.loginName,
                session.appPassword.encodeToByteArray(),
            )

            assertNull(persistence(preferences, secrets).loadActiveSession())

            assertNotNull(secrets.load(desktopAccountSecretReference(session.accountId)))
            assertEquals(session.serverUrl, preferences.get("accountCredentialSaveServer", null))
        }

    @Test
    fun failedRegistryFlushRestoresThePreviousCredentialDuringReauthentication() =
        withStore { preferences, secrets ->
            val original = firstSession()
            var failFlush = false
            val persistence = persistence(preferences, secrets) {
                if (failFlush) error("synthetic registry flush failure")
                preferences.flush()
            }
            persistence.saveSession(original)
            failFlush = true

            assertFailsWith<IllegalStateException> {
                persistence.saveSession(original.copy(appPassword = "replacement-password"))
            }

            assertEquals(
                original.appPassword,
                secrets.load(desktopAccountSecretReference(original.accountId))?.decodeToString(),
            )
            assertEquals(original, persistence(preferences, secrets).loadActiveSession())
        }

    @Test
    fun failedReplacementRollbackIsFinalizedFromTheCredentialJournalOnRestart() =
        withStore { preferences, secrets ->
            val original = firstSession()
            val replacement = original.copy(appPassword = "replacement-password")
            var flushCount = 0
            var failFlushOnAttempt: Int? = null
            val persistence = persistence(preferences, secrets) {
                flushCount += 1
                if (flushCount == failFlushOnAttempt) error("synthetic registry flush failure")
                preferences.flush()
            }
            persistence.saveSession(original)
            secrets.failSaveOnAttempt = secrets.saveCount + 2
            failFlushOnAttempt = flushCount + 2

            assertFailsWith<IllegalStateException> { persistence.saveSession(replacement) }

            assertEquals(
                replacement.appPassword,
                secrets.load(desktopAccountSecretReference(original.accountId))?.decodeToString(),
            )
            assertEquals(original.serverUrl, preferences.get("accountCredentialSaveServer", null))

            failFlushOnAttempt = null
            assertEquals(replacement, persistence(preferences, secrets).loadActiveSession())
            assertNull(preferences.get("accountCredentialSaveServer", null))
            assertNull(preferences.get("accountCredentialSaveLogin", null))
        }

    @Test
    fun canonicalEquivalentReauthenticationPreservesDesktopStorageIdentity() =
        withStore { preferences, secrets ->
            val original = NextcloudSession(
                serverUrl = "https://CLOUD.example.test:443/nextcloud",
                loginName = "alice",
                appPassword = "original-password",
            )
            val replacement = NextcloudSession(
                serverUrl = "https://cloud.example.test/nextcloud/",
                loginName = "alice",
                appPassword = "replacement-password",
            )
            assertEquals(original.accountId, replacement.accountId)
            val persistence = persistence(preferences, secrets)
            persistence.saveSession(original)

            val persisted = persistence.saveSession(replacement)

            val restored = persistence(preferences, secrets).loadActiveSession()
            assertEquals(original.serverUrl, persisted.serverUrl)
            assertEquals(replacement.appPassword, persisted.appPassword)
            assertEquals(original.serverUrl, restored?.serverUrl)
            assertEquals(replacement.appPassword, restored?.appPassword)
            assertEquals(desktopFileCacheAccountId(original), restored?.let(::desktopFileCacheAccountId))
            assertEquals(original.serverUrl, decodeRegistry(preferences).activeAccount?.serverUrl)
        }

    @Test
    fun unsupportedFutureRegistryPreservesLegacyCredentialWithoutExposingIt() = withStore { preferences, secrets ->
        val session = firstSession()
        val futureRegistry = """{"version":2,"futureAccounts":[{"id":"future"}]}"""
        putLegacySession(preferences, secrets, session)
        preferences.put(DESKTOP_ACCOUNT_REGISTRY_KEY, futureRegistry)
        val diagnostics = mutableListOf<SupportDiagnosticEventDraft>()

        val persistence = persistence(preferences, secrets, diagnostics)
        val restored = persistence.loadActiveSession()

        assertNull(restored)
        assertTrue(persistence.listAccounts().isEmpty())
        assertNull(persistence.activeAccountId())
        assertEquals(futureRegistry, preferences.get(DESKTOP_ACCOUNT_REGISTRY_KEY, null))
        assertNull(secrets.load(desktopAccountSecretReference(session.accountId)))
        assertNotNull(secrets.load(desktopSessionSecretReference(session.serverUrl, session.loginName)))
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
            legacyPresentAtFlush = legacyPresentAtFlush || secrets.load(legacyReference) != null
        }.loadActiveSession()

        assertEquals(session, restored)
        assertTrue(legacyPresentAtFlush)
        assertNull(secrets.load(legacyReference))
    }

    @Test
    fun failedLegacyCleanupIsRetriedAfterMigration() = withStore { preferences, secrets ->
        val session = firstSession()
        val legacyReference = desktopSessionSecretReference(session.serverUrl, session.loginName)
        putLegacySession(preferences, secrets, session)
        val persistence = persistence(preferences, secrets)
        secrets.failClears = true

        assertEquals(session, persistence.loadActiveSession())
        assertNotNull(secrets.load(legacyReference))

        secrets.failClears = false
        assertEquals(session, persistence.loadActiveSession())
        assertNull(secrets.load(legacyReference))
    }

    @Test
    fun pendingCleanupNeverDeletesTheOnlyReadableLegacyCredential() = withStore { preferences, secrets ->
        val session = firstSession()
        val legacyReference = desktopSessionSecretReference(session.serverUrl, session.loginName)
        putLegacySession(preferences, secrets, session)
        secrets.failSaves = true

        assertEquals(session, persistence(preferences, secrets).loadActiveSession())
        assertNotNull(secrets.load(legacyReference))

        secrets.failSaves = false
        assertEquals(session, persistence(preferences, secrets).loadActiveSession())
        assertNull(secrets.load(legacyReference))
    }

    @Test
    fun accountRemovalRetriesPendingLegacyCleanupAfterSelectionChanged() =
        withStore { preferences, secrets ->
            val migrated = firstSession()
            val other = secondSession()
            val legacyReference = desktopSessionSecretReference(migrated.serverUrl, migrated.loginName)
            putLegacySession(preferences, secrets, migrated)
            val persistence = persistence(preferences, secrets)
            secrets.failClears = true

            assertEquals(migrated, persistence.loadActiveSession())
            persistence.saveSession(other)
            assertNotNull(secrets.load(legacyReference))

            secrets.failClears = false
            assertTrue(persistence.removeAccount(migrated.accountId))
            assertNull(secrets.load(legacyReference))
        }

    @Test
    fun secureStoreReadFailureIsNotReportedAsMissingCredentials() = withStore { preferences, secrets ->
        val persistence = persistence(preferences, secrets)
        persistence.saveSession(firstSession())
        secrets.loadFailure = DesktopSecretStoreUnavailableException("synthetic locked keychain")

        assertEquals(
            NextcloudSessionLoadState.SecureStorageUnavailable,
            loadNextcloudSessionSafely(persistence::loadActiveSession),
        )
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
    fun failedCredentialDeletionKeepsAPostCommitRetryJournal() = withStore { preferences, secrets ->
        val first = firstSession()
        val second = secondSession()
        val persistence = persistence(preferences, secrets)
        persistence.saveSession(first)
        persistence.saveSession(second)
        secrets.failClears = true

        assertTrue(persistence.removeAccount(second.accountId))

        assertNull(persistence.activeAccountId())
        assertEquals(listOf(first.accountRecord()), persistence.listAccounts())
        assertNotNull(secrets.load(desktopAccountSecretReference(second.accountId)))
        assertEquals(second.accountId.storageKey, preferences.get("accountCredentialRemovals", null))

        secrets.failClears = false
        assertNull(persistence(preferences, secrets).loadActiveSession())
        assertNull(secrets.load(desktopAccountSecretReference(second.accountId)))
        assertNull(preferences.get("accountCredentialRemovals", null))
    }

    @Test
    fun removalMarkersSurviveProcessExitAfterTheRegistryCommit() = withStore { preferences, secrets ->
        val first = firstSession()
        val removed = secondSession()
        var crashDuringRemoval = false
        val persistence = persistence(preferences, secrets) {
            preferences.flush()
            if (crashDuringRemoval && decodeRegistry(preferences).accounts.none { it.id == removed.accountId }) {
                throw SimulatedProcessExit()
            }
        }
        persistence.saveSession(first)
        persistence.saveSession(removed)
        secrets.save(
            desktopSessionSecretReference(removed.serverUrl, removed.loginName),
            removed.loginName,
            removed.appPassword.encodeToByteArray(),
        )
        crashDuringRemoval = true

        assertFailsWith<SimulatedProcessExit> { persistence.removeAccount(removed.accountId) }

        assertFalse(decodeRegistry(preferences).accounts.any { it.id == removed.accountId })
        assertEquals(removed.accountId.storageKey, preferences.get("accountCredentialRemovals", null))
        assertEquals(removed.serverUrl, preferences.get("accountLegacyCleanupServer", null))
        assertNotNull(secrets.load(desktopAccountSecretReference(removed.accountId)))
        assertNotNull(secrets.load(desktopSessionSecretReference(removed.serverUrl, removed.loginName)))

        crashDuringRemoval = false
        persistence(preferences, secrets).loadActiveSession()
        assertNull(secrets.load(desktopAccountSecretReference(removed.accountId)))
        assertNull(secrets.load(desktopSessionSecretReference(removed.serverUrl, removed.loginName)))
        assertNull(preferences.get("accountCredentialRemovals", null))
        assertNull(preferences.get("accountLegacyCleanupServer", null))
    }

    @Test
    fun removalJournalNeverDeletesAStillRegisteredCredential() = withStore { preferences, secrets ->
        val session = firstSession()
        val persistence = persistence(preferences, secrets)
        persistence.saveSession(session)
        preferences.put("accountCredentialRemovals", session.accountId.storageKey)
        preferences.flush()

        assertEquals(session, persistence.loadActiveSession())
        assertNotNull(secrets.load(desktopAccountSecretReference(session.accountId)))
        assertNull(preferences.get("accountCredentialRemovals", null))
    }

    @Test
    fun failedRegistryFlushLeavesTheCredentialAndAccountIntact() =
        withStore { preferences, secrets ->
            val first = firstSession()
            val second = secondSession()
            var flushAttempts = 0
            val persistence = persistence(preferences, secrets) {
                flushAttempts += 1
                if (flushAttempts == 10) error("synthetic removal flush failure")
                preferences.flush()
            }
            persistence.saveSession(first)
            persistence.saveSession(second)

            assertFailsWith<IllegalStateException> {
                persistence.removeAccount(second.accountId)
            }

            assertEquals(second.accountId, persistence.activeAccountId())
            assertEquals(setOf(first.accountRecord(), second.accountRecord()), persistence.listAccounts().toSet())
            assertNotNull(secrets.load(desktopAccountSecretReference(second.accountId)))
            assertNull(preferences.get("accountCredentialRemovals", null))
            assertTrue(persistence.removeAccount(second.accountId))
            assertNull(persistence(preferences, secrets).activeAccountId())
        }

    @Test
    fun largeRegistryPersistsCredentialAndMetadataThroughPreferenceChunks() = withStore { preferences, secrets ->
        val session = NextcloudSession(
            serverUrl = "https://cloud.example.test/" + "a".repeat(8_050),
            loginName = "alice",
            appPassword = "private-app-password",
        )

        assertEquals(session, persistence(preferences, secrets).saveSession(session))

        assertNull(preferences.get(DESKTOP_ACCOUNT_REGISTRY_KEY, null))
        assertEquals(session.serverUrl, preferences.get("server", null))
        assertEquals(session.loginName, preferences.get("login", null))
        assertNotNull(secrets.load(desktopAccountSecretReference(session.accountId)))
        assertEquals(session.accountId, decodeRegistry(preferences).activeAccountId)
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
        decodeNextcloudAccountRegistry(
            requireNotNull(DesktopAccountRegistryPreferenceStore(preferences).read()),
        ),
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
        var failSaveOnAttempt: Int? = null
        var failClears = false
        var loadFailure: RuntimeException? = null
        var loadCount = 0
            private set
        var saveCount = 0
            private set
        var clearCount = 0
            private set

        override fun load(reference: DesktopSecretReference): ByteArray? {
            loadCount += 1
            loadFailure?.let { throw it }
            return values[reference.targetName]?.copyOf()
        }

        override fun save(reference: DesktopSecretReference, username: String?, secret: ByteArray) {
            saveCount += 1
            if (failSaves || saveCount == failSaveOnAttempt) {
                error("private-app-password at cloud.example.test for alice")
            }
            values[reference.targetName] = secret.copyOf()
        }

        override fun clear(reference: DesktopSecretReference) {
            clearCount += 1
            if (failClears) error("synthetic secret deletion failure")
            values.remove(reference.targetName)
        }

        fun resetOperationCounts() {
            loadCount = 0
            clearCount = 0
        }
    }
}

private class SimulatedProcessExit : Error()
