package dev.obiente.nextcloudnative.app

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient

data class LoginPollHttpResponse(
    val status: Int,
    val body: String,
)

enum class LoginPollFallbackReason {
    AdvertisedEndpointNotFound,
    PreExchangeFailure,
}

data class LoginPollHttpExecution(
    val interpretation: LoginPollHttpInterpretation,
    /** Whether later polls should keep using the compatibility endpoint. */
    val usedFallback: Boolean,
    /** Whether the response interpreted for this execution came from the compatibility endpoint. */
    val responseUsedFallback: Boolean,
    val selectedFallbackReason: LoginPollFallbackReason? = null,
)

/**
 * Derives the client used for one-time Login Flow v2 polls from the platform's base client.
 *
 * The HTTP stack must never replay a poll after its request may have reached the server, so
 * connection-failure recovery stays disabled. Without that recovery, a pooled connection that
 * died while the app was suspended or in the background, or that a proxy closed after its
 * keep-alive window, fails only after the request has started and is indistinguishable from a
 * lost one-time approval response. Each poll therefore opens a new connection and closes it
 * after the response, so no connection outlives the wait between polls. Failures while that
 * connection is being established remain provably pre-exchange.
 *
 * The pool and dispatcher belong to this client alone. Another client's in-flight poll, such as
 * one from a cancelled or replaced sign-in attempt, can therefore never share its HTTP/2
 * connection, and cancelling this client's calls cannot affect unrelated requests. A dispatcher
 * that only runs synchronous calls starts no threads.
 */
fun OkHttpClient.newLoginPollHttpClient(): OkHttpClient = newBuilder()
    .retryOnConnectionFailure(false)
    .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
    .dispatcher(Dispatcher())
    .build()

/**
 * Runs one poll with a new [newLoginPollHttpClient] derived from [baseClient].
 *
 * OkHttp's blocking calls do not observe coroutine cancellation, so the client's calls are
 * cancelled as soon as the calling coroutine is cancelled. A cancelled or replaced sign-in attempt
 * therefore cannot leave a poll running against the server.
 */
suspend fun <T> withLoginPollHttpClient(
    baseClient: OkHttpClient,
    block: suspend (OkHttpClient) -> T,
): T = coroutineScope {
    val client = baseClient.newLoginPollHttpClient()
    // Runs on its own dispatcher so cancellation is delivered while [block] blocks a thread.
    val cancellation = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            client.dispatcher.cancelAll()
        }
    }
    try {
        block(client)
    } finally {
        cancellation.cancel()
    }
}

suspend fun executeLoginPollHttp(
    challenge: LoginChallenge,
    fallbackAlreadySelected: Boolean,
    poll: suspend (String) -> LoginPollHttpResponse,
    networkFailure: () -> JvmNetworkFailureDiagnostic?,
): LoginPollHttpExecution {
    val fallbackEndpoint = challenge.pollFallbackEndpoint
    var usedFallback = fallbackAlreadySelected
    var responseCameFromFallback = fallbackAlreadySelected
    var selectedFallbackReason: LoginPollFallbackReason? = null

    fun failed(result: LoginPollResult) = LoginPollHttpExecution(
        interpretation = LoginPollHttpInterpretation(result),
        usedFallback = usedFallback,
        responseUsedFallback = responseCameFromFallback,
        selectedFallbackReason = selectedFallbackReason,
    )

    suspend fun attempt(endpoint: String): LoginPollHttpResponse = try {
        poll(endpoint)
    } catch (failure: Throwable) {
        if (failure is CancellationException) throw failure
        // A call cancelled with its coroutine fails as an I/O error; it is not a network outcome.
        currentCoroutineContext().ensureActive()
        throw LoginPollRequestFailure(classifyLoginPollNetworkFailure(networkFailure()), failure)
    }

    var response = try {
        attempt(if (usedFallback) requireNotNull(fallbackEndpoint) else challenge.pollEndpoint)
    } catch (failure: LoginPollRequestFailure) {
        if (
            failure.result is LoginPollResult.RetryablePreExchangeFailure &&
            !usedFallback &&
            fallbackEndpoint != null
        ) {
            val primaryFailure = failure.result
            try {
                attempt(fallbackEndpoint).let { compatibilityResponse ->
                    when {
                        compatibilityResponse.status in 200..299 -> compatibilityResponse.also {
                            usedFallback = true
                            responseCameFromFallback = true
                            selectedFallbackReason = LoginPollFallbackReason.PreExchangeFailure
                        }
                        compatibilityResponse.status == 404 -> compatibilityResponse.also {
                            responseCameFromFallback = true
                        }
                        else -> return failed(primaryFailure)
                    }
                }
            } catch (fallbackFailure: LoginPollRequestFailure) {
                return failed(fallbackFailure.result)
            }
        } else {
            return failed(failure.result)
        }
    }

    if (response.status == 404 && !responseCameFromFallback && fallbackEndpoint != null) {
        val compatibilityResponse = try {
            attempt(fallbackEndpoint)
        } catch (failure: LoginPollRequestFailure) {
            if (failure.result is LoginPollResult.RetryablePreExchangeFailure) null else return failed(failure.result)
        }
        if (compatibilityResponse != null && compatibilityResponse.status in 200..299) {
            response = compatibilityResponse
            usedFallback = true
            responseCameFromFallback = true
            selectedFallbackReason = LoginPollFallbackReason.AdvertisedEndpointNotFound
        }
    }

    return LoginPollHttpExecution(
        interpretation = interpretLoginPollHttpResponse(response.status, response.body, challenge),
        usedFallback = usedFallback,
        responseUsedFallback = responseCameFromFallback,
        selectedFallbackReason = selectedFallbackReason,
    )
}

private class LoginPollRequestFailure(
    val result: LoginPollResult,
    cause: Throwable,
) : RuntimeException(cause)
