package dev.obiente.nextcloudnative

import android.content.Context
import android.content.SharedPreferences
import dev.obiente.nextcloudnative.app.NextcloudAccountId
import dev.obiente.nextcloudnative.app.NextcloudAccountRecord
import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.SupportDiagnosticComponent
import dev.obiente.nextcloudnative.app.SupportDiagnosticEventDraft
import dev.obiente.nextcloudnative.app.SupportDiagnosticSeverity
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
) {
    private val appContext = context.applicationContext
    private val mutationMutex = Mutex()

    fun loadSession(): NextcloudSession? = ANDROID_FILE_SYNC_SESSION_SCHEDULING_GUARD.restorePersistedSession(
        load = { (readStore() as? AndroidAccountCredentialStoreRead.Available)?.state?.activeSession },
        accountIdOf = NextcloudDocumentIds::accountKey,
        publishAccount = { session, accountIdentity ->
            session?.let(registerSessionPrivateValues)
            publishAccountIdentity(accountIdentity)
        },
    )

    fun listAccounts(): List<NextcloudAccountRecord> =
        (readStore() as? AndroidAccountCredentialStoreRead.Available)?.state?.registry?.accounts.orEmpty()

    fun activeAccountId(): NextcloudAccountId? =
        (readStore() as? AndroidAccountCredentialStoreRead.Available)?.state?.registry?.activeAccountId

    fun loadSession(accountId: NextcloudAccountId): NextcloudSession? =
        (readStore() as? AndroidAccountCredentialStoreRead.Available)
            ?.state
            ?.sessions
            ?.get(accountId)
            ?.also(registerSessionPrivateValues)

    suspend fun saveSession(session: NextcloudSession) = mutationMutex.withLock {
        registerSessionPrivateValues(session)
        when (val read = readStore()) {
            is AndroidAccountCredentialStoreRead.Available ->
                replaceActiveState(read.state.upsertAndSelect(session), read.state.activeSession)
            is AndroidAccountCredentialStoreRead.Invalid ->
                replaceActiveState(
                    replacement = AndroidAccountCredentialState.Empty.upsertAndSelect(session),
                    previousSession = null,
                    suspectEncrypted = read.encrypted,
                )
        }
    }

    suspend fun selectAccount(accountId: NextcloudAccountId): NextcloudSession? = mutationMutex.withLock {
        val current = requireValidState()
        val selected = current.select(accountId) ?: return@withLock null
        val session = requireNotNull(selected.activeSession)
        registerSessionPrivateValues(session)
        replaceActiveState(selected, current.activeSession)
        session
    }

    suspend fun removeAccount(accountId: NextcloudAccountId): Boolean = mutationMutex.withLock {
        val current = requireValidState()
        if (accountId !in current.sessions) return@withLock false
        if (current.registry.activeAccountId == accountId) {
            clearSession(current)
        } else {
            persistState(current.remove(accountId))
        }
        true
    }

    suspend fun clearSession() = mutationMutex.withLock {
        when (val read = readStore()) {
            is AndroidAccountCredentialStoreRead.Available -> clearSession(read.state)
            is AndroidAccountCredentialStoreRead.Invalid -> clearInvalidStore(read.encrypted)
        }
    }

    private suspend fun clearSession(current: AndroidAccountCredentialState) {
        val activeSession = current.activeSession ?: return
        val replacement = current.remove(activeSession.accountId)
        val encodedReplacement = replacement.takeUnless { state ->
            state.registry.accounts.isEmpty() && state.sessions.isEmpty()
        }?.let(::encryptState)
        clearPersistedSession(encodedReplacement)
        clearPreviewAccount(NextcloudDocumentIds.cacheAccountId(activeSession))
        notifyDocumentRootsChanged()
    }

    private suspend fun clearInvalidStore(suspectEncrypted: String) {
        clearPersistedSession(encodedReplacement = null, suspectEncrypted = suspectEncrypted)
        notifyDocumentRootsChanged()
    }

    private suspend fun clearPersistedSession(
        encodedReplacement: String?,
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
                            remove(KEY_TEST_READ_ONLY)
                        }
                    } else {
                        prepareInvalidAndroidAccountCredentialRecoveryEdit(
                            editor = preferences.edit(),
                            suspectEncrypted = suspectEncrypted,
                            replacementEncrypted = encodedReplacement,
                            hasExistingQuarantine = preferences.contains(KEY_QUARANTINED_SESSION),
                        )
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
                            .remove(KEY_TEST_READ_ONLY)
                    } else {
                        prepareInvalidAndroidAccountCredentialRecoveryEdit(
                            editor = preferences.edit(),
                            suspectEncrypted = suspectEncrypted,
                            replacementEncrypted = encrypted,
                            hasExistingQuarantine = preferences.contains(KEY_QUARANTINED_SESSION),
                        )
                    }
                    commitPreferences(editor)
                },
                cancelAll = scheduler::cancelAll,
                publishAccount = publishAccountIdentity,
                restoreSchedules = scheduler::restorePersistedPairSchedules,
            )
        }
        if (previousSession != null && previousSession.accountId != session.accountId) {
            clearPreviewAccount(NextcloudDocumentIds.cacheAccountId(previousSession))
        }
        notifyDocumentRootsChanged()
    }

    private fun requireValidState(): AndroidAccountCredentialState = when (val read = readStore()) {
        is AndroidAccountCredentialStoreRead.Available -> read.state
        is AndroidAccountCredentialStoreRead.Invalid -> error("The account credential store is invalid.")
    }

    private fun readStore(): AndroidAccountCredentialStoreRead {
        val encrypted = preferences.getString(KEY_SESSION, null)
            ?: return AndroidAccountCredentialStoreRead.Available(AndroidAccountCredentialState.Empty)
        val encoded = try {
            sessionCipher.decrypt(encrypted)
        } catch (_: Exception) {
            recordCredentialFailure(
                code = "ACCOUNT_CREDENTIAL_STORE_READ_FAILED",
                operation = "account-credentials.restore",
            )
            return AndroidAccountCredentialStoreRead.Invalid(encrypted)
        }
        val state = restoreAndroidAccountCredentialState(
            encoded = encoded,
            persistMigrated = { migrated ->
                commitPreferences(
                    preferences.edit().putString(KEY_SESSION, sessionCipher.encrypt(migrated)),
                )
            },
            recordDiagnostic = recordDiagnostic,
        )
        return state?.let { AndroidAccountCredentialStoreRead.Available(it) }
            ?: AndroidAccountCredentialStoreRead.Invalid(encrypted)
    }

    private suspend fun persistState(state: AndroidAccountCredentialState) = withContext(Dispatchers.IO) {
        commitPreferences(preferences.edit().putString(KEY_SESSION, encryptState(state)))
    }

    private fun commitPreferences(editor: SharedPreferences.Editor) {
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

    private fun recordCredentialFailure(code: String, operation: String) {
        recordDiagnostic(
            SupportDiagnosticEventDraft(
                severity = SupportDiagnosticSeverity.Error,
                component = SupportDiagnosticComponent.Authentication,
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
}

internal fun prepareInvalidAndroidAccountCredentialRecoveryEdit(
    editor: SharedPreferences.Editor,
    suspectEncrypted: String,
    replacementEncrypted: String?,
    hasExistingQuarantine: Boolean,
): SharedPreferences.Editor = editor.apply {
    if (!hasExistingQuarantine) putString(KEY_QUARANTINED_SESSION, suspectEncrypted)
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
private const val KEY_QUARANTINED_SESSION = "encrypted_session_quarantine"
