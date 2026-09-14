package dev.obiente.nextcloudnative

import android.security.keystore.KeyPermanentlyInvalidatedException
import java.security.InvalidAlgorithmParameterException
import javax.crypto.BadPaddingException
import javax.crypto.IllegalBlockSizeException

// Only evidence of invalid ciphertext or an irreversibly invalid key is permanent.
// Provider, I/O and keystore availability failures retain the existing durable retry.
internal fun durableUploadQueueDecryptionDisposition(failure: Exception): DurableUploadQueueRecoveryDisposition {
    var cause: Throwable? = failure
    repeat(8) {
        when (cause) {
            is KeyPermanentlyInvalidatedException,
            is BadPaddingException,
            is IllegalBlockSizeException,
            is InvalidAlgorithmParameterException,
            is IllegalArgumentException,
            -> return DurableUploadQueueRecoveryDisposition.Quarantine
        }
        cause = cause?.cause
    }
    return DurableUploadQueueRecoveryDisposition.Retry
}

internal suspend fun <Result> withDurableUploadQueueRecovery(
    onRetry: () -> Result,
    onQuarantine: () -> Result,
    action: suspend () -> Result,
): Result = try {
    action()
} catch (failure: AndroidDurableMultipartUploadRecoveryException) {
    when (failure.disposition) {
        DurableUploadQueueRecoveryDisposition.Retry -> onRetry()
        DurableUploadQueueRecoveryDisposition.Quarantine -> onQuarantine()
    }
}
