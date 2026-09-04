package dev.obiente.nextcloudnative

import android.content.Context
import android.content.SharedPreferences
import dev.obiente.nextcloudnative.app.NextcloudAccountId
import dev.obiente.nextcloudnative.app.NextcloudAccountRecord
import dev.obiente.nextcloudnative.app.NextcloudAccountRegistry
import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.SupportDiagnosticComponent
import dev.obiente.nextcloudnative.app.SupportDiagnosticEventDraft
import dev.obiente.nextcloudnative.app.SupportDiagnosticSeverity
import dev.obiente.nextcloudnative.app.accountRecord
import dev.obiente.nextcloudnative.app.encodeNextcloudAccountRegistry
import dev.obiente.nextcloudnative.app.restoreNextcloudAccountRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
    private val retryQueuedUploadsCleanup: suspend (String) -> Unit,
) {
    private val appContext = context.applicationContext

    fun loadSession(): NextcloudSession? = ANDROID_FILE_SYNC_SESSION_SCHEDULING_GUARD.restorePersistedSession(
        load = {
            val registry = readRegistryForCredentialLoad()
            registry?.activeAccountId?.let { accountId -> loadSession(accountId, registry) }
        },
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
            val registry = readRegistryForCredentialLoad() ?: return@serialize null
            loadSession(accountId, registry)
        }

    private fun loadSession(
        accountId: NextcloudAccountId,
        registry: NextcloudAccountRegistry,
    ): NextcloudSession? = ANDROID_ACCOUNT_CREDENTIAL_STORE_GUARD.serialize {
        if (registry.accounts.none { account -> account.id == accountId }) return@serialize null
        val aggregateRead = readStore()
        if (!androidCredentialStoreAllowsSessionRestore(aggregateRead)) return@serialize null
        val aggregate = (aggregateRead as? AndroidAccountCredentialStoreRead.Available)?.state
        val storedSlot = readCredentialSlot(accountId)
        val restoredSlot = recoverAndroidAccountCredentialSlot(accountId, registry, storedSlot, aggregate = null)
        val session = restoredSlot ?: recoverAndroidAccountCredentialSlot(
            accountId,
            registry,
            storedSlot = null,
            aggregate = aggregate,
        ) ?: return@serialize null
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
            retryPendingAccountRemovalCleanup(NextcloudDocumentIds.accountKey(session))
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
            val accountIdentity = NextcloudDocumentIds.accountKey(session)
            ANDROID_ACCOUNT_OPERATION_GUARD.withAccount(accountIdentity) {
                val active = current.registry.activeAccountId == accountId
                removeAndroidAccountCredentialData(
                    active = active,
                    prepareAccountRemoval = { prepareAccountRemoval(session) },
                    removeQueuedUploads = { removeQueuedUploads(session) },
                    clearActiveAccount = { clearSession(current, accountIdentity) },
                    rollbackActiveRemoval = {
                        replaceActiveStateWhileOperationsIdle(
                            replacement = current,
                            previousSession = null,
                            suspectEncrypted = null,
                        )
                        clearPendingAccountRemovalCleanup(accountIdentity)
                    },
                    persistInactiveRemoval = { persistState(current.remove(accountId), accountIdentity) },
                    rollbackInactiveRemoval = {
                        persistState(current)
                        clearPendingAccountRemovalCleanup(accountIdentity)
                    },
                    completeCommittedCleanup = { clearPendingAccountRemovalCleanup(accountIdentity) },
                    recordCommittedCleanupFailure = { recordAccountRemovalCleanupFailure() },
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
                    val accountIdentity = NextcloudDocumentIds.accountKey(session)
                    ANDROID_ACCOUNT_OPERATION_GUARD.withAccount(accountIdentity) {
                        removeAndroidAccountCredentialData(
                            active = true,
                            prepareAccountRemoval = { prepareAccountRemoval(session) },
                            removeQueuedUploads = { removeQueuedUploads(session) },
                            clearActiveAccount = { clearSession(read.state, accountIdentity) },
                            rollbackActiveRemoval = {
                                replaceActiveStateWhileOperationsIdle(
                                    replacement = read.state,
                                    previousSession = null,
                                    suspectEncrypted = null,
                                )
                                clearPendingAccountRemovalCleanup(accountIdentity)
                            },
                            persistInactiveRemoval = {},
                            rollbackInactiveRemoval = {},
                            completeCommittedCleanup = {
                                clearPendingAccountRemovalCleanup(accountIdentity)
                            },
                            recordCommittedCleanupFailure = { recordAccountRemovalCleanupFailure() },
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

    private suspend fun clearSession(
        current: AndroidAccountCredentialState,
        pendingCleanupAccountIdentity: String? = null,
    ) {
        val activeSession = current.activeSession ?: return
        val replacement = current.remove(activeSession.accountId)
        val encodedReplacement = replacement.takeUnless { state ->
            state.registry.accounts.isEmpty() && state.sessions.isEmpty()
        }?.let(::encryptState)
        clearPersistedSession(encodedReplacement, replacement, pendingCleanupAccountIdentity = pendingCleanupAccountIdentity)
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
            val accountIdentity = NextcloudDocumentIds.accountKey(activeSession)
            ANDROID_ACCOUNT_OPERATION_GUARD.withAccount(accountIdentity) {
                removeRecoveredAndroidAccountCredentialData(
                    prepareAccountRemoval = { prepareAccountRemoval(activeSession) },
                    removeQueuedUploads = { removeQueuedUploads(activeSession) },
                    clearRecoveredAccount = {
                        persistRecoveredInvalidStoreAfterClear(current, suspectEncrypted, accountIdentity)
                    },
                    rollbackRecoveredAccount = {
                        replaceActiveStateWhileOperationsIdle(
                            replacement = current,
                            previousSession = null,
                            suspectEncrypted = suspectEncrypted,
                        )
                        clearPendingAccountRemovalCleanup(accountIdentity)
                    },
                    completeCommittedCleanup = { clearPendingAccountRemovalCleanup(accountIdentity) },
                    recordCommittedCleanupFailure = { recordAccountRemovalCleanupFailure() },
                )
            }
        } else {
            persistRecoveredInvalidStoreAfterClear(current, suspectEncrypted)
        }
    }

    private suspend fun persistRecoveredInvalidStoreAfterClear(
        current: AndroidAccountCredentialState,
        suspectEncrypted: String,
        pendingCleanupAccountIdentity: String? = null,
    ) {
        val activeSession = current.activeSession
        val replacement = removeActiveAndroidAccountCredentialState(current)
        val encodedReplacement = replacement.takeUnless { state ->
            state.registry.accounts.isEmpty() && state.sessions.isEmpty()
        }?.let(::encryptState)
        clearPersistedSession(
            encodedReplacement,
            replacement,
            suspectEncrypted,
            pendingCleanupAccountIdentity,
        )
        activeSession?.let { session -> clearPreviewAccount(NextcloudDocumentIds.cacheAccountId(session)) }
        notifyDocumentRootsChanged()
    }

    private suspend fun clearPersistedSession(
        encodedReplacement: String?,
        replacement: AndroidAccountCredentialState,
        suspectEncrypted: String? = null,
        pendingCleanupAccountIdentity: String? = null,
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
                    commitPreferences(preparePendingAccountRemovalCleanupEdit(editor, pendingCleanupAccountIdentity))
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
        clearAndroidPreviousPreviewAfterCommittedSelection(
            previousSession = previousSession,
            selectedSession = session,
            clearPreviewAccount = clearPreviewAccount,
            recordFailure = { recordAccountSelectionCacheCleanupFailure() },
        )
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
            val encoded = preferences.getString(KEY_ACCOUNT_REGISTRY, null) ?: return@serialize null
            val restored = restoreAndroidCredentialFreeRegistry(encoded)
            recordCredentialFreeRegistryDiagnostic(restored)
            restored.registry
        }

    private fun readRegistryForCredentialLoad(): NextcloudAccountRegistry? =
        ANDROID_ACCOUNT_CREDENTIAL_STORE_GUARD.serialize {
            val encoded = preferences.getString(KEY_ACCOUNT_REGISTRY, null)
            val restored = encoded?.let(::restoreAndroidCredentialFreeRegistry)
            restored?.let(::recordCredentialFreeRegistryDiagnostic)
            recoverAndroidCredentialFreeRegistryForCredentialLoad(restored) {
                val state = (readStore() as? AndroidAccountCredentialStoreRead.Available)?.state
                    ?: return@recoverAndroidCredentialFreeRegistryForCredentialLoad null
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
        }

    private fun recordCredentialFreeRegistryDiagnostic(restored: RestoredAndroidCredentialFreeRegistry) {
        restored.diagnosticCode?.let { code ->
            recordCredentialFailure(code, operation = "account-registry.restore")
        }
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

    private suspend fun persistState(
        state: AndroidAccountCredentialState,
        pendingCleanupAccountIdentity: String? = null,
    ) = withContext(Dispatchers.IO) {
        commitPreferences(
            preparePendingAccountRemovalCleanupEdit(
                prepareCredentialSlotEdit(
                    preferences.edit()
                        .putString(KEY_SESSION, encryptState(state))
                        .putString(KEY_ACCOUNT_REGISTRY, encodeNextcloudAccountRegistry(state.registry)),
                    state,
                ),
                pendingCleanupAccountIdentity,
            ),
        )
    }

    private suspend fun retryPendingAccountRemovalCleanup(accountIdentity: String) {
        if (accountIdentity !in pendingAccountRemovalCleanupIdentities()) return
        try {
            retryQueuedUploadsCleanup(accountIdentity)
            clearPendingAccountRemovalCleanup(accountIdentity)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            recordAccountRemovalCleanupFailure()
            throw IllegalStateException(
                "Previous account cleanup must finish before this account can be added again.",
                failure,
            )
        }
    }

    private fun pendingAccountRemovalCleanupIdentities(): Set<String> =
        preferences.getStringSet(KEY_PENDING_ACCOUNT_REMOVAL_CLEANUP, emptySet())?.toSet().orEmpty()

    private fun preparePendingAccountRemovalCleanupEdit(
        editor: SharedPreferences.Editor,
        accountIdentity: String?,
    ): SharedPreferences.Editor = if (accountIdentity == null) {
        editor
    } else {
        editor.putStringSet(
            KEY_PENDING_ACCOUNT_REMOVAL_CLEANUP,
            pendingAccountRemovalCleanupIdentities() + accountIdentity,
        )
    }

    private fun clearPendingAccountRemovalCleanup(accountIdentity: String) {
        val remaining = pendingAccountRemovalCleanupIdentities() - accountIdentity
        val editor = preferences.edit()
        if (remaining.isEmpty()) editor.remove(KEY_PENDING_ACCOUNT_REMOVAL_CLEANUP)
        else editor.putStringSet(KEY_PENDING_ACCOUNT_REMOVAL_CLEANUP, remaining)
        commitPreferences(editor)
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
    private fun recordAccountRemovalCleanupFailure() = recordCredentialFailure(
        code = "ACCOUNT_REMOVAL_CLEANUP_FAILED",
        operation = "account.remove-cleanup",
        component = SupportDiagnosticComponent.Sync,
    )
    private fun recordAccountSelectionCacheCleanupFailure() = recordCredentialFailure(
        code = "ACCOUNT_SELECTION_CACHE_CLEANUP_FAILED",
        operation = "account-selection.cache-cleanup",
        component = SupportDiagnosticComponent.Cache,
    )

}
internal sealed interface AndroidAccountCredentialStoreRead {
    data class Available(val state: AndroidAccountCredentialState) : AndroidAccountCredentialStoreRead
    data class Invalid(val encrypted: String) : AndroidAccountCredentialStoreRead
    data object IndependentRecoveryUnavailable : AndroidAccountCredentialStoreRead
    data class Unsupported(val encrypted: String, val version: Int) : AndroidAccountCredentialStoreRead
}

private fun unsupportedCredentialStoreMutation(version: Int): Nothing =
    error("The account credential store version $version is unsupported.")

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

internal fun androidCredentialStoreAllowsSessionRestore(
    read: AndroidAccountCredentialStoreRead,
): Boolean = read !is AndroidAccountCredentialStoreRead.Unsupported

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
private const val KEY_PENDING_ACCOUNT_REMOVAL_CLEANUP = "pending_account_removal_cleanup_v1"
private val ANDROID_ACCOUNT_CREDENTIAL_STORE_GUARD = AndroidAccountCredentialStoreGuard()
private val ANDROID_ACCOUNT_CREDENTIAL_MUTATION_MUTEX = Mutex()
