package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal enum class MailRecipientField { To, Cc, Bcc }

internal sealed interface MailSendPhase {
    data object Editing : MailSendPhase
    data object Storing : MailSendPhase

    /** Held in the Outbox until [sendAtEpochSeconds]; it can still be undone. */
    data class Undoable(val outboxId: Long, val sendAtEpochSeconds: Long) : MailSendPhase
    data object Sending : MailSendPhase
    data class Scheduled(val sendAtEpochSeconds: Long) : MailSendPhase
    data object Sent : MailSendPhase

    /** Waiting in the Outbox; Nextcloud sends it on its own schedule. */
    data object Queued : MailSendPhase

    /** A definite problem. [outboxId] is set while the server still holds the message. */
    data class NeedsAttention(
        val message: String,
        val outboxId: Long? = null,
        val canSendAnyway: Boolean = false,
        val canRetrySend: Boolean = false,
    ) : MailSendPhase

    /** The result could not be confirmed; nothing is retried until the server state is checked. */
    data class Unknown(val message: String, val mayAlreadyBeSent: Boolean = false) : MailSendPhase
}

internal data class MailComposerUiState(
    val draft: MailComposeDraft,
    val phase: MailSendPhase = MailSendPhase.Editing,
    val uploading: List<String> = emptyList(),
    val attachmentNotice: String? = null,
    val suggestions: List<MailAutocompleteMatch> = emptyList(),
    val suggestionField: MailRecipientField? = null,
    val persistenceNotice: String? = null,
    val savingServerDraft: Boolean = false,
    val notice: String? = null,
) {
    val editable: Boolean
        get() = phase == MailSendPhase.Editing || (phase is MailSendPhase.NeedsAttention && phase.outboxId == null)

    val finished: Boolean get() = phase == MailSendPhase.Sent || phase == MailSendPhase.Queued || phase is MailSendPhase.Scheduled
}

/**
 * Owns one composer: autosave, recipients, attachments, and sending.
 *
 * The draft is saved to the scoped draft store as it changes, so it survives process death. A
 * send first records [MailComposeDraft.storeAttemptSendAt], then stores the message in Mail's
 * Outbox. Undo removes it from the Outbox before its send time. Every unknown outcome is checked
 * against the Outbox; the controller never repeats a store or a send by itself.
 */
internal class MailComposerController(
    private val scope: CoroutineScope,
    private val transport: MailTransport,
    private val store: MailComposeDraftStore?,
    private val account: (Long) -> MailAccount?,
    private val chooseLocalFile: suspend () -> LocalUploadSelectionResult,
    private val releaseLocalFile: (LocalUploadFile) -> Unit,
    private val nowEpochSeconds: () -> Long,
    initial: MailComposeDraft,
    private val undoDelay: MailUndoDelay = MailUndoDelay.Medium,
    private val wait: suspend (Long) -> Unit = { millis -> delay(millis) },
) {
    private val sender = MailSender(transport)

    var state by mutableStateOf(MailComposerUiState(draft = initial))
        private set

    private var persistJob: Job? = null
    private var suggestionJob: Job? = null
    private var undoJob: Job? = null

    init {
        initial.outboxMessageId?.let { outboxId ->
            state = state.copy(phase = MailSendPhase.Unknown("This message was handed to the Outbox earlier. Check its status before sending it again."))
            checkSendStatus(outboxId)
        }
    }

    val problems: List<String> get() = mailComposeProblems(state.draft, account(state.draft.accountId))

    fun update(change: (MailComposeDraft) -> MailComposeDraft) {
        if (!state.editable) return
        state = state.copy(draft = change(state.draft))
        schedulePersist()
    }

    fun addRecipient(field: MailRecipientField, recipient: MailRecipient) = update { draft ->
        val all = draft.recipients.map { it.email.lowercase() }
        if (recipient.email.lowercase() in all) draft else when (field) {
            MailRecipientField.To -> draft.copy(to = draft.to + recipient)
            MailRecipientField.Cc -> draft.copy(cc = draft.cc + recipient)
            MailRecipientField.Bcc -> draft.copy(bcc = draft.bcc + recipient)
        }
    }.also { state = state.copy(suggestions = emptyList(), suggestionField = null) }

    fun removeRecipient(field: MailRecipientField, recipient: MailRecipient) = update { draft ->
        when (field) {
            MailRecipientField.To -> draft.copy(to = draft.to - recipient)
            MailRecipientField.Cc -> draft.copy(cc = draft.cc - recipient)
            MailRecipientField.Bcc -> draft.copy(bcc = draft.bcc - recipient)
        }
    }

    /** Adds every complete address typed into a field and returns the text that is not an address yet. */
    fun commitTypedRecipients(field: MailRecipientField, text: String): String {
        val (valid, rest) = parseMailRecipientInput(text)
        valid.forEach { addRecipient(field, it) }
        return rest.joinToString(", ")
    }

    fun suggest(field: MailRecipientField, term: String) {
        suggestionJob?.cancel()
        val trimmed = term.trim()
        if (trimmed.length < MAIL_AUTOCOMPLETE_MINIMUM) {
            state = state.copy(suggestions = emptyList(), suggestionField = null)
            return
        }
        suggestionJob = scope.launch {
            wait(MAIL_AUTOCOMPLETE_DEBOUNCE_MILLIS)
            val outcome = transport.read(mailAutocompleteRequest(trimmed.take(MAX_MAIL_AUTOCOMPLETE_TERM)), ::parseMailAutocomplete)
            val taken = state.draft.recipients.mapTo(mutableSetOf()) { it.email.lowercase() }
            state = state.copy(
                suggestions = (outcome as? MailReadOutcome.Loaded)?.value.orEmpty().filterNot { it.email.lowercase() in taken },
                suggestionField = field,
            )
        }
    }

    fun clearSuggestions() {
        suggestionJob?.cancel()
        state = state.copy(suggestions = emptyList(), suggestionField = null)
    }

    /** Opens the platform picker and uploads the chosen file as a Mail attachment. */
    fun attachFromDevice() {
        if (!state.editable) return
        scope.launch {
            val selection = try {
                chooseLocalFile()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                LocalUploadSelectionResult.Unavailable("The file picker could not be opened.")
            }
            val file = when (selection) {
                is LocalUploadSelectionResult.Selected -> selection.file
                LocalUploadSelectionResult.Cancelled -> return@launch
                is LocalUploadSelectionResult.Rejected -> return@launch attachmentNotice(selection.reason)
                is LocalUploadSelectionResult.Unavailable -> return@launch attachmentNotice(selection.reason)
            }
            state = state.copy(uploading = state.uploading + file.displayName, attachmentNotice = null)
            val outcome = try {
                transport.upload(mailAttachmentUploadRequest(state.draft.accountId, file), ::parseMailUploadedAttachment)
            } finally {
                releaseLocalFile(file)
                state = state.copy(uploading = state.uploading - file.displayName)
            }
            when (outcome) {
                is MailWriteOutcome.Confirmed -> update { draft ->
                    draft.copy(attachments = draft.attachments + MailComposeAttachment.Uploaded(outcome.value.id, file.displayName))
                }
                is MailWriteOutcome.Rejected -> attachmentNotice("${file.displayName} could not be attached. ${outcome.failure.message}")
                // An unconfirmed upload is not referenced, so it is never sent; attaching again is safe.
                MailWriteOutcome.Unknown -> attachmentNotice("${file.displayName} may not have uploaded. Attach it again if it is missing.")
            }
        }
    }

    fun attachFromFiles(files: List<NextcloudFile>) = update { draft ->
        val added = files.filterNot(NextcloudFile::isDirectory).map { file ->
            MailComposeAttachment.FromFiles(path = "/" + file.path.trimStart('/'), fileName = file.name)
        }
        draft.copy(attachments = (draft.attachments + added).distinctBy(MailComposeAttachment::key))
    }

    fun removeAttachment(key: String) = update { draft -> draft.copy(attachments = draft.attachments.filterNot { it.key == key }) }

    fun dismissNotice() {
        state = state.copy(notice = null, attachmentNotice = null, persistenceNotice = null)
    }

    /** Stores the message in the Outbox, to be sent after the undo delay or at [scheduledAtEpochSeconds]. */
    fun send(scheduledAtEpochSeconds: Long? = null, acknowledgeMayBeSent: Boolean = false) {
        val phase = state.phase
        val retryAfterRejection = phase is MailSendPhase.NeedsAttention && phase.outboxId == null
        val retryAfterUnknown = phase is MailSendPhase.Unknown && phase.mayAlreadyBeSent && acknowledgeMayBeSent
        if (phase != MailSendPhase.Editing && !retryAfterRejection && !retryAfterUnknown) return
        val account = account(state.draft.accountId) ?: return
        if (problems.isNotEmpty()) return
        val now = nowEpochSeconds()
        if (scheduledAtEpochSeconds != null && scheduledAtEpochSeconds <= now) {
            state = state.copy(notice = "Choose a time in the future.")
            return
        }
        scope.launch {
            val previous = state.draft.storeAttemptSendAt
            if (previous != null && !acknowledgeMayBeSent) {
                // An earlier attempt may have stored this message. Look before storing it again.
                if (!adoptEarlierAttempt(account, previous)) return@launch
            }
            val sendAt = scheduledAtEpochSeconds ?: (now + undoDelay.seconds)
            val draft = state.draft.copy(storeAttemptSendAt = sendAt)
            val payload = try {
                draft.toOutgoingPayload(account, sendAt)
            } catch (invalid: IllegalArgumentException) {
                state = state.copy(phase = MailSendPhase.NeedsAttention(invalid.message ?: "The message cannot be sent."))
                return@launch
            }
            state = state.copy(draft = draft, phase = MailSendPhase.Storing)
            if (!persistNow()) {
                state = state.copy(draft = draft.copy(storeAttemptSendAt = null), phase = MailSendPhase.Editing)
                return@launch
            }
            handleStore(sender.store(payload), sendAt, scheduled = scheduledAtEpochSeconds != null)
        }
    }

    private suspend fun adoptEarlierAttempt(account: MailAccount, previousSendAt: Long): Boolean {
        val payload = state.draft.toOutgoingPayload(account, previousSendAt)
        return when (val listed = sender.outbox()) {
            is MailReadOutcome.Failed -> {
                state = state.copy(phase = MailSendPhase.Unknown("Could not check the Outbox for an earlier attempt. Try again when you are online."))
                false
            }
            is MailReadOutcome.Loaded -> {
                val match = listed.value.filter { it.matches(payload) }
                when {
                    match.size == 1 -> {
                        handleStore(MailStoreResult.Stored(match.single(), 0), previousSendAt, scheduled = previousSendAt > nowEpochSeconds() + undoDelay.seconds)
                        false
                    }
                    previousSendAt <= nowEpochSeconds() -> {
                        state = state.copy(
                            phase = MailSendPhase.Unknown(
                                "An earlier attempt may already have been sent. Check your Sent folder before sending again.",
                                mayAlreadyBeSent = true,
                            ),
                        )
                        false
                    }
                    else -> true
                }
            }
        }
    }

    private suspend fun handleStore(result: MailStoreResult, sendAt: Long, scheduled: Boolean) {
        when (result) {
            is MailStoreResult.Stored -> {
                val outboxId = result.message.id
                state = state.copy(draft = state.draft.copy(outboxMessageId = outboxId))
                persistNow()
                when {
                    result.missingAttachments > 0 -> state = state.copy(
                        phase = MailSendPhase.NeedsAttention(
                            "${result.missingAttachments} attachment(s) could not be added. A file may have been moved, " +
                                "or you may not be allowed to download it.",
                            outboxId = outboxId,
                            canSendAnyway = true,
                        ),
                    )
                    scheduled -> {
                        state = state.copy(phase = MailSendPhase.Scheduled(sendAt))
                        forgetLocalDraft()
                    }
                    sendAt > nowEpochSeconds() -> startUndoWindow(outboxId, sendAt)
                    else -> sendStored(outboxId)
                }
            }
            is MailStoreResult.Rejected -> {
                state = state.copy(
                    draft = state.draft.copy(storeAttemptSendAt = null),
                    phase = MailSendPhase.NeedsAttention("${result.failure.title}. ${result.failure.message}"),
                )
                persistNow()
            }
            MailStoreResult.NotStored -> {
                state = state.copy(
                    draft = state.draft.copy(storeAttemptSendAt = null),
                    phase = MailSendPhase.NeedsAttention("The message was not stored on the server. You can send it again."),
                )
                persistNow()
            }
            MailStoreResult.Unknown -> state = state.copy(
                phase = MailSendPhase.Unknown("Could not confirm that the message reached the Outbox. Check its status before sending again."),
            )
        }
    }

    private fun startUndoWindow(outboxId: Long, sendAt: Long) {
        state = state.copy(phase = MailSendPhase.Undoable(outboxId, sendAt))
        undoJob?.cancel()
        undoJob = scope.launch {
            val remaining = (sendAt - nowEpochSeconds()).coerceAtLeast(0L)
            wait(remaining * 1_000L)
            if ((state.phase as? MailSendPhase.Undoable)?.outboxId == outboxId) sendStored(outboxId)
        }
    }

    /** Skips the rest of the undo window. */
    fun sendImmediately() {
        val phase = state.phase as? MailSendPhase.Undoable ?: return
        undoJob?.cancel()
        scope.launch { sendStored(phase.outboxId) }
    }

    fun undo() {
        val phase = state.phase as? MailSendPhase.Undoable ?: return
        undoJob?.cancel()
        cancelStored(phase.outboxId)
    }

    /** Removes a message held because of a problem, and returns to editing. */
    fun cancelHeld() {
        val outboxId = (state.phase as? MailSendPhase.NeedsAttention)?.outboxId ?: return
        cancelStored(outboxId)
    }

    private fun cancelStored(outboxId: Long) {
        state = state.copy(phase = MailSendPhase.Sending)
        scope.launch {
            state = when (val result = sender.cancel(outboxId)) {
                MailCancelResult.Cancelled -> state.copy(
                    draft = state.draft.copy(outboxMessageId = null, storeAttemptSendAt = null),
                    phase = MailSendPhase.Editing,
                    notice = "Sending was undone. The message is back in the editor.",
                )
                MailCancelResult.AlreadyGone -> state.copy(
                    phase = MailSendPhase.Unknown("The message had already left the Outbox, so it was probably sent.", mayAlreadyBeSent = true),
                )
                is MailCancelResult.Rejected -> state.copy(
                    phase = MailSendPhase.NeedsAttention("${result.failure.title}. The message is still in the Outbox.", outboxId, canRetrySend = true),
                )
                MailCancelResult.Unknown -> state.copy(phase = MailSendPhase.Unknown("Could not confirm whether sending was undone. Check the Outbox."))
            }
            persistNow()
        }
    }

    /** Asks Mail to send a message it already holds. Mail's own status decides what a retry does. */
    fun retrySend() {
        val outboxId = (state.phase as? MailSendPhase.NeedsAttention)?.outboxId ?: return
        scope.launch { sendStored(outboxId) }
    }

    fun checkStatus() {
        val outboxId = state.draft.outboxMessageId
        if (outboxId != null) {
            checkSendStatus(outboxId)
            return
        }
        val sendAt = state.draft.storeAttemptSendAt ?: return
        val account = account(state.draft.accountId) ?: return
        scope.launch {
            if (adoptEarlierAttempt(account, sendAt)) {
                state = state.copy(
                    draft = state.draft.copy(storeAttemptSendAt = null),
                    phase = MailSendPhase.NeedsAttention("The message is not in the Outbox. You can send it again."),
                )
                persistNow()
            }
        }
    }

    private fun checkSendStatus(outboxId: Long) {
        scope.launch { applySendResult(sender.verifySent(outboxId), outboxId) }
    }

    private suspend fun sendStored(outboxId: Long) {
        state = state.copy(phase = MailSendPhase.Sending)
        applySendResult(sender.sendNow(outboxId), outboxId)
    }

    private suspend fun applySendResult(result: MailSendResult, outboxId: Long) {
        when (result) {
            MailSendResult.Sent -> {
                state = state.copy(phase = MailSendPhase.Sent)
                forgetLocalDraft()
            }
            is MailSendResult.Waiting -> {
                state = state.copy(phase = MailSendPhase.Queued)
                forgetLocalDraft()
            }
            is MailSendResult.Failed -> state = state.copy(
                phase = MailSendPhase.NeedsAttention(
                    result.message.status.explanation ?: "The server could not send this message.",
                    outboxId = outboxId,
                    // Mail refuses to resend after an unclear SMTP result; offering it would mislead.
                    canRetrySend = result.message.status != MailOutboxStatus.Error,
                ),
            )
            is MailSendResult.Rejected -> state = state.copy(
                phase = MailSendPhase.NeedsAttention("${result.failure.title}. The message is still in the Outbox.", outboxId, canRetrySend = true),
            )
            MailSendResult.Unknown -> state = state.copy(
                phase = MailSendPhase.Unknown("Could not confirm whether the message was sent. Check its status before trying again."),
            )
        }
    }

    /** Stores a server draft in the account's Drafts folder. The composer closes afterwards. */
    fun saveToServerDrafts(onSaved: () -> Unit) {
        if (!state.editable || state.savingServerDraft) return
        val account = account(state.draft.accountId) ?: return
        val payload = try {
            state.draft.toOutgoingPayload(account, sendAtEpochSeconds = null)
        } catch (invalid: IllegalArgumentException) {
            state = state.copy(notice = invalid.message)
            return
        }
        state = state.copy(savingServerDraft = true)
        scope.launch {
            val created = transport.write(mailCreateDraftRequest(payload), ::parseMailLocalMessage)
            val moved = (created as? MailWriteOutcome.Confirmed)?.let { transport.write(mailMoveDraftToImapRequest(it.value.id), ignoreMailBody) }
            state = state.copy(savingServerDraft = false)
            when {
                moved is MailWriteOutcome.Confirmed -> {
                    forgetLocalDraft()
                    onSaved()
                }
                created is MailWriteOutcome.Rejected -> state = state.copy(notice = "${created.failure.title}. The draft is still on this device.")
                // Mail moves due local drafts to IMAP itself, so a stored draft is not lost.
                else -> state = state.copy(notice = "Could not confirm the draft was saved to Drafts. It is still on this device.")
            }
        }
    }

    /** Removes the local copy. A message the Outbox still holds must be cancelled first. */
    fun discard(onDiscarded: () -> Unit) {
        if (state.draft.outboxMessageId != null && !state.finished) return
        scope.launch {
            forgetLocalDraft()
            onDiscarded()
        }
    }

    private fun schedulePersist() {
        persistJob?.cancel()
        persistJob = scope.launch {
            wait(MAIL_AUTOSAVE_DEBOUNCE_MILLIS)
            persistNow()
        }
    }

    suspend fun persistNow(): Boolean {
        val store = store ?: return true
        val draft = state.draft
        if (!draft.hasContent && draft.outboxMessageId == null && draft.storeAttemptSendAt == null) return true
        return try {
            store.save(draft)
            if (state.persistenceNotice != null) state = state.copy(persistenceNotice = null)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IllegalStateException) {
            state = state.copy(persistenceNotice = failure.message ?: "The draft could not be saved on this device.")
            false
        } catch (_: Exception) {
            state = state.copy(persistenceNotice = "The draft could not be saved on this device.")
            false
        }
    }

    private suspend fun forgetLocalDraft() {
        persistJob?.cancel()
        try {
            store?.remove(state.draft.slotId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            state = state.copy(persistenceNotice = "The local copy of this draft could not be removed.")
        }
    }

    private fun attachmentNotice(message: String) {
        state = state.copy(attachmentNotice = message)
    }
}

private const val MAIL_AUTOCOMPLETE_MINIMUM = 2
private const val MAIL_AUTOCOMPLETE_DEBOUNCE_MILLIS = 250L
private const val MAIL_AUTOSAVE_DEBOUNCE_MILLIS = 600L
