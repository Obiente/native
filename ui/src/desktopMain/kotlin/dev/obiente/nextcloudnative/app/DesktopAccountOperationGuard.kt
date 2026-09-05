package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class DesktopAccountOperationGuard {
    private val accountMutationMutex = Mutex()
    private val syncRunMutex = Mutex()
    private val resourceActivationMonitor = Any()
    private var accountMutationActive = false

    suspend fun <Result> serialize(action: suspend () -> Result): Result =
        accountMutationMutex.withLock {
            synchronized(resourceActivationMonitor) { accountMutationActive = true }
            try {
                action()
            } finally {
                synchronized(resourceActivationMonitor) { accountMutationActive = false }
            }
        }

    suspend fun <Result> serializeWhenSyncIdle(action: suspend () -> Result): Result = serialize {
        withSyncRunLock(action)
    }

    suspend fun <Result> serializeResourceActivation(action: suspend () -> Result): Result = serialize(action)

    fun tryActivateResource(action: () -> Boolean): Boolean = synchronized(resourceActivationMonitor) {
        !accountMutationActive && action()
    }

    suspend fun <Result> withSyncRunLock(action: suspend () -> Result): Result = syncRunMutex.withLock { action() }
}

internal class DesktopSessionPublicationGuard {
    private val monitor = Any()

    fun <Result> serialize(action: () -> Result): Result = synchronized(monitor, action)
}

internal fun closeVirtualFileProviderForReplacement(
    provider: AutoCloseable?,
    detach: () -> Unit,
): Throwable? = runCatching { provider?.close() }
    .onSuccess { detach() }
    .exceptionOrNull()

internal fun desktopAccountDiagnosticFields(accountId: String?): List<SupportDiagnosticFieldDraft> =
    accountId?.let {
        listOf(
            SupportDiagnosticFieldDraft("account", it, SupportDiagnosticValuePrivacy.Identifier),
        )
    }.orEmpty()

internal fun desktopSessionSaveSwitchesAccount(
    activeAccountId: NextcloudAccountId?,
    savedAccountId: NextcloudAccountId,
): Boolean = activeAccountId != null && activeAccountId != savedAccountId

internal fun desktopSessionSaveReplacesActiveCredential(
    activeSession: NextcloudSession?,
    savedSession: NextcloudSession,
): Boolean = activeSession?.accountId == savedSession.accountId &&
    activeSession.appPassword != savedSession.appPassword

internal fun desktopResourceActivationMatchesActiveSession(
    activeSession: NextcloudSession?,
    requestedSession: NextcloudSession,
): Boolean = activeSession == requestedSession

internal fun desktopResourceDeactivationTargetsCurrentProvider(
    activeSession: NextcloudSession?,
    requestedSession: NextcloudSession,
    providerAccountId: String?,
): Boolean = desktopResourceActivationMatchesActiveSession(activeSession, requestedSession) &&
    providerAccountId == desktopFileCacheAccountId(requestedSession)

internal fun desktopSyncRunMatchesActiveSession(
    activeSession: NextcloudSession?,
    requestedSession: NextcloudSession,
): Boolean = activeSession == requestedSession

internal suspend fun <Result> DesktopAccountOperationGuard.withAuthenticatedMutationSession(
    expectedSession: NextcloudSession,
    resolveSession: suspend () -> NextcloudSession?,
    action: suspend (NextcloudSession) -> Result,
): Result = serialize {
    val current = resolveSession()
    check(desktopSyncRunMatchesActiveSession(current, expectedSession)) {
        "The account changed before the authenticated change could be sent."
    }
    action(requireNotNull(current))
}

internal fun requireDesktopAccountRemovalWritebacksResolved(pendingWritebackCount: Int) {
    check(pendingWritebackCount == 0) {
        "Finish or discard pending virtual file changes before removing this account."
    }
}

internal fun removeDesktopCredentialWithoutProviderReactivation(
    providerWasEnabled: Boolean,
    clearProviderPreference: () -> Unit,
    restoreProviderPreference: (Boolean) -> Unit,
    removalCommitted: () -> Boolean = { false },
    commitStatusObserved: (Boolean?) -> Unit = {},
    finishCommittedRemoval: () -> Unit = {},
    removeCredential: () -> Boolean,
): Boolean {
    return try {
        clearProviderPreference()
        removeCredential().also { removed ->
            commitStatusObserved(removed)
            if (!removed) restoreProviderPreference(providerWasEnabled)
        }
    } catch (failure: Throwable) {
        val committed = try {
            removalCommitted()
        } catch (statusFailure: Throwable) {
            failure.addSuppressed(statusFailure)
            null
        }
        commitStatusObserved(committed)
        when (committed) {
            false -> runCatching { restoreProviderPreference(providerWasEnabled) }
                .exceptionOrNull()
                ?.let(failure::addSuppressed)
            true -> runCatching(finishCommittedRemoval)
                .exceptionOrNull()
                ?.let(failure::addSuppressed)
            null -> Unit
        }
        throw failure
    }
}

internal fun shouldResumeDesktopWritesAfterRemovalFailure(
    removalCommitted: Boolean,
    remoteRevocationAttempted: Boolean,
    credentialRemovalStatus: Boolean?,
): Boolean = !removalCommitted && !remoteRevocationAttempted && credentialRemovalStatus == false

internal fun recoverDesktopAccountAfterPrecommitFailure(
    restoreProviderPreference: () -> Unit,
    resumeVirtualFileSystem: () -> Unit,
    resumeWindowsCloudFiles: () -> Unit = {},
    reopenSession: () -> Unit,
    restartLifecycle: () -> Unit,
): Throwable? {
    var recoveryFailure: Throwable? = null
    listOf(
        restoreProviderPreference, resumeVirtualFileSystem, resumeWindowsCloudFiles, reopenSession, restartLifecycle,
    ).forEach { action ->
        runCatching(action).exceptionOrNull()?.let { failure ->
            recoveryFailure?.addSuppressed(failure) ?: run { recoveryFailure = failure }
        }
    }
    return recoveryFailure
}

internal fun requireDesktopSessionSaveAllowed(
    allowed: Boolean,
    recordBlocked: (SupportDiagnosticEventDraft) -> Unit,
) {
    if (allowed) return
    recordBlocked(desktopAccountSelectionBlockedDiagnostic())
    error("Close files and virtual folders before switching accounts or replacing credentials.")
}

internal inline fun <Session> reopenDesktopSessionAfterSelection(
    selected: Session?,
    reopen: () -> Unit,
): Session? = selected.also { if (it != null) reopen() }

internal suspend inline fun <Session> restartDesktopSyncAfterSelection(
    select: () -> Session?,
    restart: () -> Unit,
): Session? = try {
    select()
} finally {
    restart()
}
