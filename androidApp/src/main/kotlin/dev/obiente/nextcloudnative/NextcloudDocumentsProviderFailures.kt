package dev.obiente.nextcloudnative

import java.io.FileNotFoundException

internal inline fun <T> documentMutationCall(operation: () -> T): T = try {
    operation()
} catch (failure: DocumentWebDavException) {
    when (failure.error) {
        DocumentWebDavError.Authentication,
        DocumentWebDavError.Permission,
        -> throw SecurityException(failure.message, failure)
        DocumentWebDavError.NotFound -> throw FileNotFoundException(failure.message).also { it.initCause(failure) }
        DocumentWebDavError.AlreadyExists,
        DocumentWebDavError.Conflict,
        DocumentWebDavError.Locked,
        DocumentWebDavError.InsufficientStorage,
        DocumentWebDavError.TooLarge,
        DocumentWebDavError.Throttled,
        DocumentWebDavError.Server,
        -> throw IllegalStateException(failure.message, failure)
    }
}
