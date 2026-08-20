package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CancellationException

suspend fun <T> runCatchingPreservingCancellation(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Result.failure(failure)
    }

suspend fun <T> runWithCleanupBeforeHandoff(
    cleanup: () -> Unit,
    block: suspend (markHandedOff: () -> Unit) -> T,
): T {
    var handedOff = false
    return try {
        block { handedOff = true }
    } catch (failure: Throwable) {
        if (!handedOff) {
            runCatching(cleanup).exceptionOrNull()?.let(failure::addSuppressed)
        }
        throw failure
    }
}
