package dev.obiente.nextcloudnative.app

import java.util.concurrent.TimeUnit

internal data class WindowsCloudFilesMutationState(
    val pendingWritebackCount: Int,
    val failedWritebackCount: Int,
    val pathOperationCount: Int,
    val queuedPathOperationCount: Int,
    val destructiveCallbackCount: Int,
    val pendingLocalChangeCount: Int = 0,
    val deferredLocalChangeCount: Int = 0,
) {
    val idle: Boolean
        get() = pendingWritebackCount == 0 && pathOperationCount == 0 &&
            queuedPathOperationCount == 0 && destructiveCallbackCount == 0 &&
            pendingLocalChangeCount == 0 && deferredLocalChangeCount == 0

    val writebackFailedWithoutRetry: Boolean
        get() = failedWritebackCount > 0 && pathOperationCount == 0 && queuedPathOperationCount == 0
}

internal class WindowsCloudFilesRemovalQuiescence(
    private val pauseCallbacks: () -> Boolean,
    private val mutationState: () -> WindowsCloudFilesMutationState,
    private val resumeCallbacks: () -> Unit,
    private val nanoTime: () -> Long = System::nanoTime,
    private val awaitProgress: () -> Unit = { Thread.sleep(POLL_MILLIS) },
) {
    fun tryQuiesce(timeoutSeconds: Long): Boolean {
        require(timeoutSeconds > 0L)
        try {
            if (!pauseCallbacks()) return false
            val deadline = nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
            var state = mutationState()
            while (!state.idle && !state.writebackFailedWithoutRetry && nanoTime() < deadline) {
                awaitProgress()
                state = mutationState()
            }
            check(state.failedWritebackCount == 0) {
                "Local edits in the Windows Cloud Files root could not be uploaded safely."
            }
            check(state.idle) {
                "Timed out while uploading local edits and finishing Windows Cloud Files operations."
            }
            return true
        } catch (failure: Throwable) {
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            runCatching(resumeCallbacks).exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    private companion object {
        const val POLL_MILLIS = 25L
    }
}

internal fun awaitWindowsCloudFilesPathOperationQuiescence(
    deadline: Long,
    mutationState: () -> WindowsCloudFilesMutationState,
) {
    var state = mutationState()
    while (
        (state.destructiveCallbackCount > 0 || state.pathOperationCount > 0 || state.queuedPathOperationCount > 0) &&
        System.nanoTime() < deadline
    ) {
        Thread.sleep(25L)
        state = mutationState()
    }
    check(state.destructiveCallbackCount == 0 && state.pathOperationCount == 0 && state.queuedPathOperationCount == 0) {
        "Timed out while quiescing callbacks and local edits before Windows Cloud Files recovery."
    }
}

internal fun awaitWindowsCloudFilesWritebackRecovery(
    deadline: Long,
    mutationState: () -> WindowsCloudFilesMutationState,
) {
    var state = mutationState()
    while (
        (state.pendingWritebackCount > 0 || state.pathOperationCount > 0 || state.queuedPathOperationCount > 0) &&
        !state.writebackFailedWithoutRetry &&
        System.nanoTime() < deadline
    ) {
        Thread.sleep(25L)
        state = mutationState()
    }
    check(state.failedWritebackCount == 0) {
        "Local edits in the legacy Windows Cloud Files root could not be uploaded safely."
    }
    check(state.pendingWritebackCount == 0 && state.pathOperationCount == 0 && state.queuedPathOperationCount == 0) {
        "Timed out while uploading local edits from the legacy Windows Cloud Files root."
    }
}

internal class AtomicLongState {
    @Volatile private var value: Long = 0L

    @Synchronized fun get(): Long = value

    @Synchronized fun set(next: Long) {
        value = next
    }

    @Synchronized fun compareAndSet(expected: Long, next: Long): Boolean {
        if (value != expected) return false
        value = next
        return true
    }
}

internal fun connectWindowsCloudFilesWithRegistrationRecovery(
    root: java.nio.file.Path,
    callbacks: WindowsCloudFilesCallbacks,
    api: WindowsCloudFilesApi,
    recoverRegistration: () -> Unit,
): Long = try {
    api.connect(root, callbacks)
} catch (firstFailure: WindowsCloudFilesOperationException) {
    if (!isWindowsCloudFilesRegistrationMissingResult(firstFailure.hResult)) throw firstFailure
    api.unregisterSyncRoot(root)
    recoverRegistration()
    try {
        api.connect(root, callbacks)
    } catch (retryFailure: Throwable) {
        retryFailure.addSuppressed(firstFailure)
        throw retryFailure
    }
}
