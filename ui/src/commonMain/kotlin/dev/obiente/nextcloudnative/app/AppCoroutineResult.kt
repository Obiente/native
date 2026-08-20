package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CancellationException

internal inline fun <T> runCatchingPreservingCancellation(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Result.failure(failure)
    }
