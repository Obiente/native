package dev.obiente.nextcloudnative

import android.content.Context
import android.content.SharedPreferences
import dev.obiente.nextcloudnative.app.NextcloudAccountId
import dev.obiente.nextcloudnative.app.NextcloudAccountRecord
import dev.obiente.nextcloudnative.app.NextcloudAccountRegistry
import dev.obiente.nextcloudnative.app.NextcloudAccountRegistryRecoveryReason
import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.SupportDiagnosticComponent
import dev.obiente.nextcloudnative.app.SupportDiagnosticEventDraft
import dev.obiente.nextcloudnative.app.SupportDiagnosticSeverity
import dev.obiente.nextcloudnative.app.accountRecord
import dev.obiente.nextcloudnative.app.decodeNextcloudAccountRegistry
import dev.obiente.nextcloudnative.app.encodeNextcloudAccountRegistry
import dev.obiente.nextcloudnative.app.restoreNextcloudAccountRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class AndroidAccountCredentialController(
    context: Context,
    private val preferences: SharedPreferences,
    private val sessionCipher: SessionCipher,
    private val registerSessionPrivateValues: (NextcloudSession) -> Unit,
    private val recordDiagnostic: (SupportDiagnosticEventDraft) -> Unit,
    private val publishAccountIdentity: (String?) -> Unit,
    private val clearPreviewAccount: (String) -> Unit,
    private val notifyDocumentRootsChanged: () -> Unit,
    private val resumeQueuedUploads: suspend (String) -> Unit,
    private val prepareAccountRemoval: suspend (NextcloudSession) -> Unit,
    private val removeQueuedUploads: suspend (NextcloudSession) -> Unit,
) {
    private val appContext = context.applicationContext

    fun loadSession(): NextcloudSession? = ANDROID_FILE_SYNC_SESSION_SCHEDULING_GUARD.restorePersistedSession(
        load = { activeAccountId()?.let { accountId -> loadSession(accountId) } },
        accountIdOf = NextcloudDocumentIds::accountKey,
        publishAccount = { session, accountIdentity ->
            session?.let(registerSessionPrivateValues)
            publishAccountIdentity(accountIdentity)
        },
    )

    fun listAccounts(): List<NextcloudAccountRecord> = readCredentialFreeRegistry()?.accounts.orEmpty()

    fun activeAccountId(): NextcloudAccountId? = readCredentialFreeRegistry()?.activeAccountId

    fun loadSession(accountId: NextcloudAccountId): NextcloudSession? =
        ANDROID_ACCOUNT_CREDENTIAL_STORE_GUARD.serialize {
            val registry = readCredentialFreeRegistry() ?: return@serialize null
            if (registry.accounts.none { account -> account.id == accountId }) return@serialize null
            val storedSlot = readCredentialSlot(accountId)
            val restoredSlot = recoverAndroidAccountCredentialSlot(accountId, registry, storedSlot, aggregate = null)
            val aggregate = if (restoredSlot == null) {
                (readStore() as? AndroidAccountCredentialStoreRead.Available)?.state
            } else {
                null
            }
            val session = restoredSlot ?: recoverAndroidAccountCredentialSlot(
                accountId,
                registry,
                storedSlot = null,
                aggregate = aggregate,
            )
                ?: return@serialize null
            if (storedSlot != session) {
                runCatching {
                    commitPreferences(
                        preferences.edit().putString(
                            androidAccountCredentialSlotKey(accountId),
                            encryptCredentialSlot(session),
                        ),
                    )
                }
            }
            session.also(registerSessionPrivateValues)
        }

    suspend fun saveSession(session: NextcloudSession): NextcloudSession =
        ANDROID_ACCOUNT_CREDENTIAL_MUTATION_MUTEX.withLock {
            registerSessionPrivateValues(session)
            when (val read = readStore()) {
                is AndroidAccountCredentialStoreRead.Available ->
                    replaceActiveState(read.state.upsertAndSelect(session), read.state.activeSession)
                is AndroidAccountCredentialStoreRead.Invalid -> {
                    val retained = readIndependentCredentialSlotState()
                    check(retained != null || !hasIndependentCredentialState()) {
                        "The aggregate account credential store is invalid; reset it before signing in again."
                    }
                    replaceActiveState(
                        replacement = (retained ?: AndroidAccountCredentialState.Empty).upsertAndSelect(session),
                        previousSession = retained?.activeSession,
                        suspectEncrypted = read.encrypted,
                    )
                }
                AndroidAccountCredentialStoreRead.IndependentRecoveryUnavailable -> {
                    val retained = readIndependentCredentialSlotState()
                    check(retained != null || !hasIndependentCredentialState()) {
                        "The independent account credential slots could not be recovered."
                    }
                    replaceActiveState(
                        replacement = (retained ?: AndroidAccountCredentialState.Empty).upsertAndSelect(session),
                        previousSession = retained?.activeSession,
                    )
                }
                is AndroidAccountCredentialStoreRead.Unsupported -> unsupportedCredentialStoreMutation(read.version)
            }
            requireNotNull(loadSession(session.accountId))
        }

    suspend fun selectAccount(accountId: NextcloudAccountId): NextcloudSession? =
        ANDROID_ACCOUNT_CREDENTIAL_MUTATION_MUTEX.withLock {
            val current = requireValidState()
            val selected = current.select(accountId) ?: return@withLock null
            val session = requireNotNull(selected.activeSession)
            registerSessionPrivateValues(session)
            replaceActiveState(selected, current.activeSession)
            session
        }

    suspend fun removeAccount(accountId: NextcloudAccountId): Boolean =
        ANDROID_ACCOUNT_CREDENTIAL_MUTATION_MUTEX.withLock {
            val current = requireValidState()
            val session = current.sessions[accountId] ?: return@withLock false
            ANDROID_ACCOUNT_OPERATION_GUARD.withAccount(NextcloudDocumentIds.accountKey(session)) {
                val active = current.registry.activeAccountId == accountId
                removeAndroidAccountCredentialData(
                    active = active,
                    prepareAccountRemoval = { prepareAccountRemoval(session) },
                    removeQueuedUploads = { removeQueuedUploads(session) },
                    clearActiveAccount = { clearSession(current) },
                    rollbackActiveRemoval = {
                        replaceActiveStateWhileOperationsIdle(
                            replacement = current,
                            previousSession = null,
                            suspectEncrypted = null,
                        )
                    },
                    persistInactiveRemoval = { persistState(current.remove(accountId)) },
                    rollbackInactiveRemoval = { persistState(current) },
                )
                if (!active) {
                    clearPreviewAccount(NextcloudDocumentIds.cacheAccountId(session))
                }
            }
            true
        }

    suspend fun clearSession() = ANDROID_ACCOUNT_CREDENTIAL_MUTATION_MUTEX.withLock {
        when (val read = readStore()) {
            is AndroidAccountCredentialStoreRead.Available -> {
                val session = read.state.activeSession
                if (session == null) {
                    clearSession(read.state)
                } else {
                    ANDROID_ACCOUNT_OPERATION_GUARD.withAccount(NextcloudDocumentIds.accountKey(session)) {
                        removeAndroidAccountCredentialData(
                            active = true,
                            prepareAccountRemoval = { prepareAccountRemoval(session) },
                            removeQueuedUploads = { removeQueuedUploads(session) },
                            clearActiveAccount = { clearSession(read.state) },
                            rollbackActiveRemoval = {
                                replaceActiveStateWhileOperationsIdle(
                                    replacement = read.state,
                                    previousSession = null,
                                    suspectEncrypted = null,
                                )
                            },
                            persistInactiveRemoval = {},
                            rollbackInactiveRemoval = {},
                        )
                    }
                }
            }
            is AndroidAccountCredentialStoreRead.Invalid -> {
                val retained = readIndependentCredentialSlotState()
                when {
                    retained != null -> clearRecoveredInvalidStore(retained, read.encrypted)
                    hasIndependentCredentialState() ->
                        error("The independent account credential slots could not be recovered.")
                    else -> clearInvalidStore(read.encrypted)
                }
            }
            AndroidAccountCredentialStoreRead.IndependentRecoveryUnavailable ->
                error("The independent account credential slots could not be recovered.")
            is AndroidAccountCredentialStoreRead.Unsupported -> unsupportedCredentialStoreMutation(read.version)
        }
    }

    private suspend fun clearSession(current: AndroidAccountCredentialState) {
        val activeSession = current.activeSession ?: return
        val replacement = current.remove(activeSession.accountId)
        val encodedReplacement = replacement.takeUnless { state ->
            state.registry.accounts.isEmpty() && state.sessions.isEmpty()
        }?.let(::encryptState)
        clearPersistedSession(encodedReplacement, replacement)
        clearPreviewAccount(NextcloudDocumentIds.cacheAccountId(activeSession))
        notifyDocumentRootsChanged()
    }

    private suspend fun clearInvalidStore(suspectEncrypted: String) {
        clearPersistedSession(
            encodedReplacement = null,
            replacement = AndroidAccountCredentialState.Empty,
            suspectEncrypted = suspectEncrypted,
        )
        notifyDocumentRootsChanged()
    }

    private suspend fun clearRecoveredInvalidStore(
        current: AndroidAccountCredentialState,
        suspectEncrypted: String,
    ) {
        val activeSession = current.activeSession
        if (activeSession != null) {
            ANDROID_ACCOUNT_OPERATION_GUARD.withAccount(NextcloudDocumentIds.accountKey(activeSession)) {
                removeRecoveredAndroidAccountCredentialData(
                    prepareAccountRemoval = { prepareAccountRemoval(activeSession) },
                    removeQueuedUploads = { removeQueuedUploads(activeSession) },
                    clearRecoveredAccount = { persistRecoveredInvalidStoreAfterClear(current, suspectEncrypted) },
                    rollbackRecoveredAccount = {
                        replaceActiveStateWhileOperationsIdle(
                            replacement = current,
                            previousSession = null,
                            suspectEncrypted = suspectEncrypted,
                        )
                    },
                )
            }
        } else {
            persistRecoveredInvalidStoreAfterClear(current, suspectEncrypted)
        }
    }

    private suspend fun persistRecoveredInvalidStoreAfterClear(
        current: AndroidAccountCredentialState,
        suspectEncrypted: String,
    ) {
        val activeSession = current.activeSession
        val replacement = removeActiveAndroidAccountCredentialState(current)
        val encodedReplacement = replacement.takeUnless { state ->
            state.registry.accounts.isEmpty() && state.sessions.isEmpty()
        }?.let(::encryptState)
        clearPersistedSession(encodedReplacement, replacement, suspectEncrypted)
        activeSession?.let { session -> clearPreviewAccount(NextcloudDocumentIds.cacheAccountId(session)) }
        notifyDocumentRootsChanged()
    }

    private suspend fun clearPersistedSession(
        encodedReplacement: String?,
        replacement: AndroidAccountCredentialState,
        suspectEncrypted: String? = null,
    ) {
        withContext(Dispatchers.IO) { AndroidExternalFileHandoffRegistry.clear() }
        val scheduler = AndroidFileSyncScheduler(appContext)
        withContext(Dispatchers.IO) {
            ANDROID_FILE_SYNC_SESSION_SCHEDULING_GUARD.clearSession(
                persist = {
                    val editor = if (suspectEncrypted == null) {
                        preferences.edit().apply {
                            if (encodedReplacement == null) remove(KEY_SESSION)
                            else putString(KEY_SESSION, encodedReplacement)
                            putString(KEY_ACCOUNT_REGISTRY, encodeNextcloudAccountRegistry(replacement.registry))
                            remove(KEY_TEST_READ_ONLY)
                        }.let { editor -> prepareCredentialSlotEdit(editor, replacement) }
                    } else {
                        prepareInvalidAndroidAccountCredentialRecoveryEdit(
                            editor = preferences.edit(),
                            replacementEncrypted = encodedReplacement,
                        ).putString(KEY_ACCOUNT_REGISTRY, encodeNextcloudAccountRegistry(replacement.registry))
                            .let { editor -> prepareCredentialSlotEdit(editor, replacement) }
                    }
                    commitPreferences(editor)
                },
                cancelAll = scheduler::cancelAll,
                clearPublishedAccount = { publishAccountIdentity(null) },
            )
        }
    }

    private suspend fun replaceActiveState(
        replacement: AndroidAccountCredentialState,
        previousSession: NextcloudSession?,
        suspectEncrypted: String? = null,
    ) {
        val replacementSession = requireNotNull(replacement.activeSession)
        val affectedAccountIds = listOfNotNull(previousSession, replacementSession)
            .map(NextcloudDocumentIds::accountKey)
        ANDROID_ACCOUNT_OPERATION_GUARD.withAccounts(affectedAccountIds) {
            replaceActiveStateWhileOperationsIdle(replacement, previousSession, suspectEncrypted)
        }
    }

    private suspend fun replaceActiveStateWhileOperationsIdle(
        replacement: AndroidAccountCredentialState,
        previousSession: NextcloudSession?,
        suspectEncrypted: String?,
    ) {
        val session = requireNotNull(replacement.activeSession)
        val encrypted = encryptState(replacement)
        withContext(Dispatchers.IO) { AndroidExternalFileHandoffRegistry.clear() }
        val scheduler = AndroidFileSyncScheduler(appContext)
        withContext(Dispatchers.IO) {
            ANDROID_FILE_SYNC_SESSION_SCHEDULING_GUARD.replaceSession(
                replacementAccountId = NextcloudDocumentIds.accountKey(session),
                persist = {
                    val editor = if (suspectEncrypted == null) {
                        preferences.edit()
                            .putString(KEY_SESSION, encrypted)
                            .putString(KEY_ACCOUNT_REGISTRY, encodeNextcloudAccountRegistry(replacement.registry))
                            .remove(KEY_TEST_READ_ONLY)
                    } else {
                        prepareInvalidAndroidAccountCredentialRecoveryEdit(
                            editor = preferences.edit(),
                            replacementEncrypted = encrypted,
                        ).putString(KEY_ACCOUNT_REGISTRY, encodeNextcloudAccountRegistry(replacement.registry))
                    }
                    commitPreferences(prepareCredentialSlotEdit(editor, replacement))
                },
                cancelAll = scheduler::cancelAll,
                publishAccount = publishAccountIdentity,
                restoreSchedules = scheduler::restorePersistedPairSchedules,
                onScheduleMaintenanceFailure = {
                    recordCredentialFailure(
                        code = "FILE_SYNC_SCHEDULE_MAINTENANCE_FAILED",
                        operation = "account-selection.schedule-maintenance",
                        component = SupportDiagnosticComponent.Sync,
                    )
                },
            )
        }
        if (previousSession != null && previousSession.accountId != session.accountId) {
            clearPreviewAccount(NextcloudDocumentIds.cacheAccountId(previousSession))
        }
        resumeAndroidQueuedUploadsAfterSelection(
            resume = { resumeQueuedUploads(NextcloudDocumentIds.accountKey(session)) },
            notifyDocumentRootsChanged = notifyDocumentRootsChanged,
            recordFailure = {
                recordCredentialFailure(
                    code = "DURABLE_UPLOAD_RESUME_FAILED",
                    operation = "account-selection.upload-resume",
                    component = SupportDiagnosticComponent.Storage,
                )
            },
        )
    }

    private fun requireValidState(): AndroidAccountCredentialState = when (val read = readStore()) {
        is AndroidAccountCredentialStoreRead.Available -> read.state
        is AndroidAccountCredentialStoreRead.Invalid -> error("The account credential store is invalid.")
        AndroidAccountCredentialStoreRead.IndependentRecoveryUnavailable ->
            error("The independent account credential slots could not be recovered.")
        is AndroidAccountCredentialStoreRead.Unsupported -> unsupportedCredentialStoreMutation(read.version)
    }

    private fun readCredentialFreeRegistry(): NextcloudAccountRegistry? =
        ANDROID_ACCOUNT_CREDENTIAL_STORE_GUARD.serialize {
            preferences.getString(KEY_ACCOUNT_REGISTRY, null)?.let { encoded ->
                val restored = restoreAndroidCredentialFreeRegistry(encoded) {
                    val state = (readStore() as? AndroidAccountCredentialStoreRead.Available)?.state
                        ?: return@restoreAndroidCredentialFreeRegistry null
                    runCatching {
                        commitPreferences(
                            prepareCredentialSlotEdit(
                                preferences.edit().putString(
                                    KEY_ACCOUNT_REGISTRY,
                                    encodeNextcloudAccountRegistry(state.registry),
                                ),
                                state,
                            ),
                        )
                    }
                    state.registry
                }
                restored.diagnosticCode?.let { code ->
                    recordCredentialFailure(code, operation = "account-registry.restore")
                }
                return@serialize restored.registry
            }
            val state = (readStore() as? AndroidAccountCredentialStoreRead.Available)?.state
                ?: return@serialize null
            runCatching {
                commitPreferences(
                    prepareCredentialSlotEdit(
                        preferences.edit().putString(
                            KEY_ACCOUNT_REGISTRY,
                            encodeNextcloudAccountRegistry(state.registry),
                        ),
                        state,
                    ),
                )
            }
            state.registry
        }

    private fun readStore(): AndroidAccountCredentialStoreRead = ANDROID_ACCOUNT_CREDENTIAL_STORE_GUARD.serialize {
        val encrypted = preferences.getString(KEY_SESSION, null) ?: return@serialize run {
            val retained = readIndependentCredentialSlotState()
            when {
                retained != null -> availableCredentialStore(retained)
                hasIndependentCredentialState() -> AndroidAccountCredentialStoreRead.IndependentRecoveryUnavailable
                else -> availableCredentialStore(AndroidAccountCredentialState.Empty)
            }
        }
        val encoded = try {
            sessionCipher.decrypt(encrypted)
        } catch (_: Exception) {
            recordCredentialFailure(
                code = "ACCOUNT_CREDENTIAL_STORE_READ_FAILED",
                operation = "account-credentials.restore",
            )
            return@serialize AndroidAccountCredentialStoreRead.Invalid(encrypted)
        }
        val restored = restoreAndroidAccountCredentialStore(
            encoded = encoded,
            persistMigrated = { migrated ->
                val migratedState = requireNotNull(decodeAndroidAccountCredentialState(migrated).state)
                commitPreferences(
                    prepareCredentialSlotEdit(
                        preferences.edit()
                            .putString(KEY_SESSION, sessionCipher.encrypt(migrated))
                            .putString(
                                KEY_ACCOUNT_REGISTRY,
                                encodeNextcloudAccountRegistry(migratedState.registry),
                            ),
                        migratedState,
                    ),
                )
            },
            recordDiagnostic = recordDiagnostic,
        )
        return@serialize when {
            restored.unsupportedVersion != null ->
                AndroidAccountCredentialStoreRead.Unsupported(encrypted, restored.unsupportedVersion)
            restored.state != null -> availableCredentialStore(restored.state)
            else -> AndroidAccountCredentialStoreRead.Invalid(encrypted)
        }
    }

    private fun availableCredentialStore(
        state: AndroidAccountCredentialState,
    ): AndroidAccountCredentialStoreRead.Available {
        if (preferences.contains(KEY_QUARANTINED_SESSION)) {
            runCatching { commitPreferences(preferences.edit().remove(KEY_QUARANTINED_SESSION)) }
        }
        return AndroidAccountCredentialStoreRead.Available(state)
    }

    private fun readIndependentCredentialSlotState(): AndroidAccountCredentialState? {
        return restoreAndroidAccountCredentialStateWithoutAggregate(
            encodedRegistry = preferences.getString(KEY_ACCOUNT_REGISTRY, null),
            loadSession = ::readCredentialSlot,
        )
    }

    private fun hasIndependentCredentialState(): Boolean =
        preferences.contains(KEY_ACCOUNT_REGISTRY) ||
            preferences.all.keys.any { key -> key.startsWith(KEY_ACCOUNT_CREDENTIAL_SLOT_PREFIX) }

    private fun readCredentialSlot(accountId: NextcloudAccountId): NextcloudSession? = try {
        readAndroidAccountCredentialSlot(
            accountId = accountId,
            readEncrypted = { key -> preferences.getString(key, null) },
            decrypt = sessionCipher::decrypt,
            decode = { encoded ->
                restoreAndroidAccountCredentialState(
                    encoded = encoded,
                    persistMigrated = { migrated ->
                        commitPreferences(
                            preferences.edit().putString(
                                androidAccountCredentialSlotKey(accountId),
                                sessionCipher.encrypt(migrated),
                            ),
                        )
                    },
                    recordDiagnostic = recordDiagnostic,
                )?.activeSession
            },
        )
    } catch (_: Exception) {
        recordCredentialFailure(
            code = "ACCOUNT_CREDENTIAL_STORE_READ_FAILED",
            operation = "account-credentials.restore",
        )
        null
    }

    private suspend fun persistState(state: AndroidAccountCredentialState) = withContext(Dispatchers.IO) {
        commitPreferences(
            prepareCredentialSlotEdit(
                preferences.edit()
                    .putString(KEY_SESSION, encryptState(state))
                    .putString(KEY_ACCOUNT_REGISTRY, encodeNextcloudAccountRegistry(state.registry)),
                state,
            ),
        )
    }

    private fun commitPreferences(editor: SharedPreferences.Editor) = ANDROID_ACCOUNT_CREDENTIAL_STORE_GUARD.serialize {
        try {
            requireCommittedAndroidAccountCredentialEdit(editor)
        } catch (failure: Exception) {
            recordCredentialFailure(
                code = "ACCOUNT_CREDENTIAL_STORE_WRITE_FAILED",
                operation = "account-credentials.persist",
            )
            throw failure
        }
    }

    private fun encryptState(state: AndroidAccountCredentialState): String = try {
        sessionCipher.encrypt(encodeAndroidAccountCredentialState(state))
    } catch (failure: Exception) {
        recordCredentialFailure(
            code = "ACCOUNT_CREDENTIAL_STORE_WRITE_FAILED",
            operation = "account-credentials.persist",
        )
        throw failure
    }

    private fun encryptCredentialSlot(session: NextcloudSession): String = try {
        sessionCipher.encrypt(encodeAndroidPersistedSession(session))
    } catch (failure: Exception) {
        recordCredentialFailure(
            code = "ACCOUNT_CREDENTIAL_STORE_WRITE_FAILED",
            operation = "account-credentials.repair-slot",
        )
        throw failure
    }

    private fun prepareCredentialSlotEdit(
        editor: SharedPreferences.Editor,
        state: AndroidAccountCredentialState,
    ): SharedPreferences.Editor = editor.apply {
        remove(KEY_QUARANTINED_SESSION)
        val retainedKeys = state.sessions.keys.mapTo(hashSetOf(), ::androidAccountCredentialSlotKey)
        preferences.all.keys
            .filter { key -> key.startsWith(KEY_ACCOUNT_CREDENTIAL_SLOT_PREFIX) && key !in retainedKeys }
            .forEach(::remove)
        state.sessions.forEach { (accountId, session) ->
            putString(
                androidAccountCredentialSlotKey(accountId),
                sessionCipher.encrypt(encodeAndroidPersistedSession(session)),
            )
        }
    }

    private fun recordCredentialFailure(
        code: String,
        operation: String,
        component: SupportDiagnosticComponent = SupportDiagnosticComponent.Authentication,
    ) {
        recordDiagnostic(
            SupportDiagnosticEventDraft(
                severity = SupportDiagnosticSeverity.Error,
                component = component,
                operation = operation,
                outcome = "failed",
                code = code,
            ),
        )
    }

}

internal sealed interface AndroidAccountCredentialStoreRead {
    data class Available(val state: AndroidAccountCredentialState) : AndroidAccountCredentialStoreRead
    data class Invalid(val encrypted: String) : AndroidAccountCredentialStoreRead
    data object IndependentRecoveryUnavailable : AndroidAccountCredentialStoreRead
    data class Unsupported(val encrypted: String, val version: Int) : AndroidAccountCredentialStoreRead
}

private fun unsupportedCredentialStoreMutation(version: Int): Nothing =
    error("The account credential store version $version is unsupported.")

internal fun decodeAndroidCredentialFreeRegistry(encoded: String): NextcloudAccountRegistry? =
    decodeNextcloudAccountRegistry(encoded)

internal data class RestoredAndroidCredentialFreeRegistry(
    val registry: NextcloudAccountRegistry?,
    val diagnosticCode: String? = null,
)

internal fun restoreAndroidCredentialFreeRegistry(
    encoded: String,
    recoverMalformed: () -> NextcloudAccountRegistry?,
): RestoredAndroidCredentialFreeRegistry {
    val restored = restoreNextcloudAccountRegistry(encoded, legacySession = null)
    val recoveryReason = restored.recoveryReason
    return when (recoveryReason) {
        null -> RestoredAndroidCredentialFreeRegistry(restored.registry)
        NextcloudAccountRegistryRecoveryReason.UnsupportedRegistryVersion ->
            RestoredAndroidCredentialFreeRegistry(null, recoveryReason.diagnosticCode)
        else -> RestoredAndroidCredentialFreeRegistry(recoverMalformed(), recoveryReason.diagnosticCode)
    }
}

internal fun androidAccountCredentialSlotKey(accountId: NextcloudAccountId): String =
    "$KEY_ACCOUNT_CREDENTIAL_SLOT_PREFIX${accountId.storageKey}"

internal fun readAndroidAccountCredentialSlot(
    accountId: NextcloudAccountId,
    readEncrypted: (String) -> String?,
    decrypt: (String) -> String,
    decode: (String) -> NextcloudSession?,
): NextcloudSession? {
    val encrypted = readEncrypted(androidAccountCredentialSlotKey(accountId)) ?: return null
    return decode(decrypt(encrypted))?.takeIf { session -> session.accountId == accountId }
}

internal fun recoverAndroidAccountCredentialSlot(
    accountId: NextcloudAccountId,
    registry: NextcloudAccountRegistry,
    storedSlot: NextcloudSession?,
    aggregate: AndroidAccountCredentialState?,
): NextcloudSession? {
    val account = registry.accounts.firstOrNull { candidate -> candidate.id == accountId } ?: return null
    return storedSlot?.takeIf { session -> session.accountRecord() == account }
        ?: aggregate?.sessions?.get(accountId)?.takeIf { session -> session.accountRecord() == account }
}

internal fun reconstructAndroidAccountCredentialState(
    registry: NextcloudAccountRegistry,
    loadSession: (NextcloudAccountId) -> NextcloudSession?,
): AndroidAccountCredentialState? {
    val sessions = linkedMapOf<NextcloudAccountId, NextcloudSession>()
    val unavailableAccounts = mutableListOf<NextcloudAccountId>()
    registry.accounts.forEach { account ->
        val session = loadSession(account.id)?.takeIf { loaded -> loaded.accountRecord() == account }
        if (session == null) unavailableAccounts += account.id else sessions[account.id] = session
    }
    if (registry.activeAccountId in unavailableAccounts) return null
    val retainedRegistry = unavailableAccounts.fold(registry) { retained, accountId -> retained.remove(accountId) }
    return AndroidAccountCredentialState(retainedRegistry, sessions)
}

internal fun restoreAndroidAccountCredentialStateWithoutAggregate(
    encodedRegistry: String?,
    loadSession: (NextcloudAccountId) -> NextcloudSession?,
): AndroidAccountCredentialState? {
    val restored = encodedRegistry
        ?.let { encoded -> restoreNextcloudAccountRegistry(encoded, legacySession = null) }
        ?: return null
    if (restored.recoveryReason != null) return null
    return reconstructAndroidAccountCredentialState(restored.registry, loadSession)
}

internal fun removeActiveAndroidAccountCredentialState(
    state: AndroidAccountCredentialState,
): AndroidAccountCredentialState = state.registry.activeAccountId?.let(state::remove) ?: state

internal suspend fun resumeAndroidQueuedUploadsAfterSelection(
    resume: suspend () -> Unit,
    notifyDocumentRootsChanged: () -> Unit,
    recordFailure: () -> Unit,
) {
    try {
        resume()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        recordFailure()
    } finally {
        notifyDocumentRootsChanged()
    }
}

internal suspend fun removeAndroidAccountCredentialData(
    active: Boolean,
    prepareAccountRemoval: suspend () -> Unit = {},
    removeQueuedUploads: suspend () -> Unit,
    clearActiveAccount: suspend () -> Unit,
    rollbackActiveRemoval: suspend () -> Unit,
    persistInactiveRemoval: suspend () -> Unit,
    rollbackInactiveRemoval: suspend () -> Unit,
) {
    prepareAccountRemoval()
    if (active) {
        try {
            clearActiveAccount()
            removeQueuedUploads()
        } catch (failure: Exception) {
            withContext(NonCancellable) {
                runCatching { rollbackActiveRemoval() }
                    .onFailure(failure::addSuppressed)
            }
            throw failure
        }
        return
    }

    persistInactiveRemoval()
    try {
        removeQueuedUploads()
    } catch (failure: Exception) {
        withContext(NonCancellable) {
            runCatching { rollbackInactiveRemoval() }
                .onFailure(failure::addSuppressed)
        }
        throw failure
    }
}

internal suspend fun removeRecoveredAndroidAccountCredentialData(
    prepareAccountRemoval: suspend () -> Unit = {},
    removeQueuedUploads: suspend () -> Unit,
    clearRecoveredAccount: suspend () -> Unit,
    rollbackRecoveredAccount: suspend () -> Unit,
) = removeAndroidAccountCredentialData(
    active = true,
    prepareAccountRemoval = prepareAccountRemoval,
    removeQueuedUploads = removeQueuedUploads,
    clearActiveAccount = clearRecoveredAccount,
    rollbackActiveRemoval = rollbackRecoveredAccount,
    persistInactiveRemoval = {},
    rollbackInactiveRemoval = {},
)

internal class AndroidAccountCredentialStoreGuard {
    private val monitor = Any()

    fun <Result> serialize(action: () -> Result): Result = synchronized(monitor, action)
}

internal fun prepareInvalidAndroidAccountCredentialRecoveryEdit(
    editor: SharedPreferences.Editor,
    replacementEncrypted: String?,
): SharedPreferences.Editor = editor.apply {
    remove(KEY_QUARANTINED_SESSION)
    if (replacementEncrypted == null) remove(KEY_SESSION) else putString(KEY_SESSION, replacementEncrypted)
    remove(KEY_TEST_READ_ONLY)
}

internal fun requireCommittedAndroidAccountCredentialEdit(editor: SharedPreferences.Editor) {
    check(editor.commit()) { "The account credential store could not be committed." }
}

internal fun resolveStoredAndroidAccountSession(
    accountIdentity: String,
    listAccounts: () -> List<NextcloudAccountRecord>,
    loadSession: (NextcloudAccountId) -> NextcloudSession?,
): NextcloudSession? {
    val accountId = listAccounts().firstOrNull { account ->
        NextcloudDocumentIds.accountKey(
            NextcloudSession(account.serverUrl, account.loginName, appPassword = ""),
        ) == accountIdentity
    }?.id ?: return null
    return loadSession(accountId)?.takeIf { session ->
        NextcloudDocumentIds.accountKey(session) == accountIdentity
    }
}

private const val KEY_SESSION = "encrypted_session"
private const val KEY_ACCOUNT_REGISTRY = "account_registry_v1"
private const val KEY_ACCOUNT_CREDENTIAL_SLOT_PREFIX = "account_credential_v1:"
private const val KEY_QUARANTINED_SESSION = "encrypted_session_quarantine"
private val ANDROID_ACCOUNT_CREDENTIAL_STORE_GUARD = AndroidAccountCredentialStoreGuard()
private val ANDROID_ACCOUNT_CREDENTIAL_MUTATION_MUTEX = Mutex()
