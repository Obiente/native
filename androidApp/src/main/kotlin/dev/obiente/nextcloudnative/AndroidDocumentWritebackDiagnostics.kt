package dev.obiente.nextcloudnative

import android.util.Log
import dev.obiente.nextcloudnative.app.NextcloudSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

internal fun retainAndroidDocumentWritebackFailure(
    session: NextcloudSession,
    writeback: AndroidDocumentPendingWriteback,
    failure: Throwable,
    resolveSession: () -> NextcloudSession?,
    record: (String, Boolean) -> Unit,
) {
    val complete = writeback.staging.isFile && writeback.manifest.isFile
    Log.e("NextcloudDocuments", if (complete) "Document commit failed; local recovery was retained." else "Document recovery storage is incomplete.", failure)
    withCurrentAndroidWritebackDiagnosticScope(session, resolveSession) { scope -> record(scope, complete) }
}

/** Optional diagnostics must not outlive the retained account or publish under an obsolete spelling. */
internal fun withCurrentAndroidWritebackDiagnosticScope(
    captured: NextcloudSession,
    resolveSession: () -> NextcloudSession?,
    guard: AndroidAccountOperationGuard = ANDROID_ACCOUNT_OPERATION_GUARD,
    publish: (String) -> Unit,
) {
    try {
        val candidate = resolveSession()?.takeIf { it.accountId == captured.accountId } ?: return
        runBlocking {
            guard.tryWithAccounts(androidAccountOperationIdentities(candidate), unavailable = {}) {
                val current = resolveSession()
                if (current == candidate) publish(NextcloudDocumentIds.accountKey(candidate))
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Diagnostic collection is optional and must not replace the retained-writeback outcome.
    }
}
