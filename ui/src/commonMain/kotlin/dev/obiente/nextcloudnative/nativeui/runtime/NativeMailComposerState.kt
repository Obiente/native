package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.obiente.nextcloudnative.app.DynamicNativeMemoryCacheLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

internal sealed interface NativeMailSendPhase {
    data object Editing : NativeMailSendPhase

    data object Sending : NativeMailSendPhase

    /** The server answered that it did not send the message. Editing and sending again is safe. */
    data class Rejected(val message: String) : NativeMailSendPhase

    /**
     * [attempt] may have reached the server, but no answer confirmed it. Sending is not
     * idempotent, so the draft stays open, the contract's status read is consulted when it has
     * one, and another send needs an explicit confirmation.
     */
    data class Unknown(
        val attempt: NativeMailComposeDraft,
        val recovery: NativeMailSendRecovery,
        /** The durable attempt record, shown as evidence of what may have been sent. */
        val record: NativeSendAttempt? = null,
    ) : NativeMailSendPhase
}

/** Durable, account- and app-scoped storage for one composer. Implementations encrypt content. */
internal interface NativeMailDraftStore {
    suspend fun load(): NativeMailStoredComposer?

    suspend fun save(value: NativeMailStoredComposer)

    suspend fun clear()
}

/** What survives process death: the open draft and, after an unconfirmed send, what was sent. */
internal data class NativeMailStoredComposer(
    val draft: NativeMailComposeDraft,
    val unknownAttempt: NativeMailComposeDraft? = null,
    /** The exactly-once attempt record of [unknownAttempt]; absent in records saved before it existed. */
    val attempt: NativeSendAttempt? = null,
)

/**
 * Owns the open draft and the send state machine for one app of one account.
 *
 * The owner keeps one instance outside every screen and runs sends and status reads in [scope],
 * so closing the dialog, leaving the workspace, or recreating the activity never cancels a request
 * that may have left the device. With a [store], the draft is saved shortly after each edit, and an
 * attempt is recorded before its request starts, so after process death the draft returns with its
 * unconfirmed outcome instead of looking unsent. Without a [store] the draft stays in memory and
 * sending is refused, because an attempt that cannot be recorded durably is never sent. Message
 * content never enters saved instance state.
 */
@Stable
internal class NativeMailComposerState(
    private val scope: CoroutineScope? = null,
    private val store: NativeMailDraftStore? = null,
    private val saveDelayMillis: Long = DRAFT_SAVE_DELAY_MILLIS,
    clock: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) {
    var draft: NativeMailComposeDraft? by mutableStateOf(null)
        private set
    var phase: NativeMailSendPhase by mutableStateOf(NativeMailSendPhase.Editing)
        private set
    var notice: String? by mutableStateOf(null)
        private set

    /** Set while the device copy of the draft cannot be read or written. */
    var storageProblem: String? by mutableStateOf(null)
        private set

    /** True from creation by the retention until [restore] finishes; nothing is sent meanwhile. */
    private var restorePending by mutableStateOf(false)

    /**
     * True while the device copy could not be read. It may hold an unconfirmed attempt, so it is
     * neither overwritten nor bypassed: nothing is saved or sent until a later [restore] reads it
     * or the person discards the draft.
     */
    private var storedCopyUnreadable = false

    private val persistence = Mutex()
    private var pendingSave: Job? = null
    private var sendingAttempt: NativeMailComposeDraft? = null

    /** The open exactly-once attempt; it is stored in the same encrypted record as the draft. */
    private var attemptRecord: NativeSendAttempt? = null
    private val attempts = object : NativeSendAttemptStore {
        override suspend fun load(): NativeSendAttempt? = attemptRecord

        override suspend fun save(attempt: NativeSendAttempt) {
            // Without a durable store the attempt would not survive process death, so the send is
            // refused (AttemptNotRecorded) rather than sent with only an in-memory record.
            checkNotNull(store) { "No durable draft storage is available for the attempt." }
            val previous = attemptRecord
            attemptRecord = attempt
            pendingSave?.cancel()
            if (!persistNow()) {
                // Nothing was sent for this record, so it must not later look like an open attempt.
                attemptRecord = previous
                error("The attempt could not be recorded.")
            }
        }

        override suspend fun clear() {
            attemptRecord = null
        }
    }
    private val exactlyOnce = NativeExactlyOnceSend(attempts, clock)

    val sending: Boolean get() = phase == NativeMailSendPhase.Sending

    val checking: Boolean get() = (phase as? NativeMailSendPhase.Unknown)?.recovery == NativeMailSendRecovery.Checking

    val busy: Boolean get() = sending || checking

    /** True when the previous attempt's outcome is unknown and a resend must be confirmed. */
    val resendNeedsConfirmation: Boolean get() = phase is NativeMailSendPhase.Unknown

    /**
     * Whether Send is offered. After an unknown outcome it is offered only when the contract has
     * no status read, or when that read answered without showing the message as sent.
     */
    val sendAvailable: Boolean
        get() = !restorePending && when (val current = phase) {
            NativeMailSendPhase.Editing, is NativeMailSendPhase.Rejected -> true
            NativeMailSendPhase.Sending -> false
            is NativeMailSendPhase.Unknown -> current.recovery is NativeMailSendRecovery.Unavailable ||
                current.recovery is NativeMailSendRecovery.NotShownAsSent
        }

    /** Whether reading the attempt's status again can help. */
    val statusCheckAvailable: Boolean
        get() = when ((phase as? NativeMailSendPhase.Unknown)?.recovery) {
            NativeMailSendRecovery.NotChecked, is NativeMailSendRecovery.CheckFailed, is NativeMailSendRecovery.InProgress -> true
            else -> false
        }

    /** Marks that [restore] is about to run, so a send cannot start before the device copy is known. */
    fun expectRestore() {
        if (store != null) restorePending = true
    }

    /**
     * Restores a draft saved before the process ended. A draft with content opened meanwhile wins,
     * unless the device copy holds an unconfirmed attempt: that evidence is never replaced.
     */
    suspend fun restore() {
        try {
            restoreStored()
        } finally {
            restorePending = false
        }
    }

    private suspend fun restoreStored() {
        val store = store ?: return
        persistence.withLock {
            val stored = try {
                store.load()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Persistence fault boundary: the saved copy is unreadable; keep working in memory.
                storedCopyUnreadable = true
                storageProblem = "A draft saved on this device could not be read. Nothing is sent or saved until it can be read or you discard this draft."
                return
            }
            storedCopyUnreadable = false
            if (stored == null || busy) return
            val replacesOpenDraft = draft?.hasContent == true
            if (replacesOpenDraft && stored.unknownAttempt == null) return
            if (replacesOpenDraft) {
                notice = "An earlier message was not confirmed as sent and is shown again. Resolve it before writing a new message."
            }
            draft = stored.draft
            attemptRecord = stored.attempt?.afterRestart()?.takeIf { stored.unknownAttempt != null }
            phase = stored.unknownAttempt
                ?.let { NativeMailSendPhase.Unknown(it, NativeMailSendRecovery.NotChecked, attemptRecord) }
                ?: NativeMailSendPhase.Editing
        }
    }

    fun open(draft: NativeMailComposeDraft) {
        // An unconfirmed attempt stays open until it is resolved or explicitly discarded.
        if (busy || phase is NativeMailSendPhase.Unknown) return
        this.draft = draft
        phase = NativeMailSendPhase.Editing
        notice = null
        persistSoon()
    }

    fun update(transform: (NativeMailComposeDraft) -> NativeMailComposeDraft) {
        if (busy) return
        draft = draft?.let(transform)
        if (phase is NativeMailSendPhase.Rejected) phase = NativeMailSendPhase.Editing
        persistSoon()
    }

    fun discard() {
        if (busy) return
        draft = null
        attemptRecord = null
        phase = NativeMailSendPhase.Editing
        // Discarding is the explicit decision to give up whatever the unreadable copy held.
        storedCopyUnreadable = false
        persistSoon(immediately = true)
    }

    fun dismissNotice() {
        notice = null
    }

    /** Starts [send] in the owner's scope, so the request outlives the screen that asked for it. */
    fun launchSend(
        capability: NativeMailSendCapability,
        executor: NativeActionExecutor,
        confirmedResend: Boolean,
        onSent: () -> Unit,
    ) {
        scope?.launch { send(capability, executor, confirmedResend, onSent) }
    }

    /** Starts [checkOutcome] in the owner's scope. */
    fun launchStatusCheck(
        capability: NativeMailSendCapability,
        executor: NativeActionExecutor,
        onSent: () -> Unit,
    ) {
        scope?.launch { checkOutcome(capability, executor, onSent) }
    }

    /**
     * Sends the open draft once. [confirmedResend] must be true after an unknown outcome; the
     * caller asks the user first, because the earlier message may already have been delivered.
     */
    suspend fun send(
        capability: NativeMailSendCapability,
        executor: NativeActionExecutor,
        confirmedResend: Boolean = false,
        onSent: () -> Unit = {},
    ) {
        val current = draft ?: return
        if (busy || restorePending) return
        val unknown = phase as? NativeMailSendPhase.Unknown
        if (unknown != null && (!confirmedResend || !sendAvailable)) return
        val request = try {
            capability.request(current)
        } catch (failure: IllegalArgumentException) {
            if (unknown == null) phase = NativeMailSendPhase.Rejected(failure.message ?: "The message is not ready to send.")
            return
        }
        sendingAttempt = current
        phase = NativeMailSendPhase.Sending
        // The exactly-once send records the attempt, durable with the draft, before its request
        // can leave the device, sends once, and never repeats it on its own.
        val outcome = try {
            exactlyOnce.send(
                request = request,
                executor = executor,
                targetSummary = nativeMailTargetSummary(current),
                confirmedResend = unknown != null && confirmedResend,
                declaresIdempotencyKey = capability.action.idempotencyKeyHeader != null,
            )
        } catch (cancelled: CancellationException) {
            // The request may already be on its way; the outcome is unknown, never unsent.
            phase = NativeMailSendPhase.Unknown(current, NativeMailSendRecovery.NotChecked, attemptRecord?.afterRestart())
            sendingAttempt = null
            throw cancelled
        }
        sendingAttempt = null
        when (outcome) {
            is NativeExactlyOnceSendOutcome.Sent -> {
                complete(outcome.message?.takeIf(String::isNotBlank) ?: "Message sent.")
                onSent()
            }
            is NativeExactlyOnceSendOutcome.Rejected -> {
                val earlier = outcome.earlier
                if (unknown != null && earlier != null) {
                    // Only the resend was refused; the earlier message may still have been sent.
                    phase = unknown.copy(record = earlier)
                    notice = "The server refused sending again: ${outcome.message} The earlier message may still have been sent."
                } else {
                    phase = NativeMailSendPhase.Rejected(outcome.message)
                }
                persistSoon(immediately = true)
            }
            is NativeExactlyOnceSendOutcome.MaybeSent -> {
                phase = NativeMailSendPhase.Unknown(current, NativeMailSendRecovery.NotChecked, outcome.attempt)
                persistSoon(immediately = true)
                checkOutcome(capability, executor, onSent)
            }
            is NativeExactlyOnceSendOutcome.NotSent -> phase = unknown ?: NativeMailSendPhase.Rejected(
                when (outcome.reason) {
                    NativeSendRefusal.AttemptNotRecorded -> "This device could not keep a copy of the message, so it was not sent."
                    NativeSendRefusal.ResendNeedsConfirmation -> "Confirm before sending this message again."
                },
            )
        }
    }

    /**
     * Reads the status of an unconfirmed attempt through the contract's declared recovery read.
     * A read that shows the message as sent closes the draft; any other answer only updates what
     * the composer tells the user. Without a recovery read, the user checks their sent messages.
     */
    suspend fun checkOutcome(
        capability: NativeMailSendCapability,
        executor: NativeActionExecutor,
        onSent: () -> Unit = {},
    ) {
        val unknown = phase as? NativeMailSendPhase.Unknown ?: return
        if (unknown.recovery == NativeMailSendRecovery.Checking) return
        val reader = executor as? NativeResultRecoveryReader
        if (capability.action.resultRecoveryActionId == null || reader == null) {
            phase = unknown.copy(recovery = NativeMailSendRecovery.Unavailable)
            return
        }
        val request = try {
            capability.request(unknown.attempt)
        } catch (_: IllegalArgumentException) {
            phase = unknown.copy(recovery = NativeMailSendRecovery.CheckFailed("The app's send operation changed."))
            return
        }
        val checking = unknown.copy(recovery = NativeMailSendRecovery.Checking)
        phase = checking
        val read = try {
            reader.readResult(request)
        } catch (cancelled: CancellationException) {
            if (phase === checking) phase = unknown.copy(recovery = NativeMailSendRecovery.NotChecked)
            throw cancelled
        } catch (_: Exception) {
            // Transport fault boundary: a failed status read tells nothing about the send.
            NativeResultRecoveryRead.Failed("The status could not be read.")
        }
        // A discarded or replaced draft no longer receives this answer.
        if (phase !== checking) return
        when (val verdict = capability.recoveryVerdict(unknown.attempt, read)) {
            is NativeMailSendRecovery.ShownAsSent -> {
                if (draft != null && draft != unknown.attempt) {
                    // The earlier message is resolved. What was written since was never sent.
                    attemptRecord = null
                    phase = NativeMailSendPhase.Editing
                    notice = "${verdict.description()} Your changes since then are kept as a new draft."
                    pendingSave?.cancel()
                    persistNow()
                } else {
                    complete(verdict.description())
                }
                onSent()
            }
            else -> phase = unknown.copy(recovery = verdict)
        }
    }

    private suspend fun complete(message: String) {
        draft = null
        attemptRecord = null
        phase = NativeMailSendPhase.Editing
        notice = message
        pendingSave?.cancel()
        persistNow()
    }

    private fun persistSoon(immediately: Boolean = false) {
        val scope = scope ?: return
        if (store == null) return
        pendingSave?.cancel()
        pendingSave = scope.launch {
            if (!immediately) delay(saveDelayMillis)
            persistNow()
        }
    }

    /** Writes the current state, or clears the device copy when nothing needs to survive. */
    private suspend fun persistNow(): Boolean {
        val store = store ?: return true
        if (storedCopyUnreadable) return false
        return persistence.withLock {
            val snapshot = snapshot()
            try {
                if (snapshot == null) store.clear() else store.save(snapshot)
                storageProblem = null
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Persistence fault boundary: report it; the draft stays open in memory.
                storageProblem = "This draft could not be saved on this device."
                false
            }
        }
    }

    private fun snapshot(): NativeMailStoredComposer? {
        val current = draft ?: return null
        val unknownAttempt = when (val now = phase) {
            NativeMailSendPhase.Sending -> sendingAttempt
            is NativeMailSendPhase.Unknown -> now.attempt
            else -> null
        }
        if (unknownAttempt == null && !current.hasContent) return null
        return NativeMailStoredComposer(current, unknownAttempt, attemptRecord?.takeIf { unknownAttempt != null })
    }
}

/**
 * Keeps one [NativeMailComposerState] per app for one account, outside every screen. The host's
 * account owner creates it with a process-lifetime [scope] and [close]s it when the account is
 * signed out or removed, which is the only event that cancels an unfinished send.
 */
class NativeMailComposerRetention internal constructor(
    private val scope: CoroutineScope,
    private val storeFor: (appId: String) -> NativeMailDraftStore?,
) {
    private val lock = DynamicNativeMemoryCacheLock()
    private val states = mutableMapOf<String, NativeMailComposerState>()

    internal fun stateFor(appId: String): NativeMailComposerState = lock.withLock {
        states.getOrPut(appId) {
            NativeMailComposerState(scope, storeFor(appId)).also { state ->
                state.expectRestore()
                scope.launch { state.restore() }
            }
        }
    }

    internal fun close() {
        lock.withLock { states.clear() }
        scope.coroutineContext[Job]?.cancel()
    }
}

private const val DRAFT_SAVE_DELAY_MILLIS = 600L

/** A content-free description of who a message goes to, kept in the attempt record. */
internal fun nativeMailTargetSummary(draft: NativeMailComposeDraft): String {
    val count = listOf(draft.to, draft.cc, draft.bcc).sumOf { parseNativeMailRecipients(it).first.size }
    return if (count == 1) "1 recipient" else "$count recipients"
}

/** Evidence shown while an attempt may have been sent: which attempt, and why it is unknown. */
internal fun NativeSendAttempt.evidence(): String = buildString {
    append("Attempt ").append(attemptId.take(8)).append(" to ").append(targetSummary)
    append(if (unknownReason == NativeSendUnknownReason.Interrupted) " was interrupted." else " was not confirmed by the server.")
    if (idempotencyKey != null) append(" The app accepts a repeat of the same message as one delivery.")
}
