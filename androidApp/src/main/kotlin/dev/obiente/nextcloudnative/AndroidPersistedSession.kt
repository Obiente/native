package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudAccountId
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
import dev.obiente.nextcloudnative.app.singleAccountRegistry
import dev.obiente.nextcloudnative.app.toNonSecretSupportDiagnosticExceptionDraft
import org.json.JSONArray
import org.json.JSONObject

internal data class AndroidAccountCredentialState(
    val registry: NextcloudAccountRegistry,
    val sessions: Map<NextcloudAccountId, NextcloudSession>,
    val mutationsAllowed: Boolean = true,
) {
    init {
        require(sessions.size <= MAX_ANDROID_ACCOUNT_CREDENTIALS)
        require(sessions.size == registry.accounts.size)
        require(sessions.all { (id, session) ->
            id == session.accountId && registry.accounts.any { account -> account == session.accountRecord() }
        })
        require(registry.activeAccountId == null || registry.activeAccountId in sessions)
    }

    val activeSession: NextcloudSession?
        get() = registry.activeAccountId?.let(sessions::get)

    fun upsertAndSelect(session: NextcloudSession): AndroidAccountCredentialState {
        requireMutationsAllowed()
        val stableSession = sessions[session.accountId]
            ?.let { retained -> session.copy(serverUrl = retained.serverUrl) }
            ?: session
        return copy(
            registry = registry.upsertAndSelect(stableSession.accountRecord()),
            sessions = sessions + (stableSession.accountId to stableSession),
        )
    }

    fun select(accountId: NextcloudAccountId): AndroidAccountCredentialState? {
        requireMutationsAllowed()
        if (accountId !in sessions) return null
        return copy(registry = requireNotNull(registry.select(accountId)))
    }

    fun remove(accountId: NextcloudAccountId): AndroidAccountCredentialState {
        requireMutationsAllowed()
        return copy(
            registry = registry.remove(accountId),
            sessions = sessions - accountId,
        )
    }

    private fun requireMutationsAllowed() {
        check(mutationsAllowed) { "The account credential store version is unsupported." }
    }

    companion object {
        val Empty = AndroidAccountCredentialState(NextcloudAccountRegistry.Empty, emptyMap())
    }
}

internal data class RestoredAndroidAccountCredentialState(
    val state: AndroidAccountCredentialState?,
    val needsPersistence: Boolean = false,
    val diagnosticCode: String? = null,
    val unsupportedVersion: Int? = null,
)

internal fun restoreAndroidAccountCredentialState(
    encoded: String,
    persistMigrated: (String) -> Unit,
    recordDiagnostic: (SupportDiagnosticEventDraft) -> Unit,
): AndroidAccountCredentialState? = restoreAndroidAccountCredentialStore(
    encoded = encoded,
    persistMigrated = persistMigrated,
    recordDiagnostic = recordDiagnostic,
).state

internal fun restoreAndroidAccountCredentialStore(
    encoded: String,
    persistMigrated: (String) -> Unit,
    recordDiagnostic: (SupportDiagnosticEventDraft) -> Unit,
): RestoredAndroidAccountCredentialState {
    val restored = decodeAndroidAccountCredentialState(encoded)
    restored.diagnosticCode?.let { code -> recordAccountCredentialDiagnostic(code, recordDiagnostic) }
    if (restored.needsPersistence && restored.state != null) {
        runCatching { persistMigrated(encodeAndroidAccountCredentialState(restored.state)) }
            .onFailure { failure ->
                recordAccountCredentialDiagnostic(
                    code = "ACCOUNT_CREDENTIAL_STORE_MIGRATION_FAILED",
                    recordDiagnostic = recordDiagnostic,
                    failure = failure,
                )
            }
    }
    return restored
}

internal fun decodeAndroidAccountCredentialState(encoded: String): RestoredAndroidAccountCredentialState {
    if (encoded.encodeToByteArray().size > MAX_ANDROID_ACCOUNT_CREDENTIAL_STORE_BYTES) {
        return malformedAndroidAccountCredentialState()
    }
    return try {
        val json = JSONObject(encoded)
        if (!json.has(KEY_VERSION)) {
            restoreLegacyAndroidAccountCredentialState(json)
        } else {
            val version = json.getInt(KEY_VERSION)
            if (version > ANDROID_ACCOUNT_CREDENTIAL_STORE_VERSION) {
                return RestoredAndroidAccountCredentialState(
                    state = null,
                    diagnosticCode = "ACCOUNT_CREDENTIAL_STORE_VERSION_UNSUPPORTED",
                    unsupportedVersion = version,
                )
            }
            require(version == ANDROID_ACCOUNT_CREDENTIAL_STORE_VERSION)
            val registry = requireNotNull(decodeNextcloudAccountRegistry(json.getString(KEY_ACCOUNT_REGISTRY)))
            val encodedSessions = json.getJSONArray(KEY_CREDENTIALS)
            require(encodedSessions.length() <= MAX_ANDROID_ACCOUNT_CREDENTIALS)
            val sessions = linkedMapOf<NextcloudAccountId, NextcloudSession>()
            repeat(encodedSessions.length()) { index ->
                val encodedSession = encodedSessions.getJSONObject(index)
                val session = NextcloudSession(
                    serverUrl = encodedSession.getString(KEY_SERVER_URL),
                    loginName = encodedSession.getString(KEY_LOGIN_NAME),
                    appPassword = encodedSession.getString(KEY_APP_PASSWORD),
                )
                val claimedAccountId = encodedSession.getString(KEY_ACCOUNT_ID)
                if (claimedAccountId != session.accountId.storageKey) throw AndroidCredentialMismatchException()
                if (sessions.put(session.accountId, session) != null) throw AndroidCredentialMismatchException()
            }
            if (sessions.size != registry.accounts.size || sessions.any { (_, session) ->
                    registry.accounts.none { account -> account == session.accountRecord() }
                }
            ) {
                throw AndroidCredentialMismatchException()
            }
            if (registry.activeAccountId != null && registry.activeAccountId !in sessions) {
                throw AndroidCredentialMismatchException()
            }
            RestoredAndroidAccountCredentialState(AndroidAccountCredentialState(registry, sessions))
        }
    } catch (_: AndroidCredentialMismatchException) {
        RestoredAndroidAccountCredentialState(
            state = null,
            diagnosticCode = "ACCOUNT_CREDENTIAL_SLOT_MISMATCH",
        )
    } catch (_: Exception) {
        malformedAndroidAccountCredentialState()
    }
}

internal fun encodeAndroidAccountCredentialState(state: AndroidAccountCredentialState): String = JSONObject()
    .also { check(state.mutationsAllowed) { "The account credential store version is unsupported." } }
    .put(KEY_VERSION, ANDROID_ACCOUNT_CREDENTIAL_STORE_VERSION)
    .put(KEY_ACCOUNT_REGISTRY, encodeNextcloudAccountRegistry(state.registry))
    .put(
        KEY_CREDENTIALS,
        JSONArray().also { credentials ->
            state.sessions.values.sortedBy { session -> session.accountId.storageKey }.forEach { session ->
                credentials.put(
                    JSONObject()
                        .put(KEY_ACCOUNT_ID, session.accountId.storageKey)
                        .put(KEY_SERVER_URL, session.serverUrl)
                        .put(KEY_LOGIN_NAME, session.loginName)
                        .put(KEY_APP_PASSWORD, session.appPassword),
                )
            }
        },
    )
    .toString()
    .also { encoded ->
        require(encoded.encodeToByteArray().size <= MAX_ANDROID_ACCOUNT_CREDENTIAL_STORE_BYTES)
    }

internal fun restoreAndroidPersistedSession(
    encoded: String,
    persistMigrated: (String) -> Unit,
    recordDiagnostic: (SupportDiagnosticEventDraft) -> Unit,
): NextcloudSession = requireNotNull(
    restoreAndroidAccountCredentialState(encoded, persistMigrated, recordDiagnostic)?.activeSession,
) { "The active account credential is unavailable." }

internal fun encodeAndroidPersistedSession(session: NextcloudSession): String =
    encodeAndroidAccountCredentialState(AndroidAccountCredentialState.Empty.upsertAndSelect(session))

internal fun decodeAndroidCredentialFreeRegistry(encoded: String): NextcloudAccountRegistry? =
    decodeNextcloudAccountRegistry(encoded)

internal data class RestoredAndroidCredentialFreeRegistry(
    val registry: NextcloudAccountRegistry?,
    val diagnosticCode: String? = null,
    val credentialRecoveryRequired: Boolean = false,
)

internal fun restoreAndroidCredentialFreeRegistry(
    encoded: String,
): RestoredAndroidCredentialFreeRegistry {
    val restored = restoreNextcloudAccountRegistry(encoded, legacySession = null)
    val recoveryReason = restored.recoveryReason
    return when (recoveryReason) {
        null -> RestoredAndroidCredentialFreeRegistry(restored.registry)
        NextcloudAccountRegistryRecoveryReason.UnsupportedRegistryVersion ->
            RestoredAndroidCredentialFreeRegistry(null, recoveryReason.diagnosticCode)
        else -> RestoredAndroidCredentialFreeRegistry(
            registry = null,
            diagnosticCode = recoveryReason.diagnosticCode,
            credentialRecoveryRequired = true,
        )
    }
}

internal fun recoverAndroidCredentialFreeRegistryForCredentialLoad(
    restored: RestoredAndroidCredentialFreeRegistry?,
    recover: () -> NextcloudAccountRegistry?,
): NextcloudAccountRegistry? = when {
    restored?.registry != null -> restored.registry
    restored == null || restored.credentialRecoveryRequired -> recover()
    else -> null
}

private fun restoreLegacyAndroidAccountCredentialState(
    json: JSONObject,
): RestoredAndroidAccountCredentialState {
    val session = NextcloudSession(
        serverUrl = json.getString(KEY_SERVER_URL),
        loginName = json.getString(KEY_LOGIN_NAME),
        appPassword = json.getString(KEY_APP_PASSWORD),
    )
    val encodedRegistry = when (val registry = json.opt(KEY_ACCOUNT_REGISTRY)) {
        null -> null
        is String -> registry
        else -> ""
    }
    val restoredRegistry = restoreNextcloudAccountRegistry(encodedRegistry, session)
    val credentialRegistry = singleAccountRegistry(session)
    return RestoredAndroidAccountCredentialState(
        state = AndroidAccountCredentialState(
            registry = credentialRegistry,
            sessions = mapOf(session.accountId to session),
            mutationsAllowed = restoredRegistry.recoveryReason !=
                NextcloudAccountRegistryRecoveryReason.UnsupportedRegistryVersion,
        ),
        needsPersistence = restoredRegistry.recoveryReason !=
            NextcloudAccountRegistryRecoveryReason.UnsupportedRegistryVersion,
        diagnosticCode = restoredRegistry.recoveryReason?.diagnosticCode ?: if (
            restoredRegistry.registry != credentialRegistry
        ) {
            "ACCOUNT_CREDENTIAL_SLOT_MISMATCH"
        } else {
            null
        },
    )
}

private fun malformedAndroidAccountCredentialState() = RestoredAndroidAccountCredentialState(
    state = null,
    diagnosticCode = "ACCOUNT_CREDENTIAL_STORE_MALFORMED",
)

private fun recordAccountCredentialDiagnostic(
    code: String,
    recordDiagnostic: (SupportDiagnosticEventDraft) -> Unit,
    failure: Throwable? = null,
) {
    recordDiagnostic(
        SupportDiagnosticEventDraft(
            severity = SupportDiagnosticSeverity.Warning,
            component = SupportDiagnosticComponent.Authentication,
            operation = "account-credentials.restore",
            outcome = "recovered",
            code = code,
            exception = failure?.toNonSecretSupportDiagnosticExceptionDraft(),
        ),
    )
}

private class AndroidCredentialMismatchException : IllegalArgumentException()

private const val ANDROID_ACCOUNT_CREDENTIAL_STORE_VERSION = 2
private const val MAX_ANDROID_ACCOUNT_CREDENTIALS = 64
private const val MAX_ANDROID_ACCOUNT_CREDENTIAL_STORE_BYTES = 512 * 1024
private const val KEY_VERSION = "version"
private const val KEY_ACCOUNT_REGISTRY = "account_registry_v1"
private const val KEY_CREDENTIALS = "credentials"
private const val KEY_ACCOUNT_ID = "accountId"
private const val KEY_SERVER_URL = "serverUrl"
private const val KEY_LOGIN_NAME = "loginName"
private const val KEY_APP_PASSWORD = "appPassword"
