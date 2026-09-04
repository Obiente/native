package dev.obiente.nextcloudnative.app

import java.util.prefs.Preferences
import kotlinx.coroutines.CancellationException

internal class DesktopAccountCredentialPersistence(
    private val preferences: Preferences,
    private val secretStore: DesktopSecretStore,
    private val recordDiagnostic: (SupportDiagnosticEventDraft) -> Unit,
    private val flushPreferences: () -> Unit = preferences::flush,
) {
    fun loadActiveSession(): NextcloudSession? {
        retryPendingCredentialSave()
        retryPendingLegacyCredentialCleanup()
        val read = readRegistry()
        if (read.registry == null) {
            return restoreLegacySession(read.encoded != null)
        }
        val active = read.registry.activeAccount ?: return null
        return loadSession(active.id)
    }

    fun listAccounts(): List<NextcloudAccountRecord> {
        val read = readRegistry()
        if (read.registry != null) return read.registry.accounts
        return readLegacyAccountRecord()?.let(::listOf).orEmpty()
    }

    fun activeAccountId(): NextcloudAccountId? {
        val read = readRegistry()
        if (read.registry != null) return read.registry.activeAccountId
        return readLegacyAccountRecord()?.id
    }

    fun loadSession(accountId: NextcloudAccountId): NextcloudSession? {
        retryPendingCredentialSave()
        retryPendingLegacyCredentialCleanup()
        val registry = readRegistry().registry ?: return null
        val record = registry.accounts.firstOrNull { account -> account.id == accountId } ?: return null
        val secret = loadSecret(desktopAccountSecretReference(accountId))
        if (secret != null) return record.toSession(secret)

        val legacy = loadLegacySession() ?: return null
        if (legacy.accountId != accountId || legacy.accountRecord() != record) {
            recordCredentialDiagnostic("ACCOUNT_CREDENTIAL_ACTIVE_MISMATCH", "account-credentials.restore")
            return null
        }
        migrateLegacyCredential(legacy)
        return legacy
    }

    fun saveSession(session: NextcloudSession): NextcloudSession {
        retryPendingCredentialSave()
        retryPendingLegacyCredentialCleanup()
        val read = readRegistry()
        val registry = read.registry
            ?: restoreLegacySession(read.encoded != null)?.let { requireNotNull(readRegistry().registry) }
            ?: if (read.encoded == null) NextcloudAccountRegistry.Empty else throw invalidRegistryForMutation()
        val previousRecord = registry.accounts.firstOrNull { account -> account.id == session.accountId }
        val persistedSession = previousRecord
            ?.let { record -> session.copy(serverUrl = record.serverUrl, loginName = record.loginName) }
            ?: session
        val updatedRegistry = registry.upsertAndSelect(persistedSession.accountRecord())
        val encodedRegistry = prepareRegistry(updatedRegistry)
        val secretReference = desktopAccountSecretReference(persistedSession.accountId)
        val previousSecret = loadSecretForRollback(secretReference)
        val journalNewCredential = previousRecord == null
        if (journalNewCredential) persistPendingCredentialSave(persistedSession)
        try {
            saveSecret(persistedSession)
            persistAccountState(encodedRegistry, updatedRegistry.activeAccount)
        } catch (failure: Exception) {
            try {
                if (previousSecret == null) {
                    secretStore.clear(secretReference)
                } else {
                    secretStore.save(
                        secretReference,
                        previousRecord?.loginName,
                        previousSecret,
                    )
                }
            } catch (rollbackFailure: Exception) {
                failure.addSuppressed(rollbackFailure)
                recordCredentialDiagnostic(
                    "ACCOUNT_CREDENTIAL_STORE_ROLLBACK_FAILED",
                    "account-credentials.persist",
                    rollbackFailure,
                )
            }
            if (journalNewCredential) clearPendingCredentialSave()
            throw failure
        }
        if (journalNewCredential) clearPendingCredentialSave()
        return persistedSession
    }

    fun selectAccount(accountId: NextcloudAccountId): NextcloudSession? {
        retryPendingCredentialSave()
        retryPendingLegacyCredentialCleanup()
        val registry = readRegistry().registry ?: return null
        val session = loadSession(accountId) ?: return null
        val selected = requireNotNull(registry.select(accountId))
        persistAccountState(prepareRegistry(selected), selected.activeAccount)
        return session
    }

    fun removeAccount(accountId: NextcloudAccountId): Boolean {
        retryPendingCredentialSave()
        retryPendingLegacyCredentialCleanup()
        val registry = readRegistry().registry ?: return false
        val record = registry.accounts.firstOrNull { account -> account.id == accountId } ?: return false
        val clearLegacyCredential = legacyMetadataMatches(record)
        val updated = registry.remove(accountId)
        clearSecret(desktopAccountSecretReference(accountId))
        if (clearLegacyCredential) {
            clearSecret(desktopSessionSecretReference(record.serverUrl, record.loginName))
        }
        persistAccountState(prepareRegistry(updated), updated.activeAccount)
        return true
    }

    private fun restoreLegacySession(malformedRegistry: Boolean): NextcloudSession? {
        val legacy = loadLegacySession() ?: run {
            if (malformedRegistry) {
                recordCredentialDiagnostic("ACCOUNT_REGISTRY_MALFORMED", "account-registry.restore")
            }
            return null
        }
        val restored = restoreNextcloudAccountRegistry(
            encoded = preferences.get(DESKTOP_ACCOUNT_REGISTRY_KEY, null),
            legacySession = legacy,
        )
        restored.recoveryReason?.diagnosticCode?.let { code ->
            recordCredentialDiagnostic(code, "account-registry.restore")
        }
        if (!restored.needsPersistence) return legacy
        try {
            val encodedRegistry = prepareRegistry(restored.registry)
            saveSecret(legacy)
            persistAccountState(
                encodedRegistry,
                restored.registry.activeAccount,
                pendingLegacyCleanup = legacy,
            )
        } catch (failure: Exception) {
            recordCredentialDiagnostic(
                code = "ACCOUNT_CREDENTIAL_STORE_MIGRATION_FAILED",
                operation = "account-credentials.migrate",
                failure = failure,
            )
            return legacy
        }
        clearLegacyCredentialAfterMigration(legacy)
        return legacy
    }

    private fun loadLegacySession(): NextcloudSession? {
        val server = preferences.get(KEY_SERVER, null) ?: return null
        val login = preferences.get(KEY_LOGIN, null) ?: return null
        val password = loadSecret(desktopSessionSecretReference(server, login)) ?: return null
        return NextcloudSession(server, login, password)
    }

    private fun readLegacyAccountRecord(): NextcloudAccountRecord? {
        val server = preferences.get(KEY_SERVER, null) ?: return null
        val login = preferences.get(KEY_LOGIN, null) ?: return null
        return runCatching { NextcloudSession(server, login, appPassword = "").accountRecord() }.getOrNull()
    }

    private fun migrateLegacyCredential(session: NextcloudSession) {
        persistPendingLegacyCredentialCleanup(session)
        saveSecret(session)
        clearLegacyCredentialAfterMigration(session)
    }

    private fun clearLegacyCredentialAfterMigration(session: NextcloudSession) {
        retryPendingLegacyCredentialCleanup(session)
    }

    private fun retryPendingCredentialSave() {
        val server = preferences.get(KEY_PENDING_CREDENTIAL_SAVE_SERVER, null)
        val login = preferences.get(KEY_PENDING_CREDENTIAL_SAVE_LOGIN, null)
        if (server == null && login == null) return
        if (server.isNullOrBlank() || login.isNullOrBlank()) {
            recordCredentialDiagnostic(
                "ACCOUNT_CREDENTIAL_STORE_ROLLBACK_FAILED",
                "account-credentials.recover",
            )
            return
        }
        val accountId = try {
            deriveNextcloudAccountId(server, login)
        } catch (failure: Exception) {
            recordCredentialDiagnostic(
                "ACCOUNT_CREDENTIAL_STORE_ROLLBACK_FAILED",
                "account-credentials.recover",
                failure,
            )
            return
        }
        val registryRead = readRegistry()
        if (registryRead.encoded != null && registryRead.registry == null) {
            recordCredentialDiagnostic(
                "ACCOUNT_CREDENTIAL_STORE_ROLLBACK_FAILED",
                "account-credentials.recover",
            )
            return
        }
        val credentialCommitted = registryRead.registry
            ?.accounts
            ?.any { account -> account.id == accountId } == true
        if (!credentialCommitted) {
            try {
                secretStore.clear(desktopAccountSecretReference(accountId))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                recordCredentialDiagnostic(
                    "ACCOUNT_CREDENTIAL_STORE_ROLLBACK_FAILED",
                    "account-credentials.recover",
                    failure,
                )
                return
            }
        }
        clearPendingCredentialSave()
    }

    private fun persistPendingCredentialSave(session: NextcloudSession) {
        val previousServer = preferences.get(KEY_PENDING_CREDENTIAL_SAVE_SERVER, null)
        val previousLogin = preferences.get(KEY_PENDING_CREDENTIAL_SAVE_LOGIN, null)
        try {
            preferences.put(KEY_PENDING_CREDENTIAL_SAVE_SERVER, session.serverUrl)
            preferences.put(KEY_PENDING_CREDENTIAL_SAVE_LOGIN, session.loginName)
            flushPreferences()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            preferences.putOrRemove(KEY_PENDING_CREDENTIAL_SAVE_SERVER, previousServer)
            preferences.putOrRemove(KEY_PENDING_CREDENTIAL_SAVE_LOGIN, previousLogin)
            runCatching(flushPreferences)
            recordCredentialDiagnostic(
                "ACCOUNT_CREDENTIAL_STORE_WRITE_FAILED",
                "account-credentials.persist",
                failure,
            )
            throw failure
        }
    }

    private fun clearPendingCredentialSave() {
        val server = preferences.get(KEY_PENDING_CREDENTIAL_SAVE_SERVER, null)
        val login = preferences.get(KEY_PENDING_CREDENTIAL_SAVE_LOGIN, null)
        if (server == null && login == null) return
        try {
            preferences.remove(KEY_PENDING_CREDENTIAL_SAVE_SERVER)
            preferences.remove(KEY_PENDING_CREDENTIAL_SAVE_LOGIN)
            flushPreferences()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            preferences.putOrRemove(KEY_PENDING_CREDENTIAL_SAVE_SERVER, server)
            preferences.putOrRemove(KEY_PENDING_CREDENTIAL_SAVE_LOGIN, login)
            runCatching(flushPreferences)
            recordCredentialDiagnostic(
                "ACCOUNT_CREDENTIAL_STORE_ROLLBACK_FAILED",
                "account-credentials.recover",
                failure,
            )
        }
    }

    private fun retryPendingLegacyCredentialCleanup(expected: NextcloudSession? = null) {
        val server = preferences.get(KEY_PENDING_LEGACY_CLEANUP_SERVER, null)
        val login = preferences.get(KEY_PENDING_LEGACY_CLEANUP_LOGIN, null)
        if (server == null && login == null) return
        if (server.isNullOrBlank() || login.isNullOrBlank()) {
            recordCredentialDiagnostic(
                "ACCOUNT_CREDENTIAL_LEGACY_CLEANUP_FAILED",
                "account-credentials.migrate",
            )
            return
        }
        if (expected != null && (expected.serverUrl != server || expected.loginName != login)) return
        val replacementAvailable = try {
            val accountId = deriveNextcloudAccountId(server, login)
            loadSecret(desktopAccountSecretReference(accountId)) != null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            recordCredentialDiagnostic(
                "ACCOUNT_CREDENTIAL_LEGACY_CLEANUP_FAILED",
                "account-credentials.migrate",
            )
            false
        }
        if (!replacementAvailable) return
        try {
            secretStore.clear(desktopSessionSecretReference(server, login))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            recordCredentialDiagnostic(
                "ACCOUNT_CREDENTIAL_LEGACY_CLEANUP_FAILED",
                "account-credentials.migrate",
            )
            return
        }
        try {
            preferences.remove(KEY_PENDING_LEGACY_CLEANUP_SERVER)
            preferences.remove(KEY_PENDING_LEGACY_CLEANUP_LOGIN)
            flushPreferences()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            preferences.put(KEY_PENDING_LEGACY_CLEANUP_SERVER, server)
            preferences.put(KEY_PENDING_LEGACY_CLEANUP_LOGIN, login)
            try {
                flushPreferences()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The in-memory marker remains available for another retry in this process.
            }
            recordCredentialDiagnostic(
                "ACCOUNT_CREDENTIAL_LEGACY_CLEANUP_FAILED",
                "account-credentials.migrate",
            )
        }
    }

    private fun persistPendingLegacyCredentialCleanup(session: NextcloudSession) {
        val previousServer = preferences.get(KEY_PENDING_LEGACY_CLEANUP_SERVER, null)
        val previousLogin = preferences.get(KEY_PENDING_LEGACY_CLEANUP_LOGIN, null)
        try {
            preferences.put(KEY_PENDING_LEGACY_CLEANUP_SERVER, session.serverUrl)
            preferences.put(KEY_PENDING_LEGACY_CLEANUP_LOGIN, session.loginName)
            flushPreferences()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            preferences.putOrRemove(KEY_PENDING_LEGACY_CLEANUP_SERVER, previousServer)
            preferences.putOrRemove(KEY_PENDING_LEGACY_CLEANUP_LOGIN, previousLogin)
            try {
                flushPreferences()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The next load will retry from the last durable marker state.
            }
            throw DesktopSecretDeletionRecoveryUnavailableException(failure)
        }
    }

    private fun saveSecret(session: NextcloudSession) {
        try {
            secretStore.save(
                reference = desktopAccountSecretReference(session.accountId),
                username = session.loginName,
                secret = session.appPassword.encodeToByteArray(),
            )
        } catch (failure: Exception) {
            recordCredentialDiagnostic("ACCOUNT_CREDENTIAL_STORE_WRITE_FAILED", "account-credentials.persist")
            throw failure
        }
    }

    private fun loadSecret(reference: DesktopSecretReference): String? = try {
        secretStore.load(reference)
            ?.decodeToString()
            ?.takeIf(String::isNotBlank)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: NextcloudSessionStorageUnavailableException) {
        recordCredentialDiagnostic("ACCOUNT_CREDENTIAL_STORE_READ_FAILED", "account-credentials.restore")
        throw failure
    } catch (failure: Exception) {
        recordCredentialDiagnostic("ACCOUNT_CREDENTIAL_STORE_READ_FAILED", "account-credentials.restore")
        throw DesktopSecretStoreUnavailableException(
            "The desktop secure credential store could not be read.",
            cause = failure,
        )
    }

    private fun loadSecretForRollback(reference: DesktopSecretReference): ByteArray? = try {
        secretStore.load(reference)
    } catch (failure: Exception) {
        recordCredentialDiagnostic(
            "ACCOUNT_CREDENTIAL_STORE_READ_FAILED",
            "account-credentials.persist",
            failure,
        )
        throw failure
    }

    private fun clearSecret(reference: DesktopSecretReference) {
        try {
            secretStore.clear(reference)
        } catch (failure: Exception) {
            recordCredentialDiagnostic("ACCOUNT_CREDENTIAL_STORE_CLEAR_FAILED", "account-credentials.remove")
            throw failure
        }
    }

    private fun readRegistry(): DesktopRegistryRead {
        val encoded = preferences.get(DESKTOP_ACCOUNT_REGISTRY_KEY, null)
        return DesktopRegistryRead(encoded, encoded?.let(::decodeNextcloudAccountRegistry))
    }

    private fun prepareRegistry(registry: NextcloudAccountRegistry): String =
        encodeNextcloudAccountRegistry(registry).also { encoded ->
            require(encoded.length <= Preferences.MAX_VALUE_LENGTH) {
                "The account registry exceeds the desktop preference value limit."
            }
        }

    private fun persistAccountState(
        encodedRegistry: String,
        activeAccount: NextcloudAccountRecord?,
        pendingLegacyCleanup: NextcloudSession? = null,
    ) {
        require(encodedRegistry.length <= Preferences.MAX_VALUE_LENGTH)
        val previous = DesktopAccountPreferenceSnapshot(
            registry = preferences.get(DESKTOP_ACCOUNT_REGISTRY_KEY, null),
            server = preferences.get(KEY_SERVER, null),
            login = preferences.get(KEY_LOGIN, null),
            pendingLegacyCleanupServer = preferences.get(KEY_PENDING_LEGACY_CLEANUP_SERVER, null),
            pendingLegacyCleanupLogin = preferences.get(KEY_PENDING_LEGACY_CLEANUP_LOGIN, null),
        )
        try {
            preferences.put(DESKTOP_ACCOUNT_REGISTRY_KEY, encodedRegistry)
            preferences.putOrRemove(KEY_SERVER, activeAccount?.serverUrl)
            preferences.putOrRemove(KEY_LOGIN, activeAccount?.loginName)
            pendingLegacyCleanup?.let { session ->
                preferences.put(KEY_PENDING_LEGACY_CLEANUP_SERVER, session.serverUrl)
                preferences.put(KEY_PENDING_LEGACY_CLEANUP_LOGIN, session.loginName)
            }
            flushPreferences()
        } catch (failure: Exception) {
            previous.restore(preferences)
            runCatching(flushPreferences)
            recordCredentialDiagnostic(
                "ACCOUNT_CREDENTIAL_STORE_WRITE_FAILED",
                "account-credentials.persist",
                failure,
            )
            throw failure
        }
    }

    private fun legacyMetadataMatches(record: NextcloudAccountRecord): Boolean =
        preferences.get(KEY_SERVER, null) == record.serverUrl &&
            preferences.get(KEY_LOGIN, null) == record.loginName

    private fun invalidRegistryForMutation(): IllegalStateException {
        recordCredentialDiagnostic("ACCOUNT_REGISTRY_MALFORMED", "account-registry.persist")
        return IllegalStateException("The local account registry is invalid.")
    }

    private fun recordCredentialDiagnostic(
        code: String,
        operation: String,
        failure: Throwable? = null,
    ) {
        recordDiagnostic(
            SupportDiagnosticEventDraft(
                severity = SupportDiagnosticSeverity.Warning,
                component = SupportDiagnosticComponent.Authentication,
                operation = operation,
                outcome = "failed",
                code = code,
                exception = failure?.toNonSecretSupportDiagnosticExceptionDraft(),
            ),
        )
    }

    private data class DesktopRegistryRead(
        val encoded: String?,
        val registry: NextcloudAccountRegistry?,
    )

    private data class DesktopAccountPreferenceSnapshot(
        val registry: String?,
        val server: String?,
        val login: String?,
        val pendingLegacyCleanupServer: String?,
        val pendingLegacyCleanupLogin: String?,
    ) {
        fun restore(preferences: Preferences) {
            preferences.putOrRemove(DESKTOP_ACCOUNT_REGISTRY_KEY, registry)
            preferences.putOrRemove(KEY_SERVER, server)
            preferences.putOrRemove(KEY_LOGIN, login)
            preferences.putOrRemove(KEY_PENDING_LEGACY_CLEANUP_SERVER, pendingLegacyCleanupServer)
            preferences.putOrRemove(KEY_PENDING_LEGACY_CLEANUP_LOGIN, pendingLegacyCleanupLogin)
        }
    }

    private companion object {
        const val KEY_SERVER = "server"
        const val KEY_LOGIN = "login"
        const val KEY_PENDING_LEGACY_CLEANUP_SERVER = "accountLegacyCleanupServer"
        const val KEY_PENDING_LEGACY_CLEANUP_LOGIN = "accountLegacyCleanupLogin"
        const val KEY_PENDING_CREDENTIAL_SAVE_SERVER = "accountCredentialSaveServer"
        const val KEY_PENDING_CREDENTIAL_SAVE_LOGIN = "accountCredentialSaveLogin"
    }
}

private fun Preferences.putOrRemove(key: String, value: String?) {
    if (value == null) remove(key) else put(key, value)
}

private fun NextcloudAccountRecord.toSession(appPassword: String) = NextcloudSession(
    serverUrl = serverUrl,
    loginName = loginName,
    appPassword = appPassword,
)

internal fun desktopFileCacheAccountId(account: NextcloudAccountRecord): String =
    desktopFileCacheAccountId(account.toSession(appPassword = ""))

internal class DesktopAccountSessionPublication(
    private val registerPrivateValue: (String) -> Unit,
    private val publishAccountIdentity: (String) -> Unit,
) {
    fun register(session: NextcloudSession) {
        listOf(session.serverUrl, session.loginName, session.appPassword).forEach(registerPrivateValue)
    }

    fun publish(session: NextcloudSession) {
        register(session)
        publishAccountIdentity(desktopFileCacheAccountId(session))
    }
}

internal fun desktopAccountSelectionBlockedDiagnostic() = SupportDiagnosticEventDraft(
    severity = SupportDiagnosticSeverity.Warning,
    component = SupportDiagnosticComponent.Authentication,
    operation = "account.select",
    outcome = "blocked",
    code = "ACCOUNT_SELECTION_ACTIVE_RESOURCES",
)
