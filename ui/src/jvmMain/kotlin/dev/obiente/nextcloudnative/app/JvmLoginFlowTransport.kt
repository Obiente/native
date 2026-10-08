package dev.obiente.nextcloudnative.app

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

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
 * The pool belongs to this client alone. Another client's in-flight poll, such as one from a
 * cancelled or replaced sign-in attempt, can therefore never share its HTTP/2 connection.
 */
fun OkHttpClient.newLoginPollHttpClient(): OkHttpClient = loginPollHttpClientBuilder().build()

private fun OkHttpClient.loginPollHttpClientBuilder(): OkHttpClient.Builder = newBuilder()
    .retryOnConnectionFailure(false)
    .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))

/**
 * Runs one poll with a new [newLoginPollHttpClient] derived from [baseClient].
 *
 * OkHttp's blocking calls do not observe coroutine cancellation. Every call made through the
 * client is therefore registered with a [LoginPollCallCancellation] that the calling coroutine's
 * cancellation triggers, so a cancelled or replaced sign-in attempt cannot send a poll or leave
 * one running against the server.
 *
 * The calls are cancelled synchronously by the thread that cancels the coroutine. Cancellation
 * never waits for a dispatcher thread, so it still works while blocking work saturates
 * [kotlinx.coroutines.Dispatchers.IO].
 */
suspend fun <T> withLoginPollHttpClient(
    baseClient: OkHttpClient,
    block: suspend (OkHttpClient) -> T,
): T = coroutineScope {
    val calls = LoginPollCallCancellation()
    val client = baseClient.loginPollHttpClientBuilder()
        .apply { interceptors().add(0, calls) }
        .build()
    val cancellation = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
        suspendCancellableCoroutine<Nothing> { continuation ->
            continuation.invokeOnCancellation { calls.cancel() }
        }
    }
    try {
        block(client)
    } finally {
        cancellation.cancel()
    }
}

/**
 * Cancels every call of one poll, including calls that start after cancellation was requested.
 *
 * As the first application interceptor it sees each concrete [Call] before any network work.
 * Registration and cancellation share one lock: a call registered before [cancel] is cancelled by
 * it, and a call that arrives afterwards is cancelled before its request can be sent. Calls stay
 * registered for the poll's lifetime so cancellation also stops a response body still being read.
 */
internal class LoginPollCallCancellation : Interceptor {
    private val lock = Any()
    private var cancelled = false
    private val calls = mutableListOf<Call>()

    fun cancel() {
        val registered = synchronized(lock) {
            cancelled = true
            calls.toList()
        }
        registered.forEach(Call::cancel)
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val call = chain.call()
        val alreadyCancelled = synchronized(lock) {
            if (!cancelled) calls += call
            cancelled
        }
        if (alreadyCancelled) {
            call.cancel()
            throw IOException("Canceled")
        }
        return chain.proceed(chain.request())
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
