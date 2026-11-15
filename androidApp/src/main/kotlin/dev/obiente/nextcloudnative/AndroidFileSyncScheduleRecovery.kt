package dev.obiente.nextcloudnative

import android.content.Context
import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.SupportDiagnosticComponent
import dev.obiente.nextcloudnative.app.SupportDiagnosticEventDraft
import dev.obiente.nextcloudnative.app.SupportDiagnosticSeverity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal fun startAndroidFileSyncScheduleRecovery(context: Context, scope: CoroutineScope) = scope.launch {
    try {
        restoreAndroidFileSyncSchedulesAtStartup(
            load = { AndroidFileSyncStore(context).load() },
            loadSession = { AndroidNextcloudServices(context).loadSession() },
            enqueue = { AndroidFileSyncScheduler(context).restorePersistedPairSchedules(it) },
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        AndroidSupportDiagnostics.get(context).record(SupportDiagnosticEventDraft(
            severity = SupportDiagnosticSeverity.Error, component = SupportDiagnosticComponent.Sync,
            operation = "file-sync.startup-schedules", outcome = "recovery-blocked",
            code = "FILE_SYNC_SCHEDULE_MAINTENANCE_FAILED",
        ))
    }
}

internal suspend fun restoreAndroidFileSyncSchedulesAtStartup(
    load: () -> AndroidFileSyncPersistedState,
    loadSession: suspend () -> NextcloudSession?,
    enqueue: (String) -> Unit,
) {
    val pairs = load().coordinator.pairs
    if (pairs.isEmpty()) return
    val account = loadSession()?.let(NextcloudDocumentIds::accountKey) ?: return
    // The worker rechecks the exact current session and fresh pairs under the account and engine locks.
    if (pairs.any { it.accountId == account }) enqueue(account)
}
