package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CancellationException

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
    val usedFallback: Boolean,
    val selectedFallbackReason: LoginPollFallbackReason? = null,
)

fun executeLoginPollHttp(
    challenge: LoginChallenge,
    fallbackAlreadySelected: Boolean,
    poll: (String) -> LoginPollHttpResponse,
    networkFailure: () -> JvmNetworkFailureDiagnostic?,
): LoginPollHttpExecution {
    val fallbackEndpoint = challenge.pollFallbackEndpoint
    var usedFallback = fallbackAlreadySelected
    var responseCameFromFallback = fallbackAlreadySelected
    var selectedFallbackReason: LoginPollFallbackReason? = null

    fun failed(result: LoginPollResult) = LoginPollHttpExecution(
        interpretation = LoginPollHttpInterpretation(result),
        usedFallback = usedFallback,
        selectedFallbackReason = selectedFallbackReason,
    )

    fun attempt(endpoint: String): LoginPollHttpResponse = try {
        poll(endpoint)
    } catch (failure: Throwable) {
        if (failure is CancellationException) throw failure
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
            selectedFallbackReason = LoginPollFallbackReason.AdvertisedEndpointNotFound
        }
    }

    return LoginPollHttpExecution(
        interpretation = interpretLoginPollHttpResponse(response.status, response.body, challenge),
        usedFallback = usedFallback,
        selectedFallbackReason = selectedFallbackReason,
    )
}

private class LoginPollRequestFailure(
    val result: LoginPollResult,
    cause: Throwable,
) : RuntimeException(cause)
