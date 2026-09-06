package dev.obiente.nextcloudnative.app

import java.util.prefs.Preferences
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal const val DESKTOP_UNKNOWN_CLEANUP_STATE_MESSAGE =
    "This account has cleanup state written by a newer app version."

internal fun unknownCleanupStateRejection() =
    VirtualFileStorageActionResult.Rejected(DESKTOP_UNKNOWN_CLEANUP_STATE_MESSAGE)

internal fun requireDesktopAccountActivationAllowed(blockedByUnknownCleanup: Boolean) {
    check(!blockedByUnknownCleanup) { DESKTOP_UNKNOWN_CLEANUP_STATE_MESSAGE }
}

internal fun DesktopAccountSyncPairCleanupJournal.requireAccountActivationAllowed(record: NextcloudAccountRecord) =
    requireDesktopAccountActivationAllowed(
        blocksAccountActivation(desktopFileCacheAccountId(record), record.id.storageKey),
    )

internal fun loadDesktopSessionAfterCleanupGate(
    record: NextcloudAccountRecord?,
    cleanupJournal: DesktopAccountSyncPairCleanupJournal,
    load: () -> NextcloudSession?,
    publish: (NextcloudSession) -> Unit,
): NextcloudSession? {
    requireDesktopAccountActivationAllowed(cleanupJournal.blocksAllAccountActivation())
    record?.let(cleanupJournal::requireAccountActivationAllowed)
    return load()?.also(publish)
}

internal enum class DesktopAccountSyncPairCleanupPhase {
    Prepared,
    Committed,
    Unknown,
}

internal enum class DesktopAccountOwnership {
    Present,
    Absent,
    Unknown,
}

internal data class DesktopAccountSyncPairCleanup(
    val accountId: String,
    val phase: DesktopAccountSyncPairCleanupPhase,
    val durableMutationAccountScope: String? = null,
    val accountStorageKey: String? = null,
    val legacyAccountScopeDigest: String? = null,
)

internal fun DesktopAccountSyncPairCleanup.matchesAccountActivation(
    accountId: String,
    accountStorageKey: String,
): Boolean = this.accountId == accountId || this.accountStorageKey == accountStorageKey

internal class DesktopAccountSyncPairCleanupJournal(
    private val preferences: Preferences,
    private val recordMalformed: () -> Unit = {},
) {
    private val malformedReported = AtomicBoolean()

    fun prepare(
        accountId: String,
        durableMutationAccountScope: String? = null,
        accountStorageKey: String? = null,
    ) = prepare(accountId, durableMutationAccountScope, accountStorageKey, legacyAccountScopeDigest = null)

    fun prepare(
        accountId: String,
        durableMutationAccountScope: String?,
        accountStorageKey: String?,
        legacyAccountScopeDigest: String?,
    ) = persist(
        accountId,
        DesktopAccountSyncPairCleanupPhase.Prepared,
        durableMutationAccountScope,
        accountStorageKey,
        legacyAccountScopeDigest,
    )

    fun commit(accountId: String) {
        val current = decode(accountId, preferences.get(cleanupKey(accountId), null))
        check(current.phase != DesktopAccountSyncPairCleanupPhase.Unknown) {
            "The desktop account sync cleanup journal phase is unsupported."
        }
        persist(
            accountId,
            DesktopAccountSyncPairCleanupPhase.Committed,
            current.durableMutationAccountScope,
            current.accountStorageKey,
            current.legacyAccountScopeDigest,
        )
    }

    fun clear(accountId: String) {
        validateDesktopSyncPairCleanupAccountId(accountId)
        preferences.remove(cleanupKey(accountId))
        preferences.flush()
    }

    fun blocksAccountActivation(accountId: String, accountStorageKey: String? = null): Boolean {
        validateDesktopSyncPairCleanupAccountId(accountId)
        require(accountStorageKey == null || accountStorageKey.matches(ACCOUNT_STORAGE_KEY_PATTERN)) {
            "The desktop account storage cleanup identity is invalid."
        }
        val blocked = if (accountStorageKey == null) {
            val encoded = preferences.get(cleanupKey(accountId), null)
            encoded != null && decode(accountId, encoded).phase == DesktopAccountSyncPairCleanupPhase.Unknown
        } else {
            pending().any { cleanup ->
                cleanup.phase == DesktopAccountSyncPairCleanupPhase.Unknown &&
                    (cleanup.accountStorageKey == null || cleanup.matchesAccountActivation(accountId, accountStorageKey))
            }
        }
        if (blocked) recordMalformedOnce()
        return blocked
    }

    fun blocksAllAccountActivation(): Boolean {
        val blocked = preferences.keys().asSequence()
            .filter { key -> key.startsWith(KEY_PREFIX) }
            .any { key ->
                val accountId = key.removePrefix(KEY_PREFIX)
                val cleanup = runCatching {
                    validateDesktopSyncPairCleanupAccountId(accountId)
                    decode(accountId, preferences.get(key, null))
                }.getOrNull()
                cleanup == null ||
                    cleanup.phase == DesktopAccountSyncPairCleanupPhase.Unknown && cleanup.accountStorageKey == null
            }
        if (blocked) recordMalformedOnce()
        return blocked
    }

    fun pending(): List<DesktopAccountSyncPairCleanup> {
        var malformedEntryFound = false
        val cleanups = preferences.keys()
            .asSequence()
            .filter { key -> key.startsWith(KEY_PREFIX) }
            .mapNotNull { key ->
                val accountId = key.removePrefix(KEY_PREFIX)
                val cleanup = runCatching {
                    validateDesktopSyncPairCleanupAccountId(accountId)
                    decode(accountId, preferences.get(key, null)).also { cleanup ->
                        if (cleanup.phase == DesktopAccountSyncPairCleanupPhase.Unknown) malformedEntryFound = true
                    }
                }.getOrNull()
                if (cleanup == null) malformedEntryFound = true
                cleanup
            }
            .toList()
        if (malformedEntryFound) recordMalformedOnce()
        check(cleanups.size <= MAX_LOCAL_ACCOUNTS) {
            "The desktop account sync cleanup journal is too large."
        }
        return cleanups
    }

    fun pendingForAccountActivation(accountId: String, accountStorageKey: String): List<DesktopAccountSyncPairCleanup> {
        validateDesktopSyncPairCleanupAccountId(accountId)
        require(accountStorageKey.matches(ACCOUNT_STORAGE_KEY_PATTERN)) {
            "The desktop account storage cleanup identity is invalid."
        }
        return pending().filter { cleanup -> cleanup.matchesAccountActivation(accountId, accountStorageKey) }
    }

    private fun persist(
        accountId: String,
        phase: DesktopAccountSyncPairCleanupPhase,
        durableMutationAccountScope: String?,
        accountStorageKey: String?,
        legacyAccountScopeDigest: String?,
    ) {
        validateDesktopSyncPairCleanupAccountId(accountId)
        require(
            durableMutationAccountScope == null || durableMutationAccountScope.isCanonicalGroupwareMutationAccountScope(),
        ) { "The desktop durable mutation cleanup identity is invalid." }
        require(accountStorageKey == null || accountStorageKey.matches(ACCOUNT_STORAGE_KEY_PATTERN)) {
            "The desktop account storage cleanup identity is invalid."
        }
        require(legacyAccountScopeDigest == null || legacyAccountScopeDigest.matches(ACCOUNT_STORAGE_KEY_PATTERN)) {
            "The desktop legacy workspace cleanup identity is invalid."
        }
        val key = cleanupKey(accountId)
        val current = preferences.get(key, null)?.let { decode(accountId, it) }
        check(current == null || current.phase != DesktopAccountSyncPairCleanupPhase.Unknown) {
            "The desktop account sync cleanup journal phase is unsupported."
        }
        val pending = pending()
        check(pending.any { cleanup -> cleanup.accountId == accountId } || pending.size < MAX_LOCAL_ACCOUNTS) {
            "The desktop account sync cleanup journal is too large."
        }
        preferences.put(
            key,
            encode(phase, durableMutationAccountScope, accountStorageKey, legacyAccountScopeDigest),
        )
        preferences.flush()
    }

    private fun decode(accountId: String, encoded: String?): DesktopAccountSyncPairCleanup {
        val legacyPhase = when (encoded) {
            PREPARED -> DesktopAccountSyncPairCleanupPhase.Prepared
            COMMITTED -> DesktopAccountSyncPairCleanupPhase.Committed
            else -> null
        }
        if (legacyPhase != null) return DesktopAccountSyncPairCleanup(accountId, legacyPhase)
        val fields = encoded?.split(VALUE_SEPARATOR).orEmpty()
        val phase = when (fields.getOrNull(1)) {
            PREPARED -> DesktopAccountSyncPairCleanupPhase.Prepared
            COMMITTED -> DesktopAccountSyncPairCleanupPhase.Committed
            else -> DesktopAccountSyncPairCleanupPhase.Unknown
        }
        val scope = fields.getOrNull(2)?.takeIf(String::isCanonicalGroupwareMutationAccountScope)
        val accountStorageKey = fields.getOrNull(3)?.takeIf { it.matches(ACCOUNT_STORAGE_KEY_PATTERN) }
        val legacyAccountScopeDigest = fields.getOrNull(4)?.takeIf { it.matches(ACCOUNT_STORAGE_KEY_PATTERN) }
        return if (fields.size == 3 && fields[0] == VALUE_VERSION && scope != null) {
            DesktopAccountSyncPairCleanup(accountId, phase, scope)
        } else if (
            fields.size == 4 && fields[0] == VALUE_VERSION_WITH_ACCOUNT_STORAGE &&
            scope != null && accountStorageKey != null
        ) {
            DesktopAccountSyncPairCleanup(accountId, phase, scope, accountStorageKey)
        } else if (
            fields.size == 5 && fields[0] == VALUE_VERSION_WITH_LEGACY_ACCOUNT_SCOPE &&
            scope != null && accountStorageKey != null && legacyAccountScopeDigest != null
        ) {
            DesktopAccountSyncPairCleanup(accountId, phase, scope, accountStorageKey, legacyAccountScopeDigest)
        } else if (
            fields.size > 5 && fields[0] == VALUE_VERSION_WITH_LEGACY_ACCOUNT_SCOPE &&
            scope != null && accountStorageKey != null && legacyAccountScopeDigest != null
        ) {
            DesktopAccountSyncPairCleanup(
                accountId,
                DesktopAccountSyncPairCleanupPhase.Unknown,
                scope,
                accountStorageKey,
                legacyAccountScopeDigest,
            )
        } else {
            DesktopAccountSyncPairCleanup(accountId, DesktopAccountSyncPairCleanupPhase.Unknown)
        }
    }

    private fun encode(
        phase: DesktopAccountSyncPairCleanupPhase,
        scope: String?,
        accountStorageKey: String?,
        legacyAccountScopeDigest: String?,
    ): String {
        val encodedPhase = if (phase == DesktopAccountSyncPairCleanupPhase.Prepared) PREPARED else COMMITTED
        if (legacyAccountScopeDigest != null) {
            requireNotNull(scope)
            requireNotNull(accountStorageKey)
            return listOf(
                VALUE_VERSION_WITH_LEGACY_ACCOUNT_SCOPE,
                encodedPhase,
                scope,
                accountStorageKey,
                legacyAccountScopeDigest,
            ).joinToString(VALUE_SEPARATOR)
        }
        if (accountStorageKey != null) {
            requireNotNull(scope)
            return listOf(VALUE_VERSION_WITH_ACCOUNT_STORAGE, encodedPhase, scope, accountStorageKey)
                .joinToString(VALUE_SEPARATOR)
        }
        return scope?.let { "$VALUE_VERSION$VALUE_SEPARATOR$encodedPhase$VALUE_SEPARATOR$it" } ?: encodedPhase
    }

    private fun recordMalformedOnce() {
        if (malformedReported.compareAndSet(false, true)) runCatching(recordMalformed)
    }

    private fun cleanupKey(accountId: String): String = "$KEY_PREFIX$accountId".also { key ->
        check(key.length <= Preferences.MAX_KEY_LENGTH)
    }

    private companion object {
        const val KEY_PREFIX = "fsac."
        const val PREPARED = "prepared"
        const val COMMITTED = "committed"
        const val VALUE_VERSION = "v2"
        const val VALUE_VERSION_WITH_ACCOUNT_STORAGE = "v3"
        const val VALUE_VERSION_WITH_LEGACY_ACCOUNT_SCOPE = "v4"
        const val VALUE_SEPARATOR = "|"
        val ACCOUNT_STORAGE_KEY_PATTERN = Regex("[0-9a-f]{64}")
    }
}

private fun validateDesktopSyncPairCleanupAccountId(accountId: String) {
    require(accountId.length == 64 && accountId.all { character ->
        character in '0'..'9' || character in 'a'..'f'
    }) { "The desktop account sync cleanup identity is invalid." }
}

internal fun requireDesktopAccountRemovalReady(accountId: String, linuxDesktop: Boolean) {
    if (linuxDesktop) {
        requireDesktopAccountRemovalWritebacksResolved(
            defaultDesktopLinuxWritebackStore(accountId).pendingWritebacks().size,
        )
    }
}

internal fun loadDesktopRemoteRevocationSession(
    activeAccountId: NextcloudAccountId?,
    expectedSession: NextcloudSession?,
    loadSession: (NextcloudAccountId) -> NextcloudSession?,
): NextcloudSession? {
    if (expectedSession == null) return null
    val activeSession = activeAccountId?.let(loadSession)
    check(activeSession == expectedSession) {
        "The account changed before its remote session could be revoked."
    }
    return activeSession
}

internal fun removeDesktopAccountCredential(
    preferences: Preferences,
    providerAccountId: String?,
    credentialStillExists: () -> Boolean,
    commitStatusObserved: (Boolean?) -> Unit = {},
    finishCommittedRemoval: () -> Unit = {},
    removeCredential: () -> Boolean,
): Boolean {
    val providerKey = providerAccountId?.let(::virtualFileProviderPreferenceKey)
    val providerWasEnabled = providerKey?.let { key -> preferences.getBoolean(key, false) } == true
    return removeDesktopCredentialWithoutProviderReactivation(
        providerWasEnabled = providerWasEnabled,
        clearProviderPreference = {
            providerKey?.let(preferences::remove)
            preferences.flush()
        },
        restoreProviderPreference = { enabled ->
            providerKey?.let { key ->
                if (enabled) preferences.putBoolean(key, true) else preferences.remove(key)
            }
            preferences.flush()
        },
        removalCommitted = { !credentialStillExists() },
        commitStatusObserved = commitStatusObserved,
        finishCommittedRemoval = finishCommittedRemoval,
        removeCredential = removeCredential,
    )
}

internal fun setDesktopVirtualFileProviderPreference(
    preferences: Preferences,
    accountId: String,
    enabled: Boolean,
) {
    val key = virtualFileProviderPreferenceKey(accountId)
    if (enabled) preferences.putBoolean(key, true) else preferences.remove(key)
    preferences.flush()
}

internal suspend fun removeDesktopAccountBeforeSyncPairCleanup(
    accountId: String,
    durableMutationAccountScope: String? = null,
    accountStorageKey: String? = null,
    legacyAccountScopeDigest: String? = null,
    prepareCleanup: suspend (String, String?, String?, String?) -> Unit,
    commitCleanup: suspend (String) -> Unit,
    clearCleanup: suspend (String) -> Unit,
    accountOwnership: (String) -> DesktopAccountOwnership,
    removeCredential: suspend () -> Boolean,
    removeSyncPairs: suspend (DesktopAccountSyncPairCleanup) -> Unit,
    retireCommittedAccount: () -> Unit = {},
    recordCleanupFailure: suspend (Exception) -> Unit,
): Boolean {
    prepareCleanup(accountId, durableMutationAccountScope, accountStorageKey, legacyAccountScopeDigest)
    val removed = try {
        removeCredential()
    } catch (failure: Throwable) {
        val ownership = runCatching { accountOwnership(accountId) }
        if (ownership.getOrNull() == DesktopAccountOwnership.Absent) {
            runCatching(retireCommittedAccount).exceptionOrNull()?.let(failure::addSuppressed)
        }
        runCatching {
            when (ownership.getOrThrow()) {
                DesktopAccountOwnership.Present -> clearCleanup(accountId)
                DesktopAccountOwnership.Absent -> commitCleanup(accountId)
                DesktopAccountOwnership.Unknown -> Unit
            }
        }.exceptionOrNull()?.let(failure::addSuppressed)
        throw failure
    }
    if (!removed) {
        clearCleanup(accountId)
        return false
    }
    retireCommittedAccount()
    try {
        commitCleanup(accountId)
        removeSyncPairs(
            DesktopAccountSyncPairCleanup(
                accountId,
                DesktopAccountSyncPairCleanupPhase.Committed,
                durableMutationAccountScope,
                accountStorageKey,
                legacyAccountScopeDigest,
            ),
        )
        clearCleanup(accountId)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        runCatching { recordCleanupFailure(failure) }
    }
    return true
}

internal suspend fun clearDesktopActiveAccountBeforeSyncPairCleanup(
    accountId: String?,
    durableMutationAccountScope: String? = null,
    accountStorageKey: String? = null,
    legacyAccountScopeDigest: String? = null,
    cleanupJournal: DesktopAccountSyncPairCleanupJournal,
    accountOwnership: (String) -> DesktopAccountOwnership,
    commitRemoval: suspend () -> Unit,
    removeSyncPairs: suspend (DesktopAccountSyncPairCleanup) -> Unit,
    recordDiagnostic: (SupportDiagnosticEventDraft) -> Unit,
    retireCommittedAccount: () -> Unit = {},
) {
    if (accountId == null) {
        commitRemoval()
        return
    }
    removeDesktopAccountBeforeSyncPairCleanup(
        accountId = accountId,
        durableMutationAccountScope = durableMutationAccountScope,
        accountStorageKey = accountStorageKey,
        legacyAccountScopeDigest = legacyAccountScopeDigest,
        prepareCleanup = cleanupJournal::prepare,
        commitCleanup = cleanupJournal::commit,
        clearCleanup = cleanupJournal::clear,
        accountOwnership = accountOwnership,
        removeCredential = {
            commitRemoval()
            true
        },
        removeSyncPairs = removeSyncPairs,
        retireCommittedAccount = retireCommittedAccount,
        recordCleanupFailure = { failure ->
            recordDiagnostic(desktopAccountSyncPairCleanupFailureDiagnostic(accountId, failure))
        },
    )
}

internal suspend fun commitDesktopAccountRemovalBeforeVirtualFileTeardown(
    commitRemoval: suspend () -> Unit,
    teardownVirtualFiles: () -> Unit,
) {
    commitRemoval()
    teardownVirtualFiles()
}

internal suspend fun <Session> completeDesktopSignOutAfterRemoteRevocation(
    session: Session?,
    revokeRemoteSession: suspend (Session) -> Unit,
    completeLocalRemoval: suspend () -> Unit,
) {
    if (session == null) {
        completeLocalRemoval()
        return
    }
    var revocationFailure: Throwable? = null
    try {
        revokeRemoteSession(session)
    } catch (failure: Exception) {
        revocationFailure = failure
    }
    try {
        withContext(NonCancellable) { completeLocalRemoval() }
    } catch (failure: Throwable) {
        revocationFailure?.let(failure::addSuppressed)
        throw failure
    }
    revocationFailure?.let { throw it }
    currentCoroutineContext().ensureActive()
}

internal fun finishCommittedDesktopAccountRemoval(
    markRemovalCommitted: () -> Unit,
    teardownVirtualFiles: () -> Unit,
    clearDiagnosticIdentity: () -> Unit,
    clearIntakeIdentity: () -> Unit,
) {
    markRemovalCommitted()
    var firstFailure: Throwable? = null
    listOf(teardownVirtualFiles, clearDiagnosticIdentity, clearIntakeIdentity).forEach { action ->
        runCatching(action).onFailure { failure ->
            if (firstFailure == null) firstFailure = failure else firstFailure.addSuppressed(failure)
        }
    }
    firstFailure?.let { throw it }
}

internal suspend fun retryDesktopAccountSyncPairCleanup(
    cleanup: DesktopAccountSyncPairCleanup,
    accountOwnership: (String) -> DesktopAccountOwnership,
    removeSyncPairs: suspend (DesktopAccountSyncPairCleanup) -> Unit,
    clearCleanup: suspend (String) -> Unit,
    reactivatePresentAccount: (DesktopAccountSyncPairCleanup) -> Unit = {},
) {
    when (cleanup.phase) {
        DesktopAccountSyncPairCleanupPhase.Unknown -> return
        DesktopAccountSyncPairCleanupPhase.Prepared -> {
            when (accountOwnership(cleanup.accountId)) {
                DesktopAccountOwnership.Present -> {
                    clearCleanup(cleanup.accountId)
                    reactivatePresentAccount(cleanup)
                    return
                }
                DesktopAccountOwnership.Unknown -> return
                DesktopAccountOwnership.Absent -> Unit
            }
        }
        DesktopAccountSyncPairCleanupPhase.Committed -> Unit
    }
    removeSyncPairs(cleanup)
    clearCleanup(cleanup.accountId)
}

internal suspend fun retryPendingDesktopAccountSyncPairCleanups(
    cleanupJournal: DesktopAccountSyncPairCleanupJournal,
    accountOwnership: (String) -> DesktopAccountOwnership,
    removeSyncPairs: suspend (DesktopAccountSyncPairCleanup) -> Unit,
    recordCleanupFailure: (String, Exception) -> Unit,
    reactivatePresentAccount: (DesktopAccountSyncPairCleanup) -> Unit = {},
) {
    cleanupJournal.pending().forEach { cleanup ->
        try {
            retryDesktopAccountSyncPairCleanup(
                cleanup,
                accountOwnership,
                removeSyncPairs,
                cleanupJournal::clear,
                reactivatePresentAccount,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            runCatching { recordCleanupFailure(cleanup.accountId, failure) }
        }
    }
}

internal suspend fun recoverDesktopBackgroundAccountSyncPairCleanups(
    retry: suspend () -> Unit,
    recordFailure: (Exception) -> Unit,
) {
    try {
        retry()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        runCatching { recordFailure(failure) }
    }
}

internal suspend fun retryDesktopAccountSyncPairCleanupsBounded(
    maximumAttempts: Int = 3,
    waitBeforeNextAttempt: suspend () -> Unit = { delay(1_000L) },
    retryPending: suspend () -> Boolean,
) {
    require(maximumAttempts > 0)
    repeat(maximumAttempts) { attempt ->
        if (!retryPending()) return
        if (attempt + 1 < maximumAttempts) waitBeforeNextAttempt()
    }
}

internal fun desktopAccountSyncPairCleanupFailureDiagnostic(accountId: String, failure: Exception) =
    SupportDiagnosticEventDraft(
        severity = SupportDiagnosticSeverity.Error,
        component = SupportDiagnosticComponent.Sync,
        operation = "account.remove-sync-cleanup",
        outcome = "failed",
        fields = desktopAccountDiagnosticFields(accountId),
        exception = failure.toSupportDiagnosticExceptionDraft(),
    )

internal fun desktopAccountSyncPairCleanupJournalFailureDiagnostic(failure: Exception) =
    SupportDiagnosticEventDraft(
        severity = SupportDiagnosticSeverity.Error,
        component = SupportDiagnosticComponent.Sync,
        operation = "account.remove-sync-cleanup-journal",
        outcome = "failed",
        exception = failure.toSupportDiagnosticExceptionDraft(),
    )

internal fun desktopAccountSyncPairCleanupJournalMalformedDiagnostic() =
    SupportDiagnosticEventDraft(
        severity = SupportDiagnosticSeverity.Warning,
        component = SupportDiagnosticComponent.Sync,
        operation = "account.remove-sync-cleanup-journal",
        outcome = "unknown-entry-preserved",
    )
