package dev.obiente.nextcloudnative.app

internal fun FileSyncPair.prepareForDesktopExecution(
    resetExhaustedFailures: Boolean,
    nowEpochMillis: Long = System.currentTimeMillis(),
): FileSyncPair {
    require(nowEpochMillis >= 0L)
    return copy(
        workItems = workItems.map { work ->
            when {
                work.state != FileSyncExecutionState.Failed -> work
                resetExhaustedFailures && work.attemptCount >= MAX_FILE_SYNC_ATTEMPTS -> work.copy(
                    state = FileSyncExecutionState.Ready,
                    attemptCount = 0,
                    lastAttemptEpochMillis = null,
                    failureMessage = null,
                )
                resetExhaustedFailures -> work.copy(
                    state = FileSyncExecutionState.Ready,
                    failureMessage = null,
                )
                desktopAutomaticFileSyncRetryEligible(
                    attemptCount = work.attemptCount,
                    lastAttemptEpochMillis = work.lastAttemptEpochMillis,
                    nowEpochMillis = nowEpochMillis,
                ) -> work.copy(state = FileSyncExecutionState.Ready, failureMessage = null)
                else -> work
            }
        },
    )
}

internal fun desktopAutomaticFileSyncRetryEligible(
    attemptCount: Int,
    lastAttemptEpochMillis: Long?,
    nowEpochMillis: Long,
): Boolean {
    require(attemptCount in 0..MAX_FILE_SYNC_ATTEMPTS)
    require(lastAttemptEpochMillis == null || lastAttemptEpochMillis >= 0L)
    require(nowEpochMillis >= 0L)
    if (attemptCount == 0 || attemptCount >= DESKTOP_FILE_SYNC_AUTOMATIC_ATTEMPTS) return false
    val lastAttempt = lastAttemptEpochMillis ?: return false
    if (nowEpochMillis < lastAttempt) return false
    return nowEpochMillis - lastAttempt >= desktopFileSyncRetryDelayMillis(attemptCount)
}

internal fun desktopFileSyncRetryDelayMillis(attemptCount: Int): Long {
    require(attemptCount in 1 until DESKTOP_FILE_SYNC_AUTOMATIC_ATTEMPTS)
    return DESKTOP_FILE_SYNC_RETRY_BASE_MILLIS shl (attemptCount - 1)
}

private const val DESKTOP_FILE_SYNC_AUTOMATIC_ATTEMPTS = 5
private const val DESKTOP_FILE_SYNC_RETRY_BASE_MILLIS = 2L * 60L * 1_000L
