package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudAccountId
import dev.obiente.nextcloudnative.app.NextcloudSession
import java.io.FileNotFoundException
import kotlinx.coroutines.CancellationException

/** Lookup failures may publish only through an account whose current diagnostic scope is known. */
internal fun <Result> androidDocumentsProviderCall(
    message: String,
    accountIdentity: String?,
    diagnosticSession: (() -> NextcloudSession?)?,
    loadSession: (NextcloudAccountId) -> NextcloudSession?,
    record: (String?, Throwable) -> Unit,
    operation: () -> Result,
): Result = try {
    operation()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (missing: FileNotFoundException) {
    throw missing
} catch (failure: Exception) {
    if (diagnosticSession == null) record(accountIdentity, failure)
    else recordAndroidResolvedProviderFailure(diagnosticSession, loadSession) { scope -> record(scope, failure) }
    throw FileNotFoundException(message).also { it.initCause(failure) }
}

internal fun recordAndroidResolvedProviderFailure(
    resolveSession: () -> NextcloudSession?,
    loadSession: (NextcloudAccountId) -> NextcloudSession?,
    guard: AndroidAccountOperationGuard = ANDROID_ACCOUNT_OPERATION_GUARD,
    record: (String) -> Unit,
) {
    try {
        val session = resolveSession() ?: return
        withCurrentAndroidWritebackDiagnosticScope(session, { loadSession(session.accountId) }, guard, record)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Missing, ambiguous, retired, and malformed lookup identities cannot create an unowned scope.
    }
}
