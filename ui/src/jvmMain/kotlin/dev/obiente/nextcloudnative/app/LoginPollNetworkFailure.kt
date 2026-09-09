package dev.obiente.nextcloudnative.app

fun classifyLoginPollNetworkFailure(
    diagnostic: JvmNetworkFailureDiagnostic?,
): LoginPollResult = when {
    diagnostic?.code == "NETWORK_DNS_UNRESOLVED" && !diagnostic.exchangeStarted ->
        LoginPollResult.RetryablePreExchangeFailure(diagnostic.code)
    diagnostic?.exchangeStarted == true -> LoginPollResult.AmbiguousAfterExchangeFailure(
        message = "The login response could not be confirmed safely after contacting the server. " +
            "Restart sign-in so the app does not reuse a one-time approval response.",
        code = diagnostic.code,
    )
    else -> LoginPollResult.FatalFailure(
        message = "Could not contact the server to finish sign-in. Check the server address and network, then try again.",
        code = diagnostic?.code,
    )
}

fun ambiguousLoginPollResponse(message: String): LoginPollResult =
    LoginPollResult.AmbiguousAfterExchangeFailure(
        message = "$message Restart sign-in so the app does not reuse a one-time approval response.",
        code = "LOGIN_POLL_RESPONSE_INVALID",
    )
