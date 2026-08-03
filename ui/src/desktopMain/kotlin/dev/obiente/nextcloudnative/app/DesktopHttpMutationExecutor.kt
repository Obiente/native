package dev.obiente.nextcloudnative.app

import java.io.IOException
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
        consume: (Response) -> T,
    ): T {
        val attempt = DesktopHttpMutationAttempt()
        val trackedRequest = request.newBuilder()
            .tag(DesktopHttpMutationAttempt::class.java, attempt)
            .build()
        return try {
            trackedClient.newCall(trackedRequest).execute().use(consume)
        } catch (failure: IOException) {
            if (desktopMutationResultIsAmbiguous(attempt.networkExchangeStarted, failure)) {
                runCatching(onAmbiguousNetworkResult)
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
