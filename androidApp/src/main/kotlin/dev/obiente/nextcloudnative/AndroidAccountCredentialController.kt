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
        load = { loadState()?.activeSession },
        accountIdOf = NextcloudDocumentIds::accountKey,
        publishAccount = { session, accountIdentity ->
            session?.let(registerSessionPrivateValues)
            publishAccountIdentity(accountIdentity)
        },
    )

    fun listAccounts(): List<NextcloudAccountRecord> = loadState()?.registry?.accounts.orEmpty()

    fun activeAccountId(): NextcloudAccountId? = loadState()?.registry?.activeAccountId

    fun loadSession(accountId: NextcloudAccountId): NextcloudSession? =
        loadState()?.sessions?.get(accountId)?.also(registerSessionPrivateValues)

    suspend fun saveSession(session: NextcloudSession) = mutationMutex.withLock {
        registerSessionPrivateValues(session)
        val current = requireValidState()
        replaceActiveState(current.upsertAndSelect(session), current.activeSession)
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
        clearSession(requireValidState())
    }

    private suspend fun clearSession(current: AndroidAccountCredentialState) {
        val activeSession = current.activeSession ?: return
        val replacement = current.remove(activeSession.accountId)
        val encodedReplacement = replacement.takeUnless { state ->
            state.registry.accounts.isEmpty() && state.sessions.isEmpty()
        }?.let(::encryptState)
        withContext(Dispatchers.IO) { AndroidExternalFileHandoffRegistry.clear() }
        val scheduler = AndroidFileSyncScheduler(appContext)
        withContext(Dispatchers.IO) {
            ANDROID_FILE_SYNC_SESSION_SCHEDULING_GUARD.clearSession(
                persist = {
                    val editor = preferences.edit().apply {
                        if (encodedReplacement == null) {
                            remove(KEY_SESSION)
                        } else {
                            putString(KEY_SESSION, encodedReplacement)
                        }
                        remove(KEY_TEST_READ_ONLY)
                    }
                    commitPreferences(editor)
                },
                cancelAll = scheduler::cancelAll,
                clearPublishedAccount = { publishAccountIdentity(null) },
            )
        }
        clearPreviewAccount(NextcloudDocumentIds.cacheAccountId(activeSession))
        notifyDocumentRootsChanged()
    }

    private suspend fun replaceActiveState(
        replacement: AndroidAccountCredentialState,
        previousSession: NextcloudSession?,
    ) {
        val session = requireNotNull(replacement.activeSession)
        val encrypted = encryptState(replacement)
        withContext(Dispatchers.IO) { AndroidExternalFileHandoffRegistry.clear() }
        val scheduler = AndroidFileSyncScheduler(appContext)
        withContext(Dispatchers.IO) {
            ANDROID_FILE_SYNC_SESSION_SCHEDULING_GUARD.replaceSession(
                replacementAccountId = NextcloudDocumentIds.accountKey(session),
                persist = {
                    commitPreferences(
                        preferences.edit()
                            .putString(KEY_SESSION, encrypted)
                            .remove(KEY_TEST_READ_ONLY),
                    )
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

    private fun requireValidState(): AndroidAccountCredentialState = requireNotNull(loadState()) {
        "The account credential store is invalid."
    }

    private fun loadState(): AndroidAccountCredentialState? {
        val encrypted = preferences.getString(KEY_SESSION, null) ?: return AndroidAccountCredentialState.Empty
        val encoded = try {
            sessionCipher.decrypt(encrypted)
        } catch (_: Exception) {
            recordCredentialFailure(
                code = "ACCOUNT_CREDENTIAL_STORE_READ_FAILED",
                operation = "account-credentials.restore",
            )
            return null
        }
        return restoreAndroidAccountCredentialState(
            encoded = encoded,
            persistMigrated = { migrated ->
                commitPreferences(
                    preferences.edit().putString(KEY_SESSION, sessionCipher.encrypt(migrated)),
                )
            },
            recordDiagnostic = recordDiagnostic,
        )
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

    private companion object {
        const val KEY_SESSION = "encrypted_session"
        const val KEY_TEST_READ_ONLY = "emulator_test_read_only"
    }
}

internal fun requireCommittedAndroidAccountCredentialEdit(editor: SharedPreferences.Editor) {
    check(editor.commit()) { "The account credential store could not be committed." }
}
