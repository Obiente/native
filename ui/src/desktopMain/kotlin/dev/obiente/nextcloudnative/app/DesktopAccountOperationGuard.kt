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

internal fun desktopResourceActivationMatchesActiveAccount(
    activeAccountId: NextcloudAccountId?,
    requestedAccountId: NextcloudAccountId,
): Boolean = activeAccountId == requestedAccountId

internal fun desktopSyncRunMatchesActiveSession(
    activeSession: NextcloudSession?,
    requestedSession: NextcloudSession,
): Boolean = activeSession == requestedSession

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
