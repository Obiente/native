package dev.obiente.nextcloudnative.app

/**
 * Classifies a failed poll request by whether the one-time exchange could have reached the server.
 *
 * Only failures that occurred while the connection was being established, before OkHttp started
 * writing the request, are retried. Certificate, TLS handshake, protocol, and unknown failures
 * remain fatal because waiting does not resolve them, and any failure after the exchange started
 * stays ambiguous because the server may already have issued its one-time response.
 */
fun classifyLoginPollNetworkFailure(
    diagnostic: JvmNetworkFailureDiagnostic?,
): LoginPollResult = when {
    diagnostic != null && diagnostic.isTransientBeforeExchange() ->
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

private fun JvmNetworkFailureDiagnostic.isTransientBeforeExchange(): Boolean =
    !exchangeStarted && (
        code in TRANSIENT_CONNECTION_SETUP_CODES ||
            (code in TRANSIENT_SOCKET_CODES && phase in CONNECTION_SETUP_PHASES)
        )

private val TRANSIENT_CONNECTION_SETUP_CODES = setOf(
    "NETWORK_DNS_UNRESOLVED",
    "NETWORK_CONNECT_FAILED",
    "NETWORK_CONNECT_TIMEOUT",
    "NETWORK_UNREACHABLE",
    "NETWORK_TLS_TIMEOUT",
)
private val TRANSIENT_SOCKET_CODES = setOf("NETWORK_SOCKET_FAILED", "NETWORK_CONNECTION_RESET")
private val CONNECTION_SETUP_PHASES = setOf(JvmNetworkFailurePhase.Dns, JvmNetworkFailurePhase.Connect)
