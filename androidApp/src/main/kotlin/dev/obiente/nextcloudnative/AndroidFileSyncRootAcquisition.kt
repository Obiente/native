package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.FileSyncLocalRoot
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The picker owns an IO scope; only continuation delivery runs on Main. */
internal fun acquireFileSyncRootForDelivery(
    scope: CoroutineScope,
    continuation: CancellableContinuation<FileSyncLocalRoot?>,
    acquire: () -> FileSyncLocalRoot,
    abandon: (String) -> Unit,
    mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) = scope.launch {
    if (!continuation.isActive) return@launch
    var acquired: FileSyncLocalRoot? = null
    var delivered = false
    try {
        val root = acquire().also { acquired = it }
        withContext(mainDispatcher) {
            resumeFileSyncRootSelection(continuation, root) { undelivered ->
                // Cancellation may arrive after this acquisition job completed.
                // The same owner retains cleanup on IO even if its parent is cancelled.
                scope.launch(NonCancellable) { reclaimUndeliveredFileSyncRoot(root.savedStateId ?: undelivered, abandon) }
            }
            delivered = true
        }
    } catch (cancelled: CancellationException) {
        continuation.cancel(cancelled)
        throw cancelled
    } catch (failure: Exception) {
        continuation.cancel(failure)
    } finally {
        val retained = acquired
        if (!delivered && retained != null) {
            withContext(NonCancellable) { reclaimUndeliveredFileSyncRoot(retained.savedStateId ?: retained.localRootId, abandon) }
        }
    }
}

internal fun reclaimUndeliveredFileSyncRoot(root: String, abandon: (String) -> Unit) {
    try {
        abandon(root)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Acquisition durably scheduled recovery before taking the grant. A failed
        // immediate cleanup remains owned by that worker and its persisted record.
    }
}
