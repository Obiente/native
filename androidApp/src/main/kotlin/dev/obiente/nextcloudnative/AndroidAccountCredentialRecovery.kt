package dev.obiente.nextcloudnative

import android.content.SharedPreferences
import dev.obiente.nextcloudnative.app.NextcloudAccountId
import dev.obiente.nextcloudnative.app.NextcloudAccountRecord
import dev.obiente.nextcloudnative.app.NextcloudAccountRegistry
import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.accountRecord
import dev.obiente.nextcloudnative.app.durableMutationAccountScope
import dev.obiente.nextcloudnative.app.restoreNextcloudAccountRegistry
import kotlinx.coroutines.sync.Mutex

internal sealed interface AndroidAccountCredentialStoreRead {
    data class Available(val state: AndroidAccountCredentialState) : AndroidAccountCredentialStoreRead
    data class Invalid(val encrypted: String) : AndroidAccountCredentialStoreRead
    data object IndependentRecoveryUnavailable : AndroidAccountCredentialStoreRead
    data class Unsupported(val encrypted: String, val version: Int) : AndroidAccountCredentialStoreRead
}

internal sealed interface AndroidAccountCredentialSlotRead {
    data object Missing : AndroidAccountCredentialSlotRead
    data class Available(val session: NextcloudSession) : AndroidAccountCredentialSlotRead
    data object Invalid : AndroidAccountCredentialSlotRead
    data class Unsupported(val version: Int) : AndroidAccountCredentialSlotRead
}

internal data class AndroidAccountCredentialSelectionRecovery(
    val state: AndroidAccountCredentialState,
    val suspectEncrypted: String?,
)

internal fun recoverAndroidAccountCredentialStateForSelection(
    read: AndroidAccountCredentialStoreRead,
    recoverIndependent: () -> AndroidAccountCredentialState?,
): AndroidAccountCredentialSelectionRecovery = when (read) {
    is AndroidAccountCredentialStoreRead.Available -> AndroidAccountCredentialSelectionRecovery(read.state, null)
    is AndroidAccountCredentialStoreRead.Invalid -> AndroidAccountCredentialSelectionRecovery(
        recoverIndependent() ?: error("The independent account credential slots could not be recovered."),
        read.encrypted,
    )
    AndroidAccountCredentialStoreRead.IndependentRecoveryUnavailable -> AndroidAccountCredentialSelectionRecovery(
        recoverIndependent() ?: error("The independent account credential slots could not be recovered."),
        null,
    )
    is AndroidAccountCredentialStoreRead.Unsupported -> unsupportedCredentialStoreMutation(read.version)
}

internal fun requireValidAndroidAccountCredentialState(
    read: AndroidAccountCredentialStoreRead,
    requireSupportedSlots: (NextcloudAccountRegistry) -> Unit,
): AndroidAccountCredentialState = when (read) {
    is AndroidAccountCredentialStoreRead.Available -> read.state.also { requireSupportedSlots(it.registry) }
    is AndroidAccountCredentialStoreRead.Invalid -> error("The account credential store is invalid.")
    AndroidAccountCredentialStoreRead.IndependentRecoveryUnavailable ->
        error("The independent account credential slots could not be recovered.")
    is AndroidAccountCredentialStoreRead.Unsupported -> unsupportedCredentialStoreMutation(read.version)
}

internal data class AndroidPendingAccountRemovalCleanup(
    val accountStorageKey: String,
    val workIdentity: String,
    val previewCacheIdentity: String? = null,
    val durableMutationIdentity: String? = null,
) {
    init {
        require(ACCOUNT_STORAGE_KEY_PATTERN.matches(accountStorageKey))
        require(WORK_IDENTITY_PATTERN.matches(workIdentity))
        previewCacheIdentity?.let { identity ->
            require(ACCOUNT_STORAGE_KEY_PATTERN.matches(identity))
            require(identity.startsWith(workIdentity))
        }
        durableMutationIdentity?.let { identity ->
            require(ACCOUNT_STORAGE_KEY_PATTERN.matches(identity))
            require(previewCacheIdentity != null)
        }
    }
}

internal fun unsupportedCredentialStoreMutation(version: Int): Nothing =
    error("The account credential store version $version is unsupported.")

internal fun androidAccountCredentialSlotKey(accountId: NextcloudAccountId): String =
    "$ANDROID_ACCOUNT_CREDENTIAL_SLOT_KEY_PREFIX${accountId.storageKey}"

internal fun retainedAndroidAccountCredentialSlotKeys(
    state: AndroidAccountCredentialState,
): Set<String> = state.registry.accounts.mapTo(hashSetOf()) { account ->
    androidAccountCredentialSlotKey(account.id)
}

internal fun readAndroidAccountCredentialSlot(
    accountId: NextcloudAccountId,
    readEncrypted: (String) -> String?,
    decrypt: (String) -> String,
    decode: (String) -> RestoredAndroidAccountCredentialState,
): AndroidAccountCredentialSlotRead {
    val encrypted = readEncrypted(androidAccountCredentialSlotKey(accountId))
        ?: return AndroidAccountCredentialSlotRead.Missing
    val restored = decode(decrypt(encrypted))
    restored.unsupportedVersion?.let { version -> return AndroidAccountCredentialSlotRead.Unsupported(version) }
    val session = restored.state?.activeSession
        ?.takeIf { candidate -> candidate.accountId == accountId }
        ?: return AndroidAccountCredentialSlotRead.Invalid
    return AndroidAccountCredentialSlotRead.Available(session)
}

internal fun pendingAndroidAccountRemovalCleanup(
    session: NextcloudSession,
): AndroidPendingAccountRemovalCleanup = AndroidPendingAccountRemovalCleanup(
    accountStorageKey = session.accountId.storageKey,
    workIdentity = NextcloudDocumentIds.accountKey(session),
    previewCacheIdentity = NextcloudDocumentIds.cacheAccountId(session),
    durableMutationIdentity = durableMutationAccountScope(session),
)

internal fun encodeAndroidPendingAccountRemovalCleanup(
    cleanup: AndroidPendingAccountRemovalCleanup,
): String = listOfNotNull(
    cleanup.accountStorageKey,
    cleanup.workIdentity,
    cleanup.previewCacheIdentity,
    cleanup.durableMutationIdentity,
).joinToString(":")

internal fun decodeAndroidPendingAccountRemovalCleanup(
    encoded: String,
): AndroidPendingAccountRemovalCleanup? {
    val fields = encoded.split(':')
    if (fields.size !in 2..4) return null
    return runCatching {
        AndroidPendingAccountRemovalCleanup(
            accountStorageKey = fields[0],
            workIdentity = fields[1],
            previewCacheIdentity = fields.getOrNull(2),
            durableMutationIdentity = fields.getOrNull(3),
        )
    }.getOrNull()
}

internal data class RestoredAndroidPendingAccountRemovalCleanups(
    val cleanups: Set<AndroidPendingAccountRemovalCleanup>,
    val malformedEntryCount: Int,
)

internal fun restoreAndroidPendingAccountRemovalCleanups(
    encoded: Set<String>,
): RestoredAndroidPendingAccountRemovalCleanups {
    val cleanups = linkedSetOf<AndroidPendingAccountRemovalCleanup>()
    var malformedEntryCount = 0
    encoded.forEach { entry ->
        val cleanup = decodeAndroidPendingAccountRemovalCleanup(entry)
        if (cleanup == null) malformedEntryCount += 1 else cleanups += cleanup
    }
    return RestoredAndroidPendingAccountRemovalCleanups(cleanups, malformedEntryCount)
}

internal fun pendingAndroidAccountRemovalCleanupForSession(
    session: NextcloudSession,
    cleanups: Collection<AndroidPendingAccountRemovalCleanup>,
): AndroidPendingAccountRemovalCleanup? {
    val matching = cleanups.filter { cleanup ->
        cleanup.accountStorageKey == session.accountId.storageKey
    }
    check(matching.size <= 1) { "The pending account cleanup journal is ambiguous." }
    return matching.singleOrNull()
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
    return AndroidAccountCredentialState(registry, sessions)
}

internal fun reconstructAndroidAccountCredentialStateForRemoval(
    registry: NextcloudAccountRegistry,
    accountId: NextcloudAccountId,
    loadSession: (NextcloudAccountId) -> NextcloudSession?,
): AndroidAccountCredentialState? {
    val recoverableRegistry = if (registry.activeAccountId == accountId) {
        registry.copy(activeAccountId = null)
    } else {
        registry
    }
    return reconstructAndroidAccountCredentialState(recoverableRegistry, loadSession)
}

internal data class AndroidUnavailableAccountRemovalTarget(
    val record: NextcloudAccountRecord,
    val wasActive: Boolean,
)

internal fun resolveAndroidUnavailableAccountRemovalTarget(
    registry: NextcloudAccountRegistry?,
    accountId: NextcloudAccountId,
): AndroidUnavailableAccountRemovalTarget? {
    val available = registry ?: return null
    val record = available.accounts.firstOrNull { account -> account.id == accountId } ?: return null
    return AndroidUnavailableAccountRemovalTarget(record, available.activeAccountId == accountId)
}

internal data class AndroidActiveAccountRemovalTransition(
    val identitySession: NextcloudSession,
    val replacement: AndroidAccountCredentialState,
)

internal fun resolveAndroidActiveAccountRemovalTransition(
    current: AndroidAccountCredentialState,
    fallback: NextcloudSession? = null,
): AndroidActiveAccountRemovalTransition? {
    val session = current.activeSession ?: fallback ?: return null
    val record = current.registry.accounts.firstOrNull { account -> account.id == session.accountId } ?: return null
    check(session.accountRecord() == record) { "The fallback account identity changed." }
    return AndroidActiveAccountRemovalTransition(session, current.remove(session.accountId))
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
): Boolean = when (read) {
    is AndroidAccountCredentialStoreRead.Available -> read.state.mutationsAllowed
    is AndroidAccountCredentialStoreRead.Unsupported -> false
    is AndroidAccountCredentialStoreRead.Invalid,
    AndroidAccountCredentialStoreRead.IndependentRecoveryUnavailable,
    -> true
}

internal class AndroidAccountCredentialStoreGuard {
    private val monitor = Any()

    fun <Result> serialize(action: () -> Result): Result = synchronized(monitor, action)
}

internal fun prepareInvalidAndroidAccountCredentialRecoveryEdit(
    editor: SharedPreferences.Editor,
    replacementEncrypted: String?,
): SharedPreferences.Editor = editor.apply {
    remove(ANDROID_QUARANTINED_SESSION_KEY)
    if (replacementEncrypted == null) remove(ANDROID_ACCOUNT_SESSION_KEY)
    else putString(ANDROID_ACCOUNT_SESSION_KEY, replacementEncrypted)
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

internal const val ANDROID_ACCOUNT_SESSION_KEY = "encrypted_session"
internal const val ANDROID_ACCOUNT_REGISTRY_KEY = "account_registry_v1"
internal const val ANDROID_ACCOUNT_PREFERENCES_NAME = "nextcloud_native"
internal const val ANDROID_ACCOUNT_CREDENTIAL_SLOT_KEY_PREFIX = "account_credential_v1:"
internal const val ANDROID_QUARANTINED_SESSION_KEY = "encrypted_session_quarantine"
internal const val ANDROID_PENDING_ACCOUNT_REMOVAL_CLEANUP_KEY = "pending_account_removal_cleanup_v2"
internal val ANDROID_ACCOUNT_CREDENTIAL_STORE_GUARD = AndroidAccountCredentialStoreGuard()
internal val ANDROID_ACCOUNT_CREDENTIAL_MUTATION_MUTEX = Mutex()

private val ACCOUNT_STORAGE_KEY_PATTERN = Regex("[0-9a-f]{64}")
private val WORK_IDENTITY_PATTERN = Regex("[0-9a-f]{32}")
