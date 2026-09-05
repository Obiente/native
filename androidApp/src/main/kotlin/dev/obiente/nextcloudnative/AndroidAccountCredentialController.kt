package dev.obiente.nextcloudnative

import android.content.Context
import android.content.SharedPreferences
import dev.obiente.nextcloudnative.app.NextcloudAccountId
import dev.obiente.nextcloudnative.app.NextcloudAccountRegistry
import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.SupportDiagnosticComponent
import dev.obiente.nextcloudnative.app.SupportDiagnosticEventDraft
import dev.obiente.nextcloudnative.app.SupportDiagnosticSeverity
import dev.obiente.nextcloudnative.app.accountRecord
import dev.obiente.nextcloudnative.app.encodeNextcloudAccountRegistry
import dev.obiente.nextcloudnative.app.toSupportDiagnosticExceptionDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
    private val retryQueuedUploadsCleanup: suspend (NextcloudSession, String, String?, String?) -> Unit,
    private val retryQueuedUploadsCleanupWithoutCredentials: suspend (String, String, String?, String?) -> Unit,
) {
    private val appContext = context.applicationContext
    private val handoffCleanup = AndroidExternalFileHandoffCleanup(appContext, preferences, ::commitPreferences)
    private val accountRemovalCleanupJournal = AndroidAccountRemovalCleanupJournal(
        preferences = preferences,
        commit = ::commitPreferences,
        recordMalformed = {
            recordCredentialFailure(
                code = "ACCOUNT_REMOVAL_CLEANUP_JOURNAL_MALFORMED",
                operation = "account.remove-cleanup.restore",
                component = SupportDiagnosticComponent.Sync,
            )
        },
    )

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

    fun accountRetentionSnapshot(): AndroidAccountRetentionSnapshot = readRegistryForCredentialLoad()
        ?.let { registry -> AndroidAccountRetentionSnapshot.Available(registry.accounts) }
        ?: AndroidAccountRetentionSnapshot.Unavailable

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
        val slotRead = readCredentialSlot(accountId)
        if (slotRead is AndroidAccountCredentialSlotRead.Unsupported) return@serialize null
        val storedSlot = (slotRead as? AndroidAccountCredentialSlotRead.Available)?.session
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
            retryPendingAccountRemovalCleanup(session)
            registerSessionPrivateValues(session)
            when (val read = readStore()) {
                is AndroidAccountCredentialStoreRead.Available -> {
                    requireSupportedCredentialSlots(read.state.registry)
                    replaceActiveState(read.state.upsertAndSelect(session), read.state.activeSession)
                }
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
            val (current, suspectEncrypted) = recoverAndroidAccountCredentialStateForSelection(
                readStore(), ::readIndependentCredentialSlotState,
            )
            requireSupportedCredentialSlots(current.registry)
            val selected = current.select(accountId) ?: return@withLock null
            val session = requireNotNull(selected.activeSession)
            registerSessionPrivateValues(session)
            replaceActiveState(selected, current.activeSession, suspectEncrypted)
            session
        }

    suspend fun removeAccount(accountId: NextcloudAccountId): Boolean =
        ANDROID_ACCOUNT_CREDENTIAL_MUTATION_MUTEX.withLock {
            val current = requireValidStateForAccountRemoval(accountId)
            val session = current.sessions[accountId]
                ?: return@withLock removeUnavailableAccount(accountId, current)
            val pendingCleanup = pendingAndroidAccountRemovalCleanup(session)
            withAndroidAccountRemovalLease(NextcloudDocumentIds.accountKey(session)) {
                val active = current.registry.activeAccountId == accountId
                removeAndroidAccountCredentialData(
                    active = active,
                    prepareAccountRemoval = { prepareAccountRemoval(session) },
                    removeQueuedUploads = { removeQueuedUploads(session) },
                    clearActiveAccount = { clearSession(current, pendingCleanup) },
                    rollbackActiveRemoval = {
                        replaceActiveStateWhileOperationsIdle(
                            replacement = current,
                            previousSession = null,
                            suspectEncrypted = null,
                        )
                        accountRemovalCleanupJournal.clear(accountId.storageKey)
                    },
                    persistInactiveRemoval = { persistState(current.remove(accountId), pendingCleanup) },
                    rollbackInactiveRemoval = {
                        persistState(current)
                        accountRemovalCleanupJournal.clear(accountId.storageKey)
                    },
                    completeCommittedCleanup = { accountRemovalCleanupJournal.clear(accountId.storageKey) },
                    recordCommittedCleanupFailure = ::recordAccountRemovalCleanupFailure,
                )
            }
            true
        }

    private suspend fun removeUnavailableAccount(
        accountId: NextcloudAccountId,
        recovered: AndroidAccountCredentialState,
    ): Boolean {
        val target = resolveAndroidUnavailableAccountRemovalTarget(readCredentialFreeRegistry(), accountId) ?: return false
        val unavailableSession = NextcloudSession(target.record.serverUrl, target.record.loginName, appPassword = "")
        val accountIdentity = NextcloudDocumentIds.accountKey(unavailableSession)
        val pendingCleanup = pendingAndroidAccountRemovalCleanup(unavailableSession)
        withAndroidAccountRemovalLease(accountIdentity) {
            removeUnavailableAndroidAccountCredentialData(
                accountIdentity = accountIdentity,
                active = target.wasActive,
                prepareAccountRemoval = { prepareAccountRemoval(unavailableSession) },
                removeAccountOwnedWorkWithoutCredentials = { identity ->
                    retryQueuedUploadsCleanupWithoutCredentials(
                        pendingCleanup.accountStorageKey,
                        identity,
                        pendingCleanup.previewCacheIdentity,
                        pendingCleanup.durableMutationIdentity,
                    )
                },
                persistRemoval = { persistState(recovered.remove(accountId), pendingCleanup) },
                clearActiveAccount = { clearSession(recovered, pendingCleanup, unavailableSession) },
                rollbackRemoval = {
                    rollbackUnavailableAndroidAccountRemoval(
                        active = target.wasActive, recovered = recovered, persistRecovered = { state -> persistState(state) },
                        clearCleanup = { accountRemovalCleanupJournal.clear(accountId.storageKey) },
                    )
                },
                completeCommittedCleanup = { accountRemovalCleanupJournal.clear(accountId.storageKey) },
                recordCommittedCleanupFailure = ::recordAccountRemovalCleanupFailure,
            )
        }
        notifyDocumentRootsChanged()
        return true
    }

    suspend fun revokeSession(
        expectedSession: NextcloudSession,
        revokeRemoteSession: suspend () -> Unit,
    ) = ANDROID_ACCOUNT_CREDENTIAL_MUTATION_MUTEX.withLock {
        val current = requireValidState()
        check(current.activeSession == expectedSession) {
            "The account changed before its remote session could be revoked."
        }
        val accountIdentity = NextcloudDocumentIds.accountKey(expectedSession)
        val pendingCleanup = pendingAndroidAccountRemovalCleanup(expectedSession)
        revokeAndroidSessionWithAccountLease(
            accountIdentity = accountIdentity,
            preflight = { prepareAccountRemoval(expectedSession) },
            revoke = revokeRemoteSession,
            removeLocalAccount = {
                removeAndroidAccountCredentialData(
                    active = true,
                    removeQueuedUploads = { removeQueuedUploads(expectedSession) },
                    clearActiveAccount = { clearSession(current, pendingCleanup) },
                    rollbackActiveRemoval = {
                        replaceActiveStateWhileOperationsIdle(current, previousSession = null, suspectEncrypted = null)
                        accountRemovalCleanupJournal.clear(expectedSession.accountId.storageKey)
                    },
                    persistInactiveRemoval = {},
                    rollbackInactiveRemoval = {},
                    completeCommittedCleanup = {
                        accountRemovalCleanupJournal.clear(expectedSession.accountId.storageKey)
                    },
                    recordCommittedCleanupFailure = ::recordAccountRemovalCleanupFailure,
                )
            },
        )
    }

    suspend fun clearSession() = ANDROID_ACCOUNT_CREDENTIAL_MUTATION_MUTEX.withLock {
        when (val read = readStore()) {
            is AndroidAccountCredentialStoreRead.Available -> {
                requireSupportedCredentialSlots(read.state.registry)
                val session = read.state.activeSession
                if (session == null) {
                    clearSession(read.state)
                } else {
                    val accountIdentity = NextcloudDocumentIds.accountKey(session)
                    val pendingCleanup = pendingAndroidAccountRemovalCleanup(session)
                    withAndroidAccountRemovalLease(accountIdentity) {
                        removeAndroidAccountCredentialData(
                            active = true,
                            prepareAccountRemoval = { prepareAccountRemoval(session) },
                            removeQueuedUploads = { removeQueuedUploads(session) },
                            clearActiveAccount = { clearSession(read.state, pendingCleanup) },
                            rollbackActiveRemoval = {
                                replaceActiveStateWhileOperationsIdle(
                                    replacement = read.state,
                                    previousSession = null,
                                    suspectEncrypted = null,
                                )
                                accountRemovalCleanupJournal.clear(session.accountId.storageKey)
                            },
                            persistInactiveRemoval = {},
                            rollbackInactiveRemoval = {},
                            completeCommittedCleanup = {
                                accountRemovalCleanupJournal.clear(session.accountId.storageKey)
                            },
                            recordCommittedCleanupFailure = ::recordAccountRemovalCleanupFailure,
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
        current: AndroidAccountCredentialState, pendingCleanup: AndroidPendingAccountRemovalCleanup? = null,
        activeFallback: NextcloudSession? = null,
    ) {
        val removal = resolveAndroidActiveAccountRemovalTransition(current, activeFallback) ?: return
        val replacement = removal.replacement
        val encodedReplacement = replacement.takeUnless { state ->
            state.registry.accounts.isEmpty() && state.sessions.isEmpty()
        }?.let(::encryptState)
        clearPersistedSession(encodedReplacement, replacement, pendingCleanup = pendingCleanup)
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
            val pendingCleanup = pendingAndroidAccountRemovalCleanup(activeSession)
            withAndroidAccountRemovalLease(accountIdentity) {
                removeRecoveredAndroidAccountCredentialData(
                    prepareAccountRemoval = { prepareAccountRemoval(activeSession) },
                    removeQueuedUploads = { removeQueuedUploads(activeSession) },
                    clearRecoveredAccount = {
                        persistRecoveredInvalidStoreAfterClear(current, suspectEncrypted, pendingCleanup)
                    },
                    rollbackRecoveredAccount = {
                        replaceActiveStateWhileOperationsIdle(
                            replacement = current,
                            previousSession = null,
                            suspectEncrypted = suspectEncrypted,
                        )
                        accountRemovalCleanupJournal.clear(activeSession.accountId.storageKey)
                    },
                    completeCommittedCleanup = {
                        accountRemovalCleanupJournal.clear(activeSession.accountId.storageKey)
                    },
                    recordCommittedCleanupFailure = ::recordAccountRemovalCleanupFailure,
                )
            }
        } else {
            persistRecoveredInvalidStoreAfterClear(current, suspectEncrypted)
        }
    }

    private suspend fun persistRecoveredInvalidStoreAfterClear(
        current: AndroidAccountCredentialState,
        suspectEncrypted: String,
        pendingCleanup: AndroidPendingAccountRemovalCleanup? = null,
    ) {
        val replacement = removeActiveAndroidAccountCredentialState(current)
        val encodedReplacement = replacement.takeUnless { state ->
            state.registry.accounts.isEmpty() && state.sessions.isEmpty()
        }?.let(::encryptState)
        clearPersistedSession(
            encodedReplacement,
            replacement,
            suspectEncrypted,
            pendingCleanup,
        )
        notifyDocumentRootsChanged()
    }

    private suspend fun clearPersistedSession(
        encodedReplacement: String?,
        replacement: AndroidAccountCredentialState,
        suspectEncrypted: String? = null,
        pendingCleanup: AndroidPendingAccountRemovalCleanup? = null,
    ) {
        val scheduler = AndroidFileSyncScheduler(appContext)
        withContext(Dispatchers.IO) {
            commitAndroidAccountTransitionBeforeHandoffCleanup(
                commitTransition = {
                    ANDROID_FILE_SYNC_SESSION_SCHEDULING_GUARD.clearSession(
                        persist = {
                            val editor = if (suspectEncrypted == null) {
                                preferences.edit().apply {
                                    if (encodedReplacement == null) remove(ANDROID_ACCOUNT_SESSION_KEY)
                                    else putString(ANDROID_ACCOUNT_SESSION_KEY, encodedReplacement)
                                    putString(
                                        ANDROID_ACCOUNT_REGISTRY_KEY,
                                        encodeNextcloudAccountRegistry(replacement.registry),
                                    )
                                    remove(KEY_TEST_READ_ONLY)
                                }.let { editor -> prepareCredentialSlotEdit(editor, replacement) }
                            } else {
                                prepareInvalidAndroidAccountCredentialRecoveryEdit(
                                    editor = preferences.edit(),
                                    replacementEncrypted = encodedReplacement,
                                ).putString(
                                    ANDROID_ACCOUNT_REGISTRY_KEY,
                                    encodeNextcloudAccountRegistry(replacement.registry),
                                ).let { editor -> prepareCredentialSlotEdit(editor, replacement) }
                            }
                            commitPreferences(handoffCleanup.prepare(accountRemovalCleanupJournal.prepareEdit(editor, pendingCleanup)))
                        },
                        cancelAll = scheduler::cancelAll,
                        clearPublishedAccount = { publishAccountIdentity(null) },
                        onScheduleMaintenanceFailure = ::recordAccountRemovalCleanupFailure,
                    )
                },
                clearHandoffs = handoffCleanup::complete,
                recordFailure = ::recordAccountHandoffCleanupFailure,
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
        val scheduler = AndroidFileSyncScheduler(appContext)
        completeAndroidAccountSelectionTransition(
            commitTransition = { markCommitted ->
                commitAndroidAccountTransitionBeforeHandoffCleanup(
                    commitTransition = {
                        ANDROID_FILE_SYNC_SESSION_SCHEDULING_GUARD.replaceSession(
                            replacementAccountId = NextcloudDocumentIds.accountKey(session),
                            persist = {
                                val editor = if (suspectEncrypted == null) {
                                    preferences.edit()
                                        .putString(ANDROID_ACCOUNT_SESSION_KEY, encrypted)
                                        .putString(
                                            ANDROID_ACCOUNT_REGISTRY_KEY,
                                            encodeNextcloudAccountRegistry(replacement.registry),
                                        )
                                        .remove(KEY_TEST_READ_ONLY)
                                } else {
                                    prepareInvalidAndroidAccountCredentialRecoveryEdit(
                                        editor = preferences.edit(),
                                        replacementEncrypted = encrypted,
                                    ).putString(
                                        ANDROID_ACCOUNT_REGISTRY_KEY,
                                        encodeNextcloudAccountRegistry(replacement.registry),
                                    )
                                }
                                commitPreferences(handoffCleanup.prepare(prepareCredentialSlotEdit(editor, replacement)))
                                markCommitted()
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
                    },
                    clearHandoffs = handoffCleanup::complete,
                    recordFailure = ::recordAccountHandoffCleanupFailure,
                )
            },
            finishMaintenance = {
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
            },
        )
    }

    private fun requireValidState(): AndroidAccountCredentialState = requireValidAndroidAccountCredentialState(
        readStore(), ::requireSupportedCredentialSlots,
    )

    private fun requireValidStateForAccountRemoval(accountId: NextcloudAccountId): AndroidAccountCredentialState =
        when (val read = readStore()) {
            is AndroidAccountCredentialStoreRead.Available -> read.state.also { state ->
                requireSupportedCredentialSlots(state.registry)
            }
            is AndroidAccountCredentialStoreRead.Invalid,
            AndroidAccountCredentialStoreRead.IndependentRecoveryUnavailable,
            -> readIndependentCredentialSlotState(allowUnavailableActiveAccountId = accountId)
                ?: error("The independent account credential slots could not be recovered.")
            is AndroidAccountCredentialStoreRead.Unsupported -> unsupportedCredentialStoreMutation(read.version)
        }

    private fun readCredentialFreeRegistry(): NextcloudAccountRegistry? =
        ANDROID_ACCOUNT_CREDENTIAL_STORE_GUARD.serialize {
            val encoded = preferences.getString(ANDROID_ACCOUNT_REGISTRY_KEY, null) ?: return@serialize null
            val restored = restoreAndroidCredentialFreeRegistry(encoded)
            recordCredentialFreeRegistryDiagnostic(restored)
            restored.registry
        }

    private fun readRegistryForCredentialLoad(): NextcloudAccountRegistry? =
        ANDROID_ACCOUNT_CREDENTIAL_STORE_GUARD.serialize {
            val encoded = preferences.getString(ANDROID_ACCOUNT_REGISTRY_KEY, null)
            val restored = encoded?.let(::restoreAndroidCredentialFreeRegistry)
            restored?.let(::recordCredentialFreeRegistryDiagnostic)
            recoverAndroidCredentialFreeRegistryForCredentialLoad(restored) {
                val state = (readStore() as? AndroidAccountCredentialStoreRead.Available)?.state
                    ?: return@recoverAndroidCredentialFreeRegistryForCredentialLoad null
                runCatching {
                    commitPreferences(
                        prepareCredentialSlotEdit(
                            preferences.edit().putString(
                                ANDROID_ACCOUNT_REGISTRY_KEY,
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
        val encrypted = preferences.getString(ANDROID_ACCOUNT_SESSION_KEY, null) ?: return@serialize run {
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
                            .putString(ANDROID_ACCOUNT_SESSION_KEY, sessionCipher.encrypt(migrated))
                            .putString(
                                ANDROID_ACCOUNT_REGISTRY_KEY,
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
        if (preferences.contains(ANDROID_QUARANTINED_SESSION_KEY)) {
            runCatching { commitPreferences(preferences.edit().remove(ANDROID_QUARANTINED_SESSION_KEY)) }
        }
        return AndroidAccountCredentialStoreRead.Available(state)
    }

    private fun readIndependentCredentialSlotState(
        allowUnavailableActiveAccountId: NextcloudAccountId? = null,
    ): AndroidAccountCredentialState? {
        val encodedRegistry = preferences.getString(ANDROID_ACCOUNT_REGISTRY_KEY, null) ?: return null
        val registry = restoreAndroidCredentialFreeRegistry(encodedRegistry).registry ?: return null
        val slots = registry.accounts.associate { account -> account.id to readCredentialSlot(account.id) }
        if (slots.values.any { slot -> slot is AndroidAccountCredentialSlotRead.Unsupported }) return null
        return if (allowUnavailableActiveAccountId == null) {
            reconstructAndroidAccountCredentialState(registry) { accountId ->
                (slots[accountId] as? AndroidAccountCredentialSlotRead.Available)?.session
            }
        } else {
            reconstructAndroidAccountCredentialStateForRemoval(registry, allowUnavailableActiveAccountId) { accountId ->
                (slots[accountId] as? AndroidAccountCredentialSlotRead.Available)?.session
            }
        }
    }

    private fun hasIndependentCredentialState(): Boolean =
        preferences.contains(ANDROID_ACCOUNT_REGISTRY_KEY) ||
            preferences.all.keys.any { key -> key.startsWith(ANDROID_ACCOUNT_CREDENTIAL_SLOT_KEY_PREFIX) }

    private fun readCredentialSlot(accountId: NextcloudAccountId): AndroidAccountCredentialSlotRead = try {
        readAndroidAccountCredentialSlot(
            accountId = accountId,
            readEncrypted = { key -> preferences.getString(key, null) },
            decrypt = sessionCipher::decrypt,
            decode = { encoded ->
                restoreAndroidAccountCredentialStore(
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
                )
            },
        )
    } catch (_: Exception) {
        recordCredentialFailure(
            code = "ACCOUNT_CREDENTIAL_STORE_READ_FAILED",
            operation = "account-credentials.restore",
        )
        AndroidAccountCredentialSlotRead.Invalid
    }

    private fun requireSupportedCredentialSlots(registry: NextcloudAccountRegistry) {
        registry.accounts.forEach { account ->
            val slot = readCredentialSlot(account.id)
            if (slot is AndroidAccountCredentialSlotRead.Unsupported) {
                unsupportedCredentialStoreMutation(slot.version)
            }
        }
    }

    private suspend fun persistState(
        state: AndroidAccountCredentialState,
        pendingCleanup: AndroidPendingAccountRemovalCleanup? = null,
    ) = withContext(Dispatchers.IO) {
        commitPreferences(
            accountRemovalCleanupJournal.prepareEdit(
                prepareCredentialSlotEdit(
                    preferences.edit()
                        .putString(ANDROID_ACCOUNT_SESSION_KEY, encryptState(state))
                        .putString(ANDROID_ACCOUNT_REGISTRY_KEY, encodeNextcloudAccountRegistry(state.registry)),
                    state,
                ),
                pendingCleanup,
            ),
        )
    }

    private suspend fun retryPendingAccountRemovalCleanup(session: NextcloudSession) {
        val pending = pendingAndroidAccountRemovalCleanupForSession(
            session, accountRemovalCleanupJournal.pending(),
        ) ?: return
        try {
            retryAndroidAccountRemovalCleanup(
                accountOwnedByRegistry = androidAccountRemovalCleanupOwnedByRegistry(
                    pending, readCredentialFreeRegistry()?.accounts,
                ),
                removeAccountOwnedWork = {
                    retryAndroidAccountOwnedStateCleanup(session, pending, retryQueuedUploadsCleanup)
                },
                clearCleanup = { accountRemovalCleanupJournal.clear(pending.accountStorageKey) },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            recordAccountRemovalCleanupFailure(failure)
            throw androidAccountRemovalCleanupRetryFailure(failure)
        }
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
        remove(ANDROID_QUARANTINED_SESSION_KEY)
        val retainedKeys = retainedAndroidAccountCredentialSlotKeys(state)
        preferences.all.keys
            .filter { key -> key.startsWith(ANDROID_ACCOUNT_CREDENTIAL_SLOT_KEY_PREFIX) && key !in retainedKeys }
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
        failure: Throwable? = null,
    ) {
        recordDiagnostic(
            SupportDiagnosticEventDraft(
                severity = SupportDiagnosticSeverity.Error,
                component = component,
                operation = operation,
                outcome = "failed",
                code = code,
                exception = failure?.toSupportDiagnosticExceptionDraft(),
            ),
        )
    }
    private fun recordAccountRemovalCleanupFailure(failure: Exception) = recordCredentialFailure(
        code = "ACCOUNT_REMOVAL_CLEANUP_FAILED",
        operation = "account.remove-cleanup",
        component = SupportDiagnosticComponent.Sync,
        failure = failure,
    )
    private fun recordAccountSelectionCacheCleanupFailure() = recordCredentialFailure(
        code = "ACCOUNT_SELECTION_CACHE_CLEANUP_FAILED",
        operation = "account-selection.cache-cleanup",
        component = SupportDiagnosticComponent.Cache,
    )
    private fun recordAccountHandoffCleanupFailure(failure: Exception) = recordCredentialFailure(
        code = "ACCOUNT_HANDOFF_CLEANUP_FAILED",
        operation = "account.handoff-cleanup",
        component = SupportDiagnosticComponent.Cache,
        failure = failure,
    )

}
