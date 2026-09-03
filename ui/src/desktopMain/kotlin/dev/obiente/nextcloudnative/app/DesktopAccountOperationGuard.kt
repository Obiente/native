package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class DesktopAccountOperationGuard {
    private val accountMutationMutex = Mutex()
    private val syncRunMutex = Mutex()

    suspend fun <Result> serialize(action: suspend () -> Result): Result =
        accountMutationMutex.withLock { action() }

    suspend fun <Result> serializeWhenSyncIdle(action: suspend () -> Result): Result = serialize {
        withSyncRunLock(action)
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

internal fun requireDesktopSessionSaveAllowed(
    allowed: Boolean,
    recordBlocked: (SupportDiagnosticEventDraft) -> Unit,
) {
    if (allowed) return
    recordBlocked(desktopAccountSelectionBlockedDiagnostic())
    error("Close files and virtual folders before switching accounts.")
}

internal inline fun <Session> reopenDesktopSessionAfterSelection(
    selected: Session?,
    reopen: () -> Unit,
): Session? = selected.also { if (it != null) reopen() }
