package dev.obiente.nextcloudnative.app

import java.util.prefs.Preferences
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

internal class DesktopCredentialRollbackRecoveryUnavailableException(
    cause: Throwable? = null,
) : NextcloudSessionStorageUnavailableException(
    "The pending desktop credential rollback could not be completed safely.",
    cause,
)

internal class DesktopAccountCredentialPersistence(
    private val preferences: Preferences,
    private val secretStore: DesktopSecretStore,
    private val recordDiagnostic: (SupportDiagnosticEventDraft) -> Unit,
    private val flushPreferences: () -> Unit = preferences::flush,
) {
    private val registryStore = DesktopAccountRegistryPreferenceStore(preferences, flushPreferences)
    private val legacyCleanupJournal = DesktopLegacyCredentialCleanupJournal(
        preferences,
        flushPreferences,
    ) { recordCredentialDiagnostic("ACCOUNT_CREDENTIAL_LEGACY_CLEANUP_FAILED", "account-credentials.migrate") }
    private val malformedCredentialRemovalJournalReported = AtomicBoolean(false)

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

    fun accountOwnership(accountId: String): DesktopAccountOwnership {
        val read = readRegistry()
        val knownAccounts = read.registry?.accounts
        if (knownAccounts != null) {
            return if (knownAccounts.any { account -> desktopFileCacheAccountId(account) == accountId }) {
                DesktopAccountOwnership.Present
            } else {
                DesktopAccountOwnership.Absent
            }
        }
        val legacyMatches = readLegacyAccountRecord()?.let(::desktopFileCacheAccountId) == accountId
        if (legacyMatches) return DesktopAccountOwnership.Present
        return if (read.encoded == null) DesktopAccountOwnership.Absent else DesktopAccountOwnership.Unknown
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
            ?: when {
                read.encoded == null -> NextcloudAccountRegistry.Empty
                read.unsupportedVersion -> throw unsupportedRegistryForMutation()
                else -> NextcloudAccountRegistry.Empty
            }
        val previousRecord = registry.accounts.firstOrNull { account -> account.id == session.accountId }
        val persistedSession = previousRecord
            ?.let { record -> session.copy(serverUrl = record.serverUrl, loginName = record.loginName) }
            ?: session
        val updatedRegistry = registry.upsertAndSelect(persistedSession.accountRecord())
        val encodedRegistry = prepareRegistry(updatedRegistry)
        val secretReference = desktopAccountSecretReference(persistedSession.accountId)
        val rollbackReference = desktopAccountCredentialRollbackReference(persistedSession.accountId)
        val previousSecret = loadSecretForRollback(secretReference)
        check(previousRecord == null || previousSecret != null) {
            "The existing account credential could not be read for safe replacement."
        }
        persistPendingCredentialSave(persistedSession)
        try {
            if (previousSecret != null) {
                secretStore.save(rollbackReference, previousRecord?.loginName, previousSecret)
            }
            markPendingCredentialSaveSecretWriting()
            saveSecret(persistedSession)
            markPendingCredentialSaveSecretWritten()
            persistAccountState(encodedRegistry, updatedRegistry.activeAccount)
        } catch (failure: Exception) {
            var credentialRollbackCompleted = false
            try {
                markPendingCredentialSaveRollback()
                if (previousSecret == null) {
                    secretStore.clear(secretReference)
                } else {
                    secretStore.save(
                        secretReference,
                        previousRecord?.loginName,
                        previousSecret,
                    )
                }
                secretStore.clear(rollbackReference)
                credentialRollbackCompleted = true
            } catch (rollbackFailure: Exception) {
                failure.addSuppressed(rollbackFailure)
                recordCredentialDiagnostic(
                    "ACCOUNT_CREDENTIAL_STORE_ROLLBACK_FAILED",
                    "account-credentials.persist",
                    rollbackFailure,
                )
            }
            if (credentialRollbackCompleted) clearPendingCredentialSave()
            throw failure
        }
        if (previousSecret != null) secretStore.clear(rollbackReference)
        clearPendingCredentialSave()
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
        val phase = preferences.get(KEY_PENDING_CREDENTIAL_SAVE_PHASE, null)
        if (server == null && login == null && phase == null) return
        if (server.isNullOrBlank() || login.isNullOrBlank()) {
            credentialRollbackRecoveryUnavailable()
        }
        val accountId = try {
            deriveNextcloudAccountId(server, login)
        } catch (failure: Exception) {
            credentialRollbackRecoveryUnavailable(failure)
        }
        val registryRead = readRegistry()
        if (registryRead.encoded != null && registryRead.registry == null) {
            credentialRollbackRecoveryUnavailable()
        }
        val knownPhases = setOf(
            null,
            CREDENTIAL_SAVE_PREPARED,
            CREDENTIAL_SAVE_SECRET_WRITING,
            CREDENTIAL_SAVE_SECRET_WRITTEN,
            CREDENTIAL_SAVE_ROLLBACK,
        )
        if (phase !in knownPhases) {
            credentialRollbackRecoveryUnavailable()
        }
        val registry = registryRead.registry
        val credentialCommitted = registry?.accounts?.any { account -> account.id == accountId } == true
        val secretReference = desktopAccountSecretReference(accountId)
        val rollbackReference = desktopAccountCredentialRollbackReference(accountId)
        if (!credentialCommitted) {
            try {
                secretStore.clear(secretReference)
                secretStore.clear(rollbackReference)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                credentialRollbackRecoveryUnavailable(failure)
            }
        } else if (phase == CREDENTIAL_SAVE_SECRET_WRITING || phase == CREDENTIAL_SAVE_ROLLBACK) {
            val rollbackSecret = try {
                secretStore.load(rollbackReference)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                credentialRollbackRecoveryUnavailable(failure)
            }
            if (rollbackSecret == null) {
                credentialRollbackRecoveryUnavailable()
            }
            try {
                secretStore.save(secretReference, registry.accounts.first { it.id == accountId }.loginName, rollbackSecret)
                secretStore.clear(rollbackReference)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                credentialRollbackRecoveryUnavailable(failure)
            }
        } else if (phase == CREDENTIAL_SAVE_SECRET_WRITTEN) {
            val selected = requireNotNull(registry.select(accountId))
            try {
                persistAccountState(prepareRegistry(selected), selected.activeAccount)
                secretStore.clear(rollbackReference)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                credentialRollbackRecoveryUnavailable(failure)
            }
        }
        if (phase == CREDENTIAL_SAVE_PREPARED) {
            try {
                secretStore.clear(rollbackReference)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                credentialRollbackRecoveryUnavailable(failure)
            }
        }
        clearPendingCredentialSave()
    }

    private fun retryPendingCredentialRemoval() {
        val pending = readPendingCredentialRemovals()
        pending.accountIds.forEach { accountId ->
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
            if (!reconcileLegacyAccountMetadata(registry.activeAccount)) return@forEach
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

    private fun readPendingCredentialRemovals(): DesktopPendingCredentialRemovals {
        val encoded = preferences.get(KEY_PENDING_CREDENTIAL_REMOVALS, null)
            ?: return DesktopPendingCredentialRemovals.Empty
        val accountIds = linkedSetOf<NextcloudAccountId>()
        val malformedEntries = mutableListOf<String>()
        encoded.split(',').forEach { storageKey ->
            try {
                accountIds += NextcloudAccountId(storageKey)
            } catch (_: IllegalArgumentException) {
                malformedEntries += storageKey
            }
        }
        if (malformedEntries.isNotEmpty() && malformedCredentialRemovalJournalReported.compareAndSet(false, true)) {
            runCatching {
                recordCredentialDiagnostic(
                    "ACCOUNT_CREDENTIAL_REMOVAL_JOURNAL_INVALID",
                    "account-credentials.recover",
                )
            }
        }
        return DesktopPendingCredentialRemovals(accountIds, malformedEntries)
    }

    private fun reconcileLegacyAccountMetadata(activeAccount: NextcloudAccountRecord?): Boolean {
        val previousServer = preferences.get(KEY_SERVER, null)
        val previousLogin = preferences.get(KEY_LOGIN, null)
        return try {
            preferences.putOrRemove(KEY_SERVER, activeAccount?.serverUrl)
            preferences.putOrRemove(KEY_LOGIN, activeAccount?.loginName)
            flushPreferences()
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            preferences.putOrRemove(KEY_SERVER, previousServer)
            preferences.putOrRemove(KEY_LOGIN, previousLogin)
            runCatching(flushPreferences)
            recordCredentialDiagnostic(
                "ACCOUNT_CREDENTIAL_STORE_CLEAR_FAILED",
                "account-credentials.recover",
                failure,
            )
            false
        }
    }

    private fun clearPendingCredentialRemoval(accountId: NextcloudAccountId) {
        val previous = preferences.get(KEY_PENDING_CREDENTIAL_REMOVALS, null)
        val pending = readPendingCredentialRemovals()
        val remaining = pending.accountIds - accountId
        try {
            preferences.putOrRemove(
                KEY_PENDING_CREDENTIAL_REMOVALS,
                pending.encode(remaining),
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
        val previousPhase = preferences.get(KEY_PENDING_CREDENTIAL_SAVE_PHASE, null)
        try {
            preferences.put(KEY_PENDING_CREDENTIAL_SAVE_SERVER, session.serverUrl)
            preferences.put(KEY_PENDING_CREDENTIAL_SAVE_LOGIN, session.loginName)
            preferences.put(KEY_PENDING_CREDENTIAL_SAVE_PHASE, CREDENTIAL_SAVE_PREPARED)
            flushPreferences()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            preferences.putOrRemove(KEY_PENDING_CREDENTIAL_SAVE_SERVER, previousServer)
            preferences.putOrRemove(KEY_PENDING_CREDENTIAL_SAVE_LOGIN, previousLogin)
            preferences.putOrRemove(KEY_PENDING_CREDENTIAL_SAVE_PHASE, previousPhase)
            runCatching(flushPreferences)
            recordCredentialDiagnostic(
                "ACCOUNT_CREDENTIAL_STORE_WRITE_FAILED",
                "account-credentials.persist",
                failure,
            )
            throw failure
        }
    }

    private fun markPendingCredentialSaveSecretWritten() {
        preferences.put(KEY_PENDING_CREDENTIAL_SAVE_PHASE, CREDENTIAL_SAVE_SECRET_WRITTEN)
        flushPreferences()
    }

    private fun markPendingCredentialSaveSecretWriting() {
        preferences.put(KEY_PENDING_CREDENTIAL_SAVE_PHASE, CREDENTIAL_SAVE_SECRET_WRITING)
        flushPreferences()
    }

    private fun credentialRollbackRecoveryUnavailable(failure: Exception? = null): Nothing {
        recordCredentialDiagnostic(
            "ACCOUNT_CREDENTIAL_STORE_ROLLBACK_FAILED",
            "account-credentials.recover",
            failure,
        )
        throw DesktopCredentialRollbackRecoveryUnavailableException(failure)
    }

    private fun markPendingCredentialSaveRollback() {
        val previousPhase = preferences.get(KEY_PENDING_CREDENTIAL_SAVE_PHASE, null)
        try {
            preferences.put(KEY_PENDING_CREDENTIAL_SAVE_PHASE, CREDENTIAL_SAVE_ROLLBACK)
            flushPreferences()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            preferences.putOrRemove(KEY_PENDING_CREDENTIAL_SAVE_PHASE, previousPhase)
            runCatching(flushPreferences)
            throw failure
        }
    }

    private fun clearPendingCredentialSave() {
        val server = preferences.get(KEY_PENDING_CREDENTIAL_SAVE_SERVER, null)
        val login = preferences.get(KEY_PENDING_CREDENTIAL_SAVE_LOGIN, null)
        val phase = preferences.get(KEY_PENDING_CREDENTIAL_SAVE_PHASE, null)
        if (server == null && login == null) return
        try {
            preferences.remove(KEY_PENDING_CREDENTIAL_SAVE_SERVER)
            preferences.remove(KEY_PENDING_CREDENTIAL_SAVE_LOGIN)
            preferences.remove(KEY_PENDING_CREDENTIAL_SAVE_PHASE)
            flushPreferences()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            preferences.putOrRemove(KEY_PENDING_CREDENTIAL_SAVE_SERVER, server)
            preferences.putOrRemove(KEY_PENDING_CREDENTIAL_SAVE_LOGIN, login)
            preferences.putOrRemove(KEY_PENDING_CREDENTIAL_SAVE_PHASE, phase)
            runCatching(flushPreferences)
            recordCredentialDiagnostic(
                "ACCOUNT_CREDENTIAL_STORE_ROLLBACK_FAILED",
                "account-credentials.recover",
                failure,
            )
            throw DesktopCredentialRollbackRecoveryUnavailableException(failure)
        }
    }

    private fun retryPendingLegacyCredentialCleanup(expected: NextcloudSession? = null) {
        legacyCleanupJournal.pending()
            .filter { cleanup -> expected == null ||
                expected.serverUrl == cleanup.serverUrl && expected.loginName == cleanup.loginName
            }
            .forEach(::retryPendingLegacyCredentialCleanup)
    }

    private fun retryPendingLegacyCredentialCleanup(cleanup: DesktopPendingLegacyCredentialCleanup) {
        val cleanupAllowed = try {
            val accountId = deriveNextcloudAccountId(cleanup.serverUrl, cleanup.loginName)
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
            secretStore.clear(desktopSessionSecretReference(cleanup.serverUrl, cleanup.loginName))
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
            legacyCleanupJournal.clear(cleanup)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            recordCredentialDiagnostic(
                "ACCOUNT_CREDENTIAL_LEGACY_CLEANUP_FAILED",
                "account-credentials.migrate",
            )
        }
    }

    private fun persistPendingLegacyCredentialCleanup(session: NextcloudSession) {
        val previous = legacyCleanupJournal.snapshot()
        try {
            legacyCleanupJournal.prepareAdd(DesktopPendingLegacyCredentialCleanup(session.serverUrl, session.loginName))
            flushPreferences()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            legacyCleanupJournal.restore(previous)
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
        val credentialRemovals = pendingCredentialRemoval?.let { accountId ->
            val pending = readPendingCredentialRemovals()
            requireNotNull(pending.encode(pending.accountIds + accountId))
        }
        val previous = DesktopAccountPreferenceSnapshot(
            registry = registryStore.read(),
            server = preferences.get(KEY_SERVER, null),
            login = preferences.get(KEY_LOGIN, null),
            pendingLegacyCleanups = legacyCleanupJournal.snapshot(),
            pendingCredentialRemovals = preferences.get(KEY_PENDING_CREDENTIAL_REMOVALS, null),
        )
        try {
            pendingLegacyCleanupAccount?.let { account ->
                legacyCleanupJournal.prepareAdd(
                    DesktopPendingLegacyCredentialCleanup(account.serverUrl, account.loginName),
                )
            }
            credentialRemovals?.let { removals ->
                preferences.put(KEY_PENDING_CREDENTIAL_REMOVALS, removals)
            }
            if (pendingLegacyCleanupAccount != null || pendingCredentialRemoval != null) flushPreferences()
            registryStore.write(encodedRegistry)
            preferences.putOrRemove(KEY_SERVER, activeAccount?.serverUrl)
            preferences.putOrRemove(KEY_LOGIN, activeAccount?.loginName)
            flushPreferences()
        } catch (failure: Exception) {
            runCatching { previous.restore(preferences, registryStore, legacyCleanupJournal) }
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

    private fun unsupportedRegistryForMutation(): IllegalStateException {
        recordCredentialDiagnostic("ACCOUNT_REGISTRY_VERSION_UNSUPPORTED", "account-registry.persist")
        return IllegalStateException("The local account registry was written by a newer app version.")
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

    private data class DesktopPendingCredentialRemovals(
        val accountIds: Set<NextcloudAccountId>,
        val malformedEntries: List<String>,
    ) {
        fun encode(accountIds: Set<NextcloudAccountId>): String? {
            if (accountIds.isEmpty() && malformedEntries.isEmpty()) return null
            return (accountIds.map(NextcloudAccountId::storageKey) + malformedEntries)
                .joinToString(",")
        }

        companion object {
            val Empty = DesktopPendingCredentialRemovals(emptySet(), emptyList())
        }
    }

    private data class DesktopAccountPreferenceSnapshot(
        val registry: String?,
        val server: String?,
        val login: String?,
        val pendingLegacyCleanups: DesktopLegacyCredentialCleanupSnapshot,
        val pendingCredentialRemovals: String?,
    ) {
        fun restore(
            preferences: Preferences,
            registryStore: DesktopAccountRegistryPreferenceStore,
            legacyCleanupJournal: DesktopLegacyCredentialCleanupJournal,
        ) {
            registryStore.write(registry)
            preferences.putOrRemove(KEY_SERVER, server)
            preferences.putOrRemove(KEY_LOGIN, login)
            legacyCleanupJournal.restore(pendingLegacyCleanups)
            preferences.putOrRemove(KEY_PENDING_CREDENTIAL_REMOVALS, pendingCredentialRemovals)
        }
    }

    private companion object {
        const val KEY_SERVER = "server"
        const val KEY_LOGIN = "login"
        const val KEY_PENDING_CREDENTIAL_SAVE_SERVER = "accountCredentialSaveServer"
        const val KEY_PENDING_CREDENTIAL_SAVE_LOGIN = "accountCredentialSaveLogin"
        const val KEY_PENDING_CREDENTIAL_SAVE_PHASE = "accountCredentialSavePhase"
        const val KEY_PENDING_CREDENTIAL_REMOVALS = "accountCredentialRemovals"
        const val CREDENTIAL_SAVE_PREPARED = "prepared"
        const val CREDENTIAL_SAVE_SECRET_WRITING = "secret-writing"
        const val CREDENTIAL_SAVE_SECRET_WRITTEN = "secret-written"
        const val CREDENTIAL_SAVE_ROLLBACK = "rollback"
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

internal fun desktopDurableMutationAccountScope(account: NextcloudAccountRecord): String =
    durableMutationAccountScope(account.toSession(appPassword = ""))

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
