package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend fun <Result> withRetainedAndroidAccountFileRead(
    expectedSession: NextcloudSession,
    resolveSession: suspend () -> NextcloudSession?,
    guard: AndroidAccountOperationGuard = ANDROID_ACCOUNT_OPERATION_GUARD,
    read: suspend () -> Result,
): Result = withContext(Dispatchers.IO) {
    guard.withExactAccountSession(
        expectedSession = expectedSession,
        resolveSession = resolveSession,
        unavailable = { error("The account changed before the file read could finish.") },
    ) { read() }
}
