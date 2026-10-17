package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

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
 * Opens a range session for [plan] only after composition commits and closes it on disposal.
 *
 * Opening registers an account resource, so it must never run in a `remember` initializer: an
 * abandoned composition would leave the session registered with nothing to close it.
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
    DisposableEffect(wanted, attempt, session, userId, services) {
        val opened = wanted?.let { openOrNull(it, services, session, userId) }
        slot = opened?.let(NativeVideoRangeSourceSlot::Open) ?: NativeVideoRangeSourceSlot.Unavailable
        onDispose { opened?.close() }
    }
    return slot
}

private fun openOrNull(
    plan: NativeVideoCompatibilityRangePlan,
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    userId: String,
): NativeVideoRangeSource? = try {
    plan.openRangeSource(services = services, session = session, userId = userId)
} catch (_: IllegalArgumentException) {
    // The record has no safe size, ETag, or path to pin a range read to.
    null
} catch (_: IllegalStateException) {
    // The account session is closing.
    null
}
