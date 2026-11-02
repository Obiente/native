package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CancellationException
import dev.obiente.nextcloudnative.nativeui.runtime.NativeVerifiedWriteOutcome
import dev.obiente.nextcloudnative.nativeui.runtime.NativeWriteSendResult
import dev.obiente.nextcloudnative.nativeui.runtime.NativeWriteVerification
import dev.obiente.nextcloudnative.nativeui.runtime.runNativeVerifiedWrite

/** Result of comparing a fresh server read with the change the user requested. */
sealed interface DeckWriteVerification {
    data object Confirmed : DeckWriteVerification
    data class Adjusted(val message: String) : DeckWriteVerification
    data class NotApplied(val message: String) : DeckWriteVerification
}

sealed interface DeckVerifiedWriteOutcome<out T> {
    /** The fresh read shows the change. [message] explains a server-side adjustment. */
    data class Confirmed<T>(val snapshot: T, val message: String? = null) : DeckVerifiedWriteOutcome<T>

    /** The server refused the change, or the fresh read shows it was not applied. */
    data class Rejected<T>(val snapshot: T?, val message: String) : DeckVerifiedWriteOutcome<T>

    /**
     * The write may have been applied but no fresh read could confirm it. Writes stay blocked until
     * a later read reconciles the state; the request is never retried automatically.
     */
    data class Unknown(val message: String) : DeckVerifiedWriteOutcome<Nothing>
}

/**
 * Sends one Deck write and verifies its postcondition with an authoritative read, through the
 * shared verified write path ([runNativeVerifiedWrite]).
 *
 * A definite client rejection (4xx) is reported without a second request. Any other failure,
 * including a transport error or an unreadable success response, is treated as ambiguous: the
 * state is re-read, and only the verified server state decides the outcome.
 */
internal suspend fun <T> runVerifiedDeckWrite(
    execute: suspend (NextcloudApiRequest) -> NextcloudApiResponse,
    request: NextcloudApiRequest,
    acceptResponse: (NextcloudApiResponse) -> Unit = { response -> parseDeckMutationReceipt(response) },
    reread: suspend () -> T,
    verify: (T) -> DeckWriteVerification,
    actionLabel: String,
): DeckVerifiedWriteOutcome<T> {
    var adjustment: String? = null
    val outcome = runNativeVerifiedWrite(
        actionLabel = actionLabel,
        send = {
            var status: Int? = null
            try {
                val response = execute(request)
                status = response.status
                acceptResponse(response)
                NativeWriteSendResult.Accepted
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val message = failure.deckWriteMessage(actionLabel)
                if (isAmbiguousDeckMutationFailure(status)) NativeWriteSendResult.Ambiguous(message) else NativeWriteSendResult.Rejected(message)
            }
        },
        readBack = reread,
        verify = { snapshot ->
            when (val verification = verify(snapshot)) {
                DeckWriteVerification.Confirmed -> NativeWriteVerification.Confirmed
                is DeckWriteVerification.Adjusted -> {
                    adjustment = verification.message
                    NativeWriteVerification.Adjusted(message = verification.message)
                }
                is DeckWriteVerification.NotApplied -> NativeWriteVerification.NotApplied(verification.message)
            }
        },
    )
    return when (outcome) {
        is NativeVerifiedWriteOutcome.Confirmed -> DeckVerifiedWriteOutcome.Confirmed(outcome.snapshot)
        is NativeVerifiedWriteOutcome.Adjusted -> DeckVerifiedWriteOutcome.Confirmed(outcome.snapshot, outcome.message ?: adjustment)
        is NativeVerifiedWriteOutcome.NotApplied -> DeckVerifiedWriteOutcome.Rejected(outcome.snapshot, outcome.message)
        is NativeVerifiedWriteOutcome.Unknown -> DeckVerifiedWriteOutcome.Unknown(outcome.message)
    }
}

private fun Exception.deckWriteMessage(actionLabel: String): String =
    message?.takeIf(String::isNotBlank) ?: "The app could not complete this change ($actionLabel)."
