package dev.obiente.nextcloudnative

import kotlinx.coroutines.CancellationException

internal suspend fun <Result> captureDurableUploadRequestOutcome(
    request: suspend () -> Result,
): kotlin.Result<Result> = try {
    kotlin.Result.success(request())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    kotlin.Result.failure(failure)
}
