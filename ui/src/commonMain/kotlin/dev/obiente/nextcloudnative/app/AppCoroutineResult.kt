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
