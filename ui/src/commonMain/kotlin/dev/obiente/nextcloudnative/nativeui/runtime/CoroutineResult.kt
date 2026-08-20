package dev.obiente.nextcloudnative.nativeui.runtime

import kotlin.coroutines.cancellation.CancellationException

internal inline fun <T> runCatchingUnlessCancelled(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Result.failure(failure)
    }
