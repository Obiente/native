package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal enum class LoginAttemptPhase(val message: String, val buttonLabel: String) {
    Contacting("Contacting your server...", "Connecting..."),
    AwaitingApproval("Finish signing in in your browser, then return here.", "Waiting for approval"),
    WaitingForNetwork(
        "The server name could not be resolved. Check your network or VPN. Retrying...",
        "Retrying connection...",
    ),
    ReconnectingToServer(
        "The server could not be reached. Check your network or VPN. Retrying...",
        "Retrying connection...",
    ),
    Completing("Finishing sign-in...", "Finishing sign-in..."),
}

/** Owns a single bounded browser-approval attempt and rejects cancelled/replaced completions. */
internal class LoginAttemptState(private val timeoutMillis: Long = 5 * 60_000L) {
    init { require(timeoutMillis > 0) }

    var phase by mutableStateOf<LoginAttemptPhase?>(null)
        private set
    var failure by mutableStateOf<Throwable?>(null)
        private set
    private var generation = 0L
    private var job: Job? = null

    fun cancel() {
        generation += 1
        job?.cancel()
        job = null
        phase = null
        failure = null
    }

    fun start(
        scope: CoroutineScope,
        begin: suspend () -> LoginChallenge,
        openBrowser: suspend (String) -> Unit,
        poll: suspend (LoginChallenge) -> LoginPollResult,
        awaitNetwork: suspend () -> Unit,
        finish: (LoginChallenge) -> Unit,
        onAuthenticated: suspend (NextcloudSession) -> Unit,
    ) {
        cancel()
        val attempt = generation
        phase = LoginAttemptPhase.Contacting
        job = scope.launch {
            var challenge: LoginChallenge? = null
            try {
                val authenticated = withTimeoutOrNull(timeoutMillis) {
                    val acquired = begin()
                    challenge = acquired
                    currentCoroutineContext().ensureActive()
                    if (generation != attempt) throw CancellationException("Sign-in replaced")
                    openBrowser(acquired.loginUrl)
                    currentCoroutineContext().ensureActive()
                    phase = LoginAttemptPhase.AwaitingApproval
                    pollLoginUntilApproved(
                        poll = { poll(acquired) },
                        waitBeforeNextPoll = { delayMillis, needsNetwork ->
                            if (needsNetwork) awaitNetwork()
                            delay(delayMillis)
                        },
                        hasTimedOut = { false },
                        onStatus = { status ->
                            if (generation == attempt) phase = status
                        },
                    )
                } ?: error("Sign-in timed out. Check the server address and network, then try again.")
                currentCoroutineContext().ensureActive()
                if (generation != attempt) return@launch
                phase = LoginAttemptPhase.Completing
                onAuthenticated(authenticated)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (problem: Throwable) {
                // The sign-in UI is a fault boundary; cancellation still remains control flow.
                if (generation == attempt) failure = problem
            } finally {
                try {
                    challenge?.let(finish)
                } finally {
                    if (generation == attempt) phase = null
                }
            }
        }
    }
}
