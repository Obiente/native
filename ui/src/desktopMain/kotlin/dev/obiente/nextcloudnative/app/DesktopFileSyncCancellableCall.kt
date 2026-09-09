package dev.obiente.nextcloudnative.app

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlinx.coroutines.CancellationException
import okhttp3.Call
import okhttp3.Response

/** Cancels the active call, including redirected response consumption, when sync stops. */
internal fun <T> withDesktopFileSyncCallCancellation(
    shouldContinue: () -> Boolean,
    execute: (executeCall: (Call) -> Response) -> T,
): T {
    fun cancellation(cause: Throwable? = null): CancellationException =
        CancellationException("Desktop file sync paused.").also { cancelled ->
            cause?.let(cancelled::initCause)
        }

    if (!shouldContinue()) throw cancellation()
    val finished = AtomicBoolean(false)
    val activeCall = AtomicReference<Call?>()
    val watcher = Thread({
        while (!finished.get() && shouldContinue()) {
            LockSupport.parkNanos(CANCELLATION_POLL_NANOS)
        }
        if (!finished.get()) activeCall.get()?.cancel()
    }, "nextcloud-desktop-sync-call-cancellation").apply {
        isDaemon = true
        start()
    }
    return try {
        execute { call ->
            activeCall.set(call)
            if (!shouldContinue()) {
                call.cancel()
                throw cancellation()
            }
            call.execute()
        }.also {
            if (!shouldContinue()) throw cancellation()
        }
    } catch (failure: IOException) {
        if (!shouldContinue()) throw cancellation(failure)
        throw failure
    } finally {
        finished.set(true)
        watcher.interrupt()
    }
}

private const val CANCELLATION_POLL_NANOS = 25L * 1_000_000L
