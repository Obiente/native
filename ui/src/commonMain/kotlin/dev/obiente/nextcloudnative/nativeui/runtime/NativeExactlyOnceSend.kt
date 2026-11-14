package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.app.publicContentSha256
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlin.random.Random

/*
 * Exactly-once sends (technique D, risk class R6). A send has an effect outside the app, so it is
 * never repeated to find out whether it worked:
 * - an attempt record (id, time, target summary, payload fingerprint) is durable before the
 *   request can leave the device;
 * - the request is sent once and never retried automatically;
 * - 2xx is sent; 4xx is rejected and the draft stays; anything else is "may have been sent", with
 *   the recorded evidence shown;
 * - sending again after that needs the person's explicit confirmation naming the risk, and a
 *   refusal of that resend leaves the earlier attempt unresolved;
 * - a declared Idempotency-Key is generated once per attempt and reused on a confirmed resend of
 *   the same payload, so a server that honours it can drop the duplicate.
 */

@Serializable
enum class NativeSendAttemptState {
    /** Recorded before the request; after a restart this means "may have been sent". */
    Prepared,

    /** The request may have reached the server; nothing confirmed it. */
    MaybeSent,
}

/** Why an attempt may have been sent. Typed evidence only; never a response body. */
@Serializable
enum class NativeSendUnknownReason {
    /** The server answered with something other than a success or a definite refusal. */
    Unconfirmed,

    /** The request was interrupted (the app closed or the operation was cancelled) after it started. */
    Interrupted,
}

@Serializable
data class NativeSendAttempt(
    val attemptId: String,
    val createdAtEpochMillis: Long,
    /** A bounded, content-free description such as "2 recipients". */
    val targetSummary: String,
    /** SHA-256 of the exact request values, so a resend of the same payload is recognised. */
    val payloadFingerprint: String,
    val idempotencyKey: String? = null,
    val state: NativeSendAttemptState = NativeSendAttemptState.Prepared,
    val unknownReason: NativeSendUnknownReason? = null,
) {
    init {
        require(attemptId.isAttemptToken() && payloadFingerprint.length == 64) { "The send attempt record is invalid." }
        require(idempotencyKey == null || idempotencyKey.isAttemptToken()) { "The idempotency key is invalid." }
        require(targetSummary.length <= MAX_TARGET_SUMMARY && targetSummary.none(Char::isISOControl))
    }
}

/** What travels with the request so the executor can admit it and attach a declared key. */
data class NativeSendAttemptToken(val attemptId: String, val idempotencyKey: String?)

/** Durable, account-scoped storage for the one open attempt of one send surface. */
interface NativeSendAttemptStore {
    suspend fun load(): NativeSendAttempt?

    suspend fun save(attempt: NativeSendAttempt)

    suspend fun clear()
}

sealed interface NativeExactlyOnceSendOutcome {
    data class Sent(val message: String?) : NativeExactlyOnceSendOutcome

    /**
     * The server refused this request. With no [earlier] attempt nothing was sent and sending
     * again is safe after a correction. A refused resend says nothing about the attempt before
     * it: [earlier] is that attempt, still unresolved and still stored.
     */
    data class Rejected(val message: String, val earlier: NativeSendAttempt? = null) : NativeExactlyOnceSendOutcome

    /** The send may have been delivered. Only a confirmed resend may follow. */
    data class MaybeSent(val attempt: NativeSendAttempt) : NativeExactlyOnceSendOutcome

    /** Nothing was sent: a previous attempt is unresolved, or no durable record could be kept. */
    data class NotSent(val reason: NativeSendRefusal) : NativeExactlyOnceSendOutcome
}

enum class NativeSendRefusal {
    /** An earlier attempt may have been sent; the person has not confirmed sending again. */
    ResendNeedsConfirmation,

    /** The attempt record could not be stored, so the request was not sent. */
    AttemptNotRecorded,
}

/**
 * The exactly-once state machine for one send surface. The caller owns the draft; this class owns
 * only the attempt record and the single request.
 */
class NativeExactlyOnceSend(
    private val store: NativeSendAttemptStore,
    private val clock: () -> Long,
    private val newToken: () -> String = ::newNativeSendToken,
) {
    /** The unresolved attempt, if any. A [NativeSendAttemptState.Prepared] record also counts. */
    suspend fun pending(): NativeSendAttempt? = store.load()

    /**
     * Sends [request] at most once. With an unresolved earlier attempt it sends only when
     * [confirmedResend] is true, and then reuses the earlier idempotency key for the same payload.
     */
    suspend fun send(
        request: NativeActionRequest.Submit,
        executor: NativeActionExecutor,
        targetSummary: String,
        confirmedResend: Boolean,
        declaresIdempotencyKey: Boolean,
    ): NativeExactlyOnceSendOutcome {
        val previous = try {
            store.load()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Persistence fault boundary: without the earlier record a resend cannot be judged.
            return NativeExactlyOnceSendOutcome.NotSent(NativeSendRefusal.AttemptNotRecorded)
        }
        if (previous != null && !confirmedResend) return NativeExactlyOnceSendOutcome.NotSent(NativeSendRefusal.ResendNeedsConfirmation)
        val fingerprint = nativeSendFingerprint(request)
        val attempt = NativeSendAttempt(
            attemptId = newToken(),
            createdAtEpochMillis = clock(),
            targetSummary = targetSummary.take(MAX_TARGET_SUMMARY),
            payloadFingerprint = fingerprint,
            idempotencyKey = when {
                !declaresIdempotencyKey -> null
                previous?.payloadFingerprint == fingerprint -> previous.idempotencyKey ?: newToken()
                else -> newToken()
            },
        )
        try {
            store.save(attempt)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return NativeExactlyOnceSendOutcome.NotSent(NativeSendRefusal.AttemptNotRecorded)
        }
        // From here the request may leave the device; a cancellation leaves the Prepared record,
        // which every later reader treats as "may have been sent".
        val result = try {
            executor.execute(request.copy(sendAttempt = NativeSendAttemptToken(attempt.attemptId, attempt.idempotencyKey)))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            NativeActionExecutionResult.Failure(message = "", outcome = NativeActionFailureOutcome.Unknown)
        }
        return when {
            result is NativeActionExecutionResult.Success -> {
                clearQuietly()
                NativeExactlyOnceSendOutcome.Sent(result.message)
            }
            result is NativeActionExecutionResult.Failure && result.outcome == NativeActionFailureOutcome.Rejected -> {
                val message = result.message.ifBlank { "The server did not send this." }
                val earlier = previous?.afterRestart()
                if (earlier == null) {
                    clearQuietly()
                } else {
                    try {
                        store.save(earlier)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // The refused attempt's record remains and still asks for a confirmation.
                    }
                }
                NativeExactlyOnceSendOutcome.Rejected(message, earlier)
            }
            else -> {
                val maybe = attempt.copy(state = NativeSendAttemptState.MaybeSent, unknownReason = NativeSendUnknownReason.Unconfirmed)
                try {
                    store.save(maybe)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // The Prepared record remains and already reads as "may have been sent".
                }
                NativeExactlyOnceSendOutcome.MaybeSent(maybe)
            }
        }
    }

    /** The person discarded the draft or verified the outcome; the attempt is no longer open. */
    suspend fun resolve() = store.clear()

    private suspend fun clearQuietly() {
        try {
            store.clear()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A leftover record only asks for one extra confirmation; it never resends anything.
        }
    }
}

/** A stored [NativeSendAttemptState.Prepared] attempt found after a restart was interrupted. */
fun NativeSendAttempt.afterRestart(): NativeSendAttempt =
    if (state == NativeSendAttemptState.Prepared) copy(state = NativeSendAttemptState.MaybeSent, unknownReason = NativeSendUnknownReason.Interrupted) else this

/** The SHA-256 of the action and its exact request values, independent of map order. */
fun nativeSendFingerprint(request: NativeActionRequest.Submit): String {
    val canonical = buildString {
        append(request.action.id).append('\u0000')
        request.values.entries.sortedBy { it.key }.forEach { (key, value) -> append(key).append('\u0001').append(value).append('\u0000') }
    }
    return publicContentSha256(canonical.encodeToByteArray())
}

/** A 128-bit random hex token for attempt ids and idempotency keys. */
fun newNativeSendToken(): String = Random.Default.nextBytes(16).joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

private fun String.isAttemptToken(): Boolean = length in 16..128 && all { it in '0'..'9' || it in 'a'..'f' || it == '-' }

private const val MAX_TARGET_SUMMARY = 160
