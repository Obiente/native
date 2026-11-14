package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException

/** What became of one create handed to [NativeCreateAttemptGuard.submit]. */
internal sealed interface NativeCreateOutcome {
    /** The request ran; [confirmed] is true only when the server confirmed it. */
    data class Ran(val confirmed: Boolean) : NativeCreateOutcome

    /** Nothing was sent. [message] says why, or is null when another create is already running. */
    data class NotSent(val message: String?) : NativeCreateOutcome
}

/**
 * Keeps a create that cannot safely be repeated from being repeated by accident. Adding a row or
 * an expense is not idempotent, and the new record may be outside the part of the list that is
 * shown, so a lost answer cannot always be settled by looking.
 *
 * The attempt is recorded in [attempts] before its request leaves the device. An attempt whose
 * result was never learned stays on record across leaving the screen and restarting the app, and
 * the next create is held back once with an explanation; pressing again is the explicit decision
 * to add another. Without a store the same holds for as long as this guard lives.
 */
@Stable
internal class NativeCreateAttemptGuard(
    private val attempts: NativeSendAttemptStore? = null,
    private val clock: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) {
    /** An earlier create that may have been applied. */
    var unconfirmed by mutableStateOf<NativeSendAttempt?>(null)
        private set

    /** True until the stored attempt has been read; nothing is created meanwhile. */
    var restoring by mutableStateOf(attempts != null)
        private set

    private var unreadable = false
    private var warned = false
    private var running = false

    /** Reads the attempt recorded before this screen existed. */
    suspend fun restore() {
        val store = attempts ?: return
        try {
            unconfirmed = store.load()?.afterRestart()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Persistence fault boundary: an unreadable record may be a record already added.
            unreadable = true
        }
        restoring = false
    }

    suspend fun submit(
        request: NativeActionRequest.Submit,
        session: NativeEditorWriteSession,
        executor: NativeActionExecutor,
        onRefreshRequested: () -> Unit,
    ): NativeCreateOutcome {
        if (running || restoring || !session.canSubmit) return NativeCreateOutcome.NotSent(null)
        val earlier = unconfirmed
        if ((earlier != null || unreadable) && !warned) {
            warned = true
            return NativeCreateOutcome.NotSent(
                "An earlier attempt may already have been added, and it may be outside what is shown here. " +
                    "Check first. Press again to add another anyway.",
            )
        }
        // Taken before the record is written: storage suspends, and a second press must not start
        // another attempt while this one is still on its way.
        running = true
        try {
            val attempt = NativeSendAttempt(newNativeSendToken(), clock(), "1 record", nativeSendFingerprint(request))
            try {
                attempts?.save(attempt)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return NativeCreateOutcome.NotSent("This device could not keep a record of the new entry, so it was not added.")
            }
            unreadable = false
            val confirmed = try {
                session.submit(executor, request, NativeEditorWriteRetry.NonIdempotent, onRefreshRequested)
            } catch (cancelled: CancellationException) {
                // The request may be on its way; the stored record already reads as unconfirmed.
                unconfirmed = attempt.afterRestart()
                throw cancelled
            }
            val status = session.status
            settle(
                when {
                    confirmed -> null
                    // A refusal answers only this request; an earlier attempt stays unconfirmed.
                    status is NativeEditorWriteStatus.Rejected || status is NativeEditorWriteStatus.Changed -> earlier
                    else -> attempt.copy(state = NativeSendAttemptState.MaybeSent, unknownReason = NativeSendUnknownReason.Unconfirmed)
                },
            )
            return NativeCreateOutcome.Ran(confirmed)
        } finally {
            running = false
        }
    }

    private suspend fun settle(remaining: NativeSendAttempt?) {
        unconfirmed = remaining
        warned = false
        val store = attempts ?: return
        try {
            if (remaining == null) store.clear() else store.save(remaining)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A record left behind only asks for one more decision; it never creates anything.
        }
    }
}

/**
 * The guard for creates of [surface], such as one table's rows or one project's expenses. With
 * account storage from [LocalNativeSendAttemptStores] it outlives this composition.
 */
@Composable
internal fun rememberNativeCreateAttemptGuard(surface: String): NativeCreateAttemptGuard {
    val stores = LocalNativeSendAttemptStores.current
    val guard = remember(surface, stores) { NativeCreateAttemptGuard(stores?.invoke("create:$surface")) }
    LaunchedEffect(guard) { guard.restore() }
    return guard
}
