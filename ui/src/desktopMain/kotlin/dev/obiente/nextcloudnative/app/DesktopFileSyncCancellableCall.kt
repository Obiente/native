package dev.obiente.nextcloudnative.app

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import kotlinx.coroutines.CancellationException
import okhttp3.Call

/** Cancels a blocking desktop sync request promptly when the pair is paused or stopped. */
internal fun <T> executeDesktopFileSyncCancellableCall(
    call: Call,
    shouldContinue: () -> Boolean,
    consume: (Call) -> T,
): T {
    fun cancellation(cause: Throwable? = null): CancellationException =
        CancellationException("Desktop file sync paused.").also { cancelled ->
            cause?.let(cancelled::initCause)
        }

    if (!shouldContinue()) throw cancellation()
    val finished = AtomicBoolean(false)
    val watcher = Thread({
        while (!finished.get() && shouldContinue()) {
            LockSupport.parkNanos(CANCELLATION_POLL_NANOS)
        }
        if (!finished.get()) call.cancel()
    }, "nextcloud-desktop-sync-call-cancellation").apply {
        isDaemon = true
        start()
    }
    return try {
        consume(call).also {
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
