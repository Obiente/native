package dev.obiente.nextcloudnative

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Keep ownership when prompt cancellation discards a successfully persisted IO result. */
internal suspend fun registerAndroidExternalFileHandoff(
    register: () -> AndroidExternalFileHandoffRecord,
    revoke: (AndroidExternalFileHandoffRecord) -> Unit,
    discardStagedCopy: () -> Unit,
): AndroidExternalFileHandoffRecord {
    var registered: AndroidExternalFileHandoffRecord? = null
    return try {
        withContext(Dispatchers.IO) { register().also { registered = it } }
    } catch (cancelled: CancellationException) {
        withContext(NonCancellable + Dispatchers.IO) {
            try {
                registered?.let(revoke)
            } catch (cleanup: Exception) {
                retainRegistrationCleanupFailure(cancelled, cleanup)
            }
            try {
                discardStagedCopy()
            } catch (cleanup: Exception) {
                retainRegistrationCleanupFailure(cancelled, cleanup)
            }
        }
        throw cancelled
    }
}

private fun retainRegistrationCleanupFailure(cancelled: CancellationException, cleanup: Exception) {
    // Coroutine recovery may unwrap a copied cancellation on the next suspension boundary.
    // Keep diagnostics on its original cancellation too, without replacing control flow.
    val chain = generateSequence<Throwable>(cancelled) { it.cause }.take(8)
        .filterIsInstance<CancellationException>().distinct().toList()
    if (chain.any { it === cleanup }) return
    chain.forEach { if (it.suppressed.none { previous -> previous === cleanup }) it.addSuppressed(cleanup) }
}
