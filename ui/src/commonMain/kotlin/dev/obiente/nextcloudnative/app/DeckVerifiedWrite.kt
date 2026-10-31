package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CancellationException

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
 * Sends one Deck write and verifies its postcondition with an authoritative read.
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
    var status: Int? = null
    val writeFailure: Exception? = try {
        val response = execute(request)
        status = response.status
        acceptResponse(response)
        null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        failure
    }
    if (writeFailure != null && !isAmbiguousDeckMutationFailure(status)) {
        return DeckVerifiedWriteOutcome.Rejected(null, writeFailure.deckWriteMessage(actionLabel))
    }
    val snapshot = try {
        reread()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        return DeckVerifiedWriteOutcome.Unknown(
            "Deck may have applied this change ($actionLabel). Refresh before making another change.",
        )
    }
    return when (val verification = verify(snapshot)) {
        DeckWriteVerification.Confirmed -> DeckVerifiedWriteOutcome.Confirmed(snapshot)
        is DeckWriteVerification.Adjusted -> DeckVerifiedWriteOutcome.Confirmed(snapshot, verification.message)
        is DeckWriteVerification.NotApplied -> DeckVerifiedWriteOutcome.Rejected(
            snapshot,
            writeFailure?.deckWriteMessage(actionLabel) ?: verification.message,
        )
    }
}

private fun Exception.deckWriteMessage(actionLabel: String): String =
    message?.takeIf(String::isNotBlank) ?: "Deck could not complete this change ($actionLabel)."
