package dev.obiente.nextcloudnative.contracts

import java.io.IOException
import java.security.GeneralSecurityException
import java.util.concurrent.CancellationException
import okhttp3.Response

/**
 * Stable, non-secret classification of why contract acquisition could not complete.
 *
 * Callers derive product copy and diagnostics from this value. Exception messages and class
 * names are local diagnostics only and never become a stable identifier.
 */
enum class ContractAcquisitionFailureKind {
    /** No response arrived from a contract source: DNS, TLS, connection, or timeout failure. */
    Network,

    /** The App Store or an App Store-linked source answered with a non-success HTTP status. */
    SourceUnavailable,

    /** A package signature, certificate, identity, or source origin check rejected the source. */
    VerificationFailed,

    /** The server-reported app ID or version cannot select a release safely. */
    InvalidRequest,

    /** This runtime could not load or initialize acquisition code. */
    RuntimeIncompatible,

    /** Any other failure. */
    Unexpected,
}

/** A contract source answered, but not with the requested resource. */
class ContractSourceHttpException(val status: Int, message: String) : IllegalStateException(message)

/** A package or source failed verification. The original failure stays available as the cause. */
class ContractSourceVerificationException(message: String, cause: Throwable? = null) :
    SecurityException(message, cause)

/** The app ID or version supplied for release selection is malformed. */
class ContractAcquisitionRequestException(message: String) : IllegalArgumentException(message)

/**
 * Classifies a failure thrown by [SignedAppStoreContractAcquirer.acquire].
 *
 * Typed acquisition exceptions win over their supertypes. A [LinkageError] means acquisition code
 * could not load or initialize on this runtime, for example a static initializer that the Android
 * runtime rejects; it is reported as a client fault instead of a contract or network problem.
 */
fun classifyContractAcquisitionFailure(failure: Throwable): ContractAcquisitionFailureKind = when (failure) {
    is ContractSourceHttpException -> ContractAcquisitionFailureKind.SourceUnavailable
    is ContractSourceVerificationException -> ContractAcquisitionFailureKind.VerificationFailed
    is ContractAcquisitionRequestException -> ContractAcquisitionFailureKind.InvalidRequest
    is IOException -> ContractAcquisitionFailureKind.Network
    is GeneralSecurityException -> ContractAcquisitionFailureKind.VerificationFailed
    is LinkageError -> ContractAcquisitionFailureKind.RuntimeIncompatible
    else -> ContractAcquisitionFailureKind.Unexpected
}

internal inline fun requireValidRequest(valid: Boolean, message: () -> String) {
    if (!valid) throw ContractAcquisitionRequestException(message())
}

internal inline fun requireVerifiedSource(verified: Boolean, message: () -> String) {
    if (!verified) throw ContractSourceVerificationException(message())
}

internal inline fun requireSuccessfulSource(response: Response, message: () -> String) {
    if (!response.isSuccessful) throw ContractSourceHttpException(response.code, message())
}

/**
 * Package and identity checks throw several exception types; the acquisition boundary is one kind.
 * Cancellation is control flow, never a verification result, so it is returned unchanged.
 */
internal fun Exception.asSourceVerificationFailure(appId: String): RuntimeException = when (this) {
    is CancellationException -> this
    is ContractSourceVerificationException -> this
    else -> ContractSourceVerificationException("The signed $appId package was rejected.", this)
}
