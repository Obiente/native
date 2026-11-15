package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.runtime.NativeActionExecutionResult
import dev.obiente.nextcloudnative.nativeui.runtime.NativeActionFailureOutcome

/*
 * OCS failures reported inside an HTTP 2xx response. Only a status code that means the server
 * refused the request is a rejection; a server error, an unknown code, or metadata that contradicts
 * itself may hide an applied write, so its outcome is unknown and nothing may resend it unconfirmed.
 *
 * OCS v1 codes: 100 is success; 101 to 199 are app-defined failures for invalid input, a missing
 * target, or a refused permission; 996 is a server error, 997 unauthorised, 998 not found, and 999
 * an unknown error. OCS v2 reports HTTP status codes in the envelope.
 */

/** Whether an OCS status code that is not a success means the server refused the request. */
internal fun dynamicOcsFailureOutcome(statusCode: Long): NativeActionFailureOutcome = when (statusCode) {
    in 400L..499L, in 101L..199L, OCS_V1_UNAUTHORISED, OCS_V1_NOT_FOUND -> NativeActionFailureOutcome.Rejected
    else -> NativeActionFailureOutcome.Unknown
}

internal fun dynamicOcsFailure(actionLabel: String, statusCode: Long, safeMessage: String?): NativeActionExecutionResult.Failure =
    when (val outcome = dynamicOcsFailureOutcome(statusCode)) {
        NativeActionFailureOutcome.Rejected -> NativeActionExecutionResult.Failure(
            message = safeMessage?.let { message -> "The server rejected $actionLabel: $message" }
                ?: "The OCS endpoint rejected $actionLabel.",
            outcome = outcome,
        )
        NativeActionFailureOutcome.Unknown -> NativeActionExecutionResult.Failure(
            message = "The server reported an error for $actionLabel, so it may or may not have been applied.",
            outcome = outcome,
        )
    }

private const val OCS_V1_UNAUTHORISED = 997L
private const val OCS_V1_NOT_FOUND = 998L
