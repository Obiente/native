package dev.obiente.nextcloudnative.app

import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.OkHttpClient

/** Executes a blocking OkHttp call without allowing it to outlive its owning coroutine. */
suspend fun <T> executeCancellableJvmHttpCall(
    client: OkHttpClient,
    call: Call,
    block: (Call, shouldContinue: () -> Boolean) -> T,
): T {
    val job = currentCoroutineContext()[Job]
    return suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        val execute = Runnable {
            val result = runCatching {
                block(call) { job?.isActive != false && !call.isCanceled() }
            }
            continuation.resumeWith(result)
        }
        runCatching { client.dispatcher.executorService.execute(execute) }
            .onFailure { failure -> continuation.resumeWith(Result.failure(failure)) }
    }
}

/** Nextcloud credentials are UTF-8 throughout the JVM transports. */
fun nextcloudBasicAuthorization(session: NextcloudSession): String {
    val encoded = Base64.getEncoder().encodeToString(
        "${session.loginName}:${session.appPassword}".toByteArray(StandardCharsets.UTF_8),
    )
    return "Basic $encoded"
}
