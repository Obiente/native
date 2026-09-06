package dev.obiente.nextcloudnative

import kotlinx.coroutines.CancellationException

internal class AndroidLocalUploadCapabilityUnavailableException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal class AndroidLocalUploadCapabilityReadException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal class AndroidLocalUploadCapabilityMalformedException(
    message: String,
    cause: Throwable? = null,
    val cleanupPermissionIdentity: String? = null,
    val grantPreExisting: Boolean? = null,
) : IllegalStateException(message, cause)

internal fun requireDurableUploadCapabilityReady(phase: CapabilityPhase) {
    if (phase == CapabilityPhase.OwnershipCheckPending) {
        throw AndroidLocalUploadCapabilityReadException(
            "The local file selection ownership check is still pending.",
        )
    }
    if (!isDurableUploadCapabilityReady(phase)) {
        throw AndroidLocalUploadCapabilityUnavailableException(
            "The local file selection is pending capability cleanup.",
        )
    }
}

internal inline fun readAndroidLocalUploadCapabilityPreference(read: () -> String?): String? = try {
    read()
} catch (failure: ClassCastException) {
    throw AndroidLocalUploadCapabilityMalformedException(
        "The local file selection metadata has an invalid stored type.",
        failure,
    )
}

internal inline fun decryptAndroidLocalUploadCapability(decrypt: () -> String): String = try {
    decrypt()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: InvalidSessionCiphertextException) {
    throw AndroidLocalUploadCapabilityMalformedException(
        "The encrypted local file selection metadata is invalid.",
        failure,
    )
}

internal inline fun <Result> readAndroidLocalUploadCapability(load: () -> Result): Result = try {
    load()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: AndroidLocalUploadCapabilityMalformedException) {
    throw AndroidLocalUploadCapabilityUnavailableException(
        "The local file selection metadata is invalid.",
        failure,
    )
} catch (failure: Exception) {
    throw AndroidLocalUploadCapabilityReadException(
        "The local file selection metadata could not be read.",
        failure,
    )
}
