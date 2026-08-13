package dev.obiente.nextcloudnative.app

import kotlin.math.min

internal const val LOGIN_POLL_PENDING_DELAY_MILLIS = 2_000L
internal const val LOGIN_POLL_MAX_RETRY_DELAY_MILLIS = 15_000L

internal suspend fun pollLoginUntilApproved(
    poll: suspend () -> LoginPollResult,
    waitBeforeNextPoll: suspend (delayMillis: Long, awaitNetwork: Boolean) -> Unit,
    hasTimedOut: () -> Boolean,
    onStatus: (String) -> Unit = {},
): NextcloudSession {
    var transientFailures = 0
    while (!hasTimedOut()) {
        when (val result = poll()) {
            LoginPollResult.Pending -> {
                transientFailures = 0
                onStatus("Finish signing in in your browser, then return here.")
                waitBeforeNextPoll(LOGIN_POLL_PENDING_DELAY_MILLIS, false)
            }
            is LoginPollResult.Approved -> return result.session
            is LoginPollResult.RetryablePreExchangeFailure -> {
                transientFailures += 1
                val delayMillis = loginPollRetryDelayMillis(transientFailures)
                onStatus("The server name is temporarily unavailable. Waiting for the network and retrying...")
                waitBeforeNextPoll(delayMillis, true)
            }
            is LoginPollResult.FatalFailure -> error(result.message)
            is LoginPollResult.AmbiguousAfterExchangeFailure -> error(result.message)
        }
    }
    error("Login approval timed out. Please try again.")
}

internal fun loginPollRetryDelayMillis(consecutiveFailures: Int): Long {
    require(consecutiveFailures > 0)
    var delayMillis = LOGIN_POLL_PENDING_DELAY_MILLIS
    repeat(min(consecutiveFailures - 1, 30)) {
        delayMillis = min(delayMillis * 2, LOGIN_POLL_MAX_RETRY_DELAY_MILLIS)
    }
    return delayMillis
}
