package dev.obiente.nextcloudnative.app

import java.util.prefs.Preferences
import kotlinx.coroutines.CancellationException

internal class DesktopAccountCredentialPersistence(
    private val preferences: Preferences,
    private val secretStore: DesktopSecretStore,
    private val recordDiagnostic: (SupportDiagnosticEventDraft) -> Unit,
    private val flushPreferences: () -> Unit = preferences::flush,
) {
    private val registryStore = DesktopAccountRegistryPreferenceStore(preferences, flushPreferences)

    fun loadActiveSession(): NextcloudSession? {
        retryPendingCredentialSave()
        retryPendingCredentialRemoval()
        retryPendingLegacyCredentialCleanup()
        val read = readRegistry()
        if (read.registry == null) {
            return restoreLegacySession(read)
        }
        val active = read.registry.activeAccount ?: return null
        return loadSession(active.id)
    }

    fun listAccounts(): List<NextcloudAccountRecord> {
        val read = readRegistry()
        if (read.registry != null) return read.registry.accounts
        if (read.unsupportedVersion) return emptyList()
        return readLegacyAccountRecord()?.let(::listOf).orEmpty()
    }

    fun activeAccountId(): NextcloudAccountId? {
        val read = readRegistry()
        if (read.registry != null) return read.registry.activeAccountId
        if (read.unsupportedVersion) return null
        return readLegacyAccountRecord()?.id
    }

    fun loadSession(accountId: NextcloudAccountId): NextcloudSession? {
        retryPendingCredentialSave()
        retryPendingCredentialRemoval()
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
        retryPendingCredentialRemoval()
        retryPendingLegacyCredentialCleanup()
        val read = readRegistry()
        val registry = read.registry
            ?: restoreLegacySession(read)?.let { requireNotNull(readRegistry().registry) }
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
            var credentialRollbackCompleted = false
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
                credentialRollbackCompleted = true
            } catch (rollbackFailure: Exception) {
                failure.addSuppressed(rollbackFailure)
                recordCredentialDiagnostic(
                    "ACCOUNT_CREDENTIAL_STORE_ROLLBACK_FAILED",
                    "account-credentials.persist",
                    rollbackFailure,
                )
            }
            if (journalNewCredential && credentialRollbackCompleted) clearPendingCredentialSave()
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
        retryPendingCredentialRemoval()
        retryPendingLegacyCredentialCleanup()
        val registry = readRegistry().registry ?: return false
        val record = registry.accounts.firstOrNull { account -> account.id == accountId } ?: return false
        val clearLegacyCredential = legacyMetadataMatches(record)
        val updated = registry.remove(accountId)
        persistAccountState(
            encodedRegistry = prepareRegistry(updated),
            activeAccount = updated.activeAccount,
            pendingLegacyCleanupAccount = record.takeIf { clearLegacyCredential },
            pendingCredentialRemoval = accountId,
        )
        retryPendingCredentialRemoval()
        if (clearLegacyCredential) retryPendingLegacyCredentialCleanup()
        return true
    }

    private fun restoreLegacySession(read: DesktopRegistryRead): NextcloudSession? {
        val legacy = loadLegacySession()
        val restored = restoreNextcloudAccountRegistry(
            encoded = read.encoded,
            legacySession = legacy,
        )
        restored.recoveryReason?.diagnosticCode?.let { code ->
            recordCredentialDiagnostic(code, "account-registry.restore")
        }
        if (read.unsupportedVersion) return null
        legacy ?: return null
        if (!restored.needsPersistence) return legacy
        try {
            val encodedRegistry = prepareRegistry(restored.registry)
            saveSecret(legacy)
            persistAccountState(
                encodedRegistry,
                restored.registry.activeAccount,
                pendingLegacyCleanupAccount = legacy.accountRecord(),
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

    private fun retryPendingCredentialRemoval() {
        pendingCredentialRemovalIds().forEach { accountId ->
            val registry = readRegistry().registry
            if (registry == null) {
                recordCredentialDiagnostic(
                    "ACCOUNT_CREDENTIAL_REMOVAL_JOURNAL_INVALID",
                    "account-credentials.recover",
                )
                return@forEach
            }
            if (registry.accounts.any { account -> account.id == accountId }) {
                clearPendingCredentialRemoval(accountId)
                return@forEach
            }
            try {
                secretStore.clear(desktopAccountSecretReference(accountId))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                recordCredentialDiagnostic(
                    "ACCOUNT_CREDENTIAL_STORE_CLEAR_FAILED",
                    "account-credentials.remove",
                    failure,
                )
                return@forEach
            }
            clearPendingCredentialRemoval(accountId)
        }
    }

    private fun pendingCredentialRemovalIds(): Set<NextcloudAccountId> {
        val encoded = preferences.get(KEY_PENDING_CREDENTIAL_REMOVALS, null) ?: return emptySet()
        if (encoded.isBlank()) return emptySet()
        return encoded.split(',').mapNotNullTo(linkedSetOf()) { storageKey ->
            try {
                NextcloudAccountId(storageKey)
            } catch (_: IllegalArgumentException) {
                recordCredentialDiagnostic(
                    "ACCOUNT_CREDENTIAL_REMOVAL_JOURNAL_INVALID",
                    "account-credentials.recover",
                )
                null
            }
        }
    }

    private fun clearPendingCredentialRemoval(accountId: NextcloudAccountId) {
        val previous = preferences.get(KEY_PENDING_CREDENTIAL_REMOVALS, null)
        val remaining = pendingCredentialRemovalIds() - accountId
        try {
            preferences.putOrRemove(
                KEY_PENDING_CREDENTIAL_REMOVALS,
                if (remaining.isEmpty()) null else remaining.joinToString(",") { pending -> pending.storageKey },
            )
            flushPreferences()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            preferences.putOrRemove(KEY_PENDING_CREDENTIAL_REMOVALS, previous)
            runCatching(flushPreferences)
            recordCredentialDiagnostic(
                "ACCOUNT_CREDENTIAL_STORE_CLEAR_FAILED",
                "account-credentials.recover",
                failure,
            )
        }
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
        val cleanupAllowed = try {
            val accountId = deriveNextcloudAccountId(server, login)
            val registry = readRegistry().registry
            registry?.accounts?.none { account -> account.id == accountId } == true ||
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
        if (!cleanupAllowed) return
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
        val encoded = registryStore.read()
        val decoded = encoded?.let(::decodeNextcloudAccountRegistryResult)
        return DesktopRegistryRead(
            encoded = encoded,
            registry = (decoded as? NextcloudAccountRegistryDecodeResult.Valid)?.registry,
            unsupportedVersion = decoded == NextcloudAccountRegistryDecodeResult.UnsupportedVersion,
        )
    }

    private fun prepareRegistry(registry: NextcloudAccountRegistry): String =
        encodeNextcloudAccountRegistry(registry)

    private fun persistAccountState(
        encodedRegistry: String,
        activeAccount: NextcloudAccountRecord?,
        pendingLegacyCleanupAccount: NextcloudAccountRecord? = null,
        pendingCredentialRemoval: NextcloudAccountId? = null,
    ) {
        val previous = DesktopAccountPreferenceSnapshot(
            registry = registryStore.read(),
            server = preferences.get(KEY_SERVER, null),
            login = preferences.get(KEY_LOGIN, null),
            pendingLegacyCleanupServer = preferences.get(KEY_PENDING_LEGACY_CLEANUP_SERVER, null),
            pendingLegacyCleanupLogin = preferences.get(KEY_PENDING_LEGACY_CLEANUP_LOGIN, null),
            pendingCredentialRemovals = preferences.get(KEY_PENDING_CREDENTIAL_REMOVALS, null),
        )
        try {
            registryStore.write(encodedRegistry)
            preferences.putOrRemove(KEY_SERVER, activeAccount?.serverUrl)
            preferences.putOrRemove(KEY_LOGIN, activeAccount?.loginName)
            pendingLegacyCleanupAccount?.let { account ->
                preferences.put(KEY_PENDING_LEGACY_CLEANUP_SERVER, account.serverUrl)
                preferences.put(KEY_PENDING_LEGACY_CLEANUP_LOGIN, account.loginName)
            }
            pendingCredentialRemoval?.let { accountId ->
                val removals = pendingCredentialRemovalIds() + accountId
                preferences.put(
                    KEY_PENDING_CREDENTIAL_REMOVALS,
                    removals.joinToString(",") { pending -> pending.storageKey },
                )
            }
            flushPreferences()
        } catch (failure: Exception) {
            runCatching { previous.restore(preferences, registryStore) }
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
        val unsupportedVersion: Boolean,
    )

    private data class DesktopAccountPreferenceSnapshot(
        val registry: String?,
        val server: String?,
        val login: String?,
        val pendingLegacyCleanupServer: String?,
        val pendingLegacyCleanupLogin: String?,
        val pendingCredentialRemovals: String?,
    ) {
        fun restore(preferences: Preferences, registryStore: DesktopAccountRegistryPreferenceStore) {
            registryStore.write(registry)
            preferences.putOrRemove(KEY_SERVER, server)
            preferences.putOrRemove(KEY_LOGIN, login)
            preferences.putOrRemove(KEY_PENDING_LEGACY_CLEANUP_SERVER, pendingLegacyCleanupServer)
            preferences.putOrRemove(KEY_PENDING_LEGACY_CLEANUP_LOGIN, pendingLegacyCleanupLogin)
            preferences.putOrRemove(KEY_PENDING_CREDENTIAL_REMOVALS, pendingCredentialRemovals)
        }
    }

    private companion object {
        const val KEY_SERVER = "server"
        const val KEY_LOGIN = "login"
        const val KEY_PENDING_LEGACY_CLEANUP_SERVER = "accountLegacyCleanupServer"
        const val KEY_PENDING_LEGACY_CLEANUP_LOGIN = "accountLegacyCleanupLogin"
        const val KEY_PENDING_CREDENTIAL_SAVE_SERVER = "accountCredentialSaveServer"
        const val KEY_PENDING_CREDENTIAL_SAVE_LOGIN = "accountCredentialSaveLogin"
        const val KEY_PENDING_CREDENTIAL_REMOVALS = "accountCredentialRemovals"
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
