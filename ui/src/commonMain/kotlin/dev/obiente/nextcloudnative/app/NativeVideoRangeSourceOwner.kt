package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext

/** The seekable source a video player reads from, once the viewer has committed to showing it. */
internal sealed interface NativeVideoRangeSourceSlot {
    /** A source is needed but has not been opened yet; the player should wait. */
    data object Opening : NativeVideoRangeSourceSlot

    /** No source applies, or it could not be opened; the player reports its own failure. */
    data object Unavailable : NativeVideoRangeSourceSlot

    data class Open(val source: NativeVideoRangeSource) : NativeVideoRangeSourceSlot

    val sourceOrNull: NativeVideoRangeSource?
        get() = (this as? Open)?.source
}

/**
 * Opens a range session for [plan] only after composition commits and closes it when the viewer
 * leaves or the inputs change.
 *
 * Opening registers an account resource and can wait for an account operation lock, so it never
 * runs in a `remember` initializer or on the UI thread. The open itself is not cancellable, so a
 * session that finishes opening after the viewer left is closed instead of leaked.
 */
@Composable
internal fun rememberNativeVideoRangeSource(
    plan: NativeVideoCompatibilityRangePlan?,
    needed: Boolean,
    attempt: Int,
    session: NextcloudSession,
    userId: String,
    services: NextcloudPlatformServices,
): NativeVideoRangeSourceSlot {
    val wanted = plan?.takeIf { needed }
    var slot by remember(wanted, attempt, session, userId, services) {
        mutableStateOf(if (wanted == null) NativeVideoRangeSourceSlot.Unavailable else NativeVideoRangeSourceSlot.Opening)
    }
    LaunchedEffect(wanted, attempt, session, userId, services) {
        if (wanted == null) return@LaunchedEffect
        // Assigned inside the block: withContext discards its result when it resumes into a
        // cancelled effect, so the finally must see the session even then.
        var opened: NativeVideoRangeSource? = null
        try {
            withContext(NonCancellable + videoRangeOpenDispatcher) {
                opened = openOrNull(wanted, services, session, userId)
            }
            slot = opened?.let(NativeVideoRangeSourceSlot::Open) ?: NativeVideoRangeSourceSlot.Unavailable
            awaitCancellation()
        } finally {
            opened?.close()
        }
    }
    return slot
}

/** Where blocking range-session opens run: the platform's IO pool. */
internal expect val videoRangeOpenDispatcher: CoroutineDispatcher

private fun openOrNull(
    plan: NativeVideoCompatibilityRangePlan,
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    userId: String,
): NativeVideoRangeSource? = try {
    plan.openRangeSource(services = services, session = session, userId = userId)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    // UI fault boundary: an unsafe record, a closing account session, or a platform reporting a
    // changed or removed account (Android throws FileNotFoundException) leaves nothing to stream.
    // The player then reports a typed playback failure instead of crashing the viewer.
    null
}
