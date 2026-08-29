package dev.obiente.nextcloudnative.app

import java.io.IOException
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Tracks whether a mutating HTTP request reached a network exchange before an I/O failure. */
internal class DesktopHttpMutationExecutor(client: OkHttpClient) {
    private val trackedClient = client.newBuilder()
        .retryOnConnectionFailure(false)
        .addNetworkInterceptor { chain ->
            chain.request().tag(DesktopHttpMutationAttempt::class.java)?.networkExchangeStarted = true
            chain.proceed(chain.request())
        }
        .build()

    fun <T> execute(
        request: Request,
        onAmbiguousNetworkResult: () -> Unit,
        onAcceptedResponse: () -> Unit = {},
        shouldContinue: (() -> Boolean)? = null,
        consume: (Response) -> T,
    ): T {
        val attempt = DesktopHttpMutationAttempt()
        val trackedRequest = request.newBuilder()
            .tag(DesktopHttpMutationAttempt::class.java, attempt)
            .build()
        val call = trackedClient.newCall(trackedRequest)
        return try {
            val execute = { activeCall: okhttp3.Call ->
                activeCall.execute().use { response ->
                    if (response.isSuccessful) runCatching(onAcceptedResponse)
                    consume(response)
                }
            }
            shouldContinue?.let { continuation ->
                executeDesktopFileSyncCancellableCall(call, continuation, execute)
            } ?: execute(call)
        } catch (cancelled: CancellationException) {
            if (attempt.networkExchangeStarted) runCatching(onAmbiguousNetworkResult)
            throw cancelled
        } catch (failure: IOException) {
            if (desktopMutationResultIsAmbiguous(attempt.networkExchangeStarted, failure)) {
                runCatching(onAmbiguousNetworkResult)
                throw DesktopFileSyncAmbiguousMutationException(failure)
            }
            throw failure
        }
    }
}

internal fun desktopMutationResultIsAmbiguous(
    networkExchangeStarted: Boolean,
    failure: Throwable,
): Boolean = networkExchangeStarted && failure is IOException

private class DesktopHttpMutationAttempt {
    @Volatile
    var networkExchangeStarted: Boolean = false
}
