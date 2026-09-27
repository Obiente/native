package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudSession
import java.io.IOException

internal suspend fun restoreAndroidFileSyncScheduleWhileCurrent(
    expectedSession: NextcloudSession,
    resolveSession: suspend () -> NextcloudSession?,
    runAttemptCount: Int,
    guard: AndroidAccountOperationGuard = ANDROID_ACCOUNT_OPERATION_GUARD,
    restore: suspend (NextcloudSession) -> Unit,
): BackgroundSyncWorkerDisposition = try {
    guard.withExactAccountSession(
        expectedSession, resolveSession, unavailable = { BackgroundSyncWorkerDisposition.Complete },
    ) { current ->
        restore(current)
        BackgroundSyncWorkerDisposition.Complete
    }
} catch (failure: Exception) {
    scheduleRestorationFailureDisposition(runAttemptCount, failure)
}

internal fun scheduleRestorationFailureDisposition(
    runAttemptCount: Int,
    failure: Throwable? = null,
): BackgroundSyncWorkerDisposition {
    require(runAttemptCount >= 0)
    val causes = generateSequence(failure) { it.cause }.take(8).toList()
    causes.forEach(::rethrowAndroidFileSyncCancellation)
    val status = causes.filterIsInstance<AndroidOcsRequestFailure>().firstOrNull()?.status
    val transient = when {
        failure == null -> true
        status != null -> status == 408 || status == 429 || status in 500..599
        causes.any { it is AndroidFileSyncStateTruncatedException } -> false
        causes.any { it is IOException } -> true
        else -> false
    }
    return if (transient && runAttemptCount < 2) BackgroundSyncWorkerDisposition.Retry
    else BackgroundSyncWorkerDisposition.Complete
}
