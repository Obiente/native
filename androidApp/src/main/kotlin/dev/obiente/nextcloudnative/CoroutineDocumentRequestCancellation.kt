package dev.obiente.nextcloudnative

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive

@OptIn(InternalCoroutinesApi::class)
internal class CoroutineDocumentRequestCancellation(
    private val job: Job,
) : DocumentRequestCancellation, AutoCloseable {
    private val cancelAction = AtomicReference<(() -> Unit)?>(null)
    private val completion = job.invokeOnCompletion(
        onCancelling = true,
        invokeImmediately = true,
    ) { failure ->
        if (failure != null) cancelAction.getAndSet(null)?.invoke()
    }

    override fun throwIfCancelled() {
        job.ensureActive()
    }

    override fun setOnCancelAction(action: (() -> Unit)?) {
        cancelAction.set(action)
        if (!job.isActive) cancelAction.getAndSet(null)?.invoke()
    }

    override fun close() {
        cancelAction.set(null)
        completion.dispose()
    }
}
