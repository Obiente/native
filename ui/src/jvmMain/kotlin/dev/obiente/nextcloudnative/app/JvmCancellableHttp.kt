package dev.obiente.nextcloudnative.app

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Executes an account-bound request without allowing any redirected call to outlive its coroutine. */
suspend fun <T> executeCancellableNextcloudAuthenticatedRequest(
    client: OkHttpClient,
    initialRequest: Request,
    onNetworkFailure: (Throwable) -> Unit,
    consume: (Response, shouldContinue: () -> Boolean) -> T,
): T {
    val requestClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    return suspendCancellableCoroutine { continuation ->
        val activeCall = AtomicReference<Call?>(null)
        continuation.invokeOnCancellation { activeCall.get()?.cancel() }
        val execute = Runnable {
            val result = runCatching {
                executeNextcloudAuthenticatedRequest(
                    client = requestClient,
                    initialRequest = initialRequest,
                    executeCall = { call ->
                        activeCall.set(call)
                        if (!continuation.isActive) call.cancel()
                        try {
                            call.execute()
                        } catch (failure: Throwable) {
                            onNetworkFailure(failure)
                            throw failure
                        }
                    },
                ) { response ->
                    consume(response) {
                        continuation.isActive && activeCall.get()?.isCanceled() == false
                    }
                }
            }
            activeCall.set(null)
            continuation.resumeWith(result)
        }
        runCatching { requestClient.dispatcher.executorService.execute(execute) }
            .onFailure { failure ->
                activeCall.set(null)
                continuation.resumeWith(Result.failure(failure))
            }
    }
}
