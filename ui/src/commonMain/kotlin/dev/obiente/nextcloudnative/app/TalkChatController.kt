package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

internal sealed interface TalkLiveState {
    /** Not waiting for messages: the app is hidden or the conversation has not loaded. */
    data object Paused : TalkLiveState
    data object Connected : TalkLiveState
    data class Reconnecting(val failure: TalkFailure) : TalkLiveState
    /** A failure that waiting cannot fix, such as a removed conversation or an expired sign-in. */
    data class Stopped(val failure: TalkFailure) : TalkLiveState
}

/**
 * A send whose result has not been confirmed. It is recorded before its request leaves the device
 * and kept until it is resolved, so the conversation can be checked for [referenceId] before the
 * user is offered to send again.
 */
@Serializable
data class TalkUnconfirmedSend(val text: String, val referenceId: String?, val replyTo: Long?)

/**
 * Durable, account- and conversation-scoped storage for the one unresolved send of a
 * conversation. It outlives the chat screen, so leaving a conversation or restarting the app
 * cannot turn "may have been sent" into "never sent".
 */
interface TalkUnconfirmedSendStore {
    suspend fun load(): TalkUnconfirmedSend?

    suspend fun save(attempt: TalkUnconfirmedSend)

    suspend fun clear()
}

internal sealed interface TalkSendState {
    data object Idle : TalkSendState
    data object Sending : TalkSendState
    /** [checked] once the conversation was read after the attempt and did not contain the message. */
    data class Unconfirmed(val checked: Boolean) : TalkSendState
    data class Rejected(val failure: TalkFailure) : TalkSendState
}

internal data class TalkChatUiState(
    val timeline: TalkTimeline? = null,
    val loadFailure: TalkFailure? = null,
    val refreshing: Boolean = false,
    val olderCursor: Long? = null,
    val hasMoreHistory: Boolean = false,
    val loadingEarlier: Boolean = false,
    val historyFailure: TalkFailure? = null,
    val live: TalkLiveState = TalkLiveState.Paused,
    val abilities: TalkChatAbilities = TalkChatAbilities(TalkCapabilities.None, null),
    val replyTo: TalkMessage? = null,
    val editing: TalkMessage? = null,
    val send: TalkSendState = TalkSendState.Idle,
    val unconfirmed: TalkUnconfirmedSend? = null,
    val pendingMessageIds: Set<Long> = emptySet(),
    val notice: String? = null,
) {
    val messages: List<TalkMessage>? get() = timeline?.messages
}

/** Account- and conversation-scoped memory for painting the last timeline first. */
internal interface TalkChatMemory {
    fun load(): List<TalkMessage>?
    fun store(messages: List<TalkMessage>)
}

internal fun talkChatWorkspaceMemory(session: NextcloudSession, token: String): TalkChatMemory = object : TalkChatMemory {
    // Captured before any read, so a retired account cannot be repopulated by a late response.
    private val producer = TalkWorkspaceMemoryCache.producer(session)
    override fun load(): List<TalkMessage>? = TalkWorkspaceMemoryCache.messages(session, token)
    override fun store(messages: List<TalkMessage>) =
        TalkWorkspaceMemoryCache.storeMessages(session, token, messages, producer)
}

/**
 * Owns one conversation's timeline, live updates, and writes for one account.
 *
 * Loads and long polls are fenced by a generation, so a response for a replaced timeline is
 * dropped. Writes are offered only when [TalkChatAbilities] allows them. A write whose result is
 * unknown is verified against the server before the user is told what happened; a send is never
 * repeated without the user confirming it after the conversation was checked.
 */
internal class TalkChatController(
    private val scope: CoroutineScope,
    private val token: String,
    private val ownActorId: String,
    private val execute: suspend (TalkTransportRequest) -> TalkTransportResponse,
    private val memory: TalkChatMemory? = null,
    initialUnconfirmed: TalkUnconfirmedSend? = null,
    /** Without a store the unresolved send lives only as long as this controller. */
    private val unconfirmedStore: TalkUnconfirmedSendStore? = null,
    private val parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val nowSeconds: () -> Long = { Clock.System.now().epochSeconds },
    private val newReferenceId: () -> String = { newTalkReferenceId() },
    /** Waits between polls; tests replace it to step through reconnects without real time. */
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    var state by mutableStateOf(
        TalkChatUiState(
            timeline = memory?.load()?.let(TalkTimeline::of),
            unconfirmed = initialUnconfirmed,
            send = if (initialUnconfirmed != null) TalkSendState.Unconfirmed(checked = false) else TalkSendState.Idle,
        ),
    )
        private set

    /** Receives each confirmed send, so the caller can clear the draft that produced it. */
    var onSent: (TalkUnconfirmedSend) -> Unit = {}

    private var generation = 0L
    private var loadedGeneration = -1L
    private var loadJob: Job? = null
    private var pollJob: Job? = null
    private var historyJob: Job? = null
    private var liveRequested = false
    private var restoreJob: Job? = null

    /** False until the stored unresolved send has been read; nothing is sent before that. */
    private var storedSendRead = unconfirmedStore == null

    fun start() {
        if (loadJob != null || restoreJob != null) return
        val store = unconfirmedStore
        if (store == null) {
            refresh()
            return
        }
        restoreJob = scope.launch {
            try {
                val stored = store.load()
                if (stored != null && state.unconfirmed == null) {
                    state = state.copy(unconfirmed = stored, send = TalkSendState.Unconfirmed(checked = false))
                }
                storedSendRead = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Persistence fault boundary: an unreadable record may be a message already sent.
                state = state.copy(notice = "An earlier message saved on this device could not be read, so nothing can be sent in this conversation.")
            }
            refresh()
        }
    }

    /** Reloads access and the latest page; an unconfirmed send is checked against the result. */
    fun refresh() {
        val current = ++generation
        pollJob?.cancel()
        historyJob?.cancel()
        loadJob?.cancel()
        state = state.copy(
            refreshing = true,
            loadFailure = null,
            loadingEarlier = false,
            historyFailure = null,
            live = if (state.live is TalkLiveState.Stopped) TalkLiveState.Paused else state.live,
        )
        loadJob = scope.launch {
            val (abilities, page) = coroutineScope {
                val capabilities = async { readCapabilities() }
                val access = async { readAccess() }
                val page = async { read(TalkChatRequests.history(token, olderCursor = null)) }
                TalkChatAbilities(capabilities.await() ?: state.abilities.capabilities, access.await()) to page.await()
            }
            if (current != generation) return@launch
            state = state.copy(abilities = abilities, refreshing = false)
            when (page) {
                is TalkChatRead.Failed -> {
                    state = state.copy(loadFailure = page.failure)
                    if (page.failure.stopsLiveUpdates) state = state.copy(live = TalkLiveState.Stopped(page.failure))
                    return@launch
                }
                TalkChatRead.NotModified -> publish(TalkTimeline.Empty, olderCursor = null)
                is TalkChatRead.Messages -> publish(
                    TalkTimeline.Empty.applying(page.entries),
                    olderCursor = page.lastGiven,
                )
            }
            loadedGeneration = current
            reconcileUnconfirmed(page as? TalkChatRead.Messages, checkedConversation = true)
            startPolling()
        }
    }

    /** Starts or stops waiting for new messages, for example when the app is hidden. */
    fun setLiveUpdates(enabled: Boolean) {
        liveRequested = enabled
        if (enabled) {
            startPolling()
        } else {
            pollJob?.cancel()
            pollJob = null
            if (state.live !is TalkLiveState.Stopped) state = state.copy(live = TalkLiveState.Paused)
        }
    }

    fun loadEarlier() {
        val cursor = state.olderCursor ?: return
        if (!state.hasMoreHistory || state.loadingEarlier) return
        val current = generation
        state = state.copy(loadingEarlier = true, historyFailure = null)
        historyJob = scope.launch {
            val page = read(TalkChatRequests.history(token, olderCursor = cursor))
            if (current != generation) return@launch
            val timeline = state.timeline ?: TalkTimeline.Empty
            state = when (page) {
                is TalkChatRead.Messages -> {
                    val updated = timeline.withOlder(page.entries)
                    memory?.store(updated.messages)
                    state.copy(
                        timeline = updated,
                        olderCursor = page.lastGiven,
                        hasMoreHistory = page.lastGiven != null && page.entries.isNotEmpty(),
                        loadingEarlier = false,
                    )
                }
                TalkChatRead.NotModified -> state.copy(hasMoreHistory = false, loadingEarlier = false)
                is TalkChatRead.Failed -> state.copy(historyFailure = page.failure, loadingEarlier = false)
            }
        }
    }

    fun reply(message: TalkMessage) {
        if (!state.abilities.canReply(message)) return
        state = state.copy(replyTo = message, editing = null)
    }

    fun cancelReply() {
        state = state.copy(replyTo = null)
    }

    fun canEdit(message: TalkMessage): Boolean = state.abilities.canEdit(message, ownActorId, nowSeconds())

    fun canDelete(message: TalkMessage): Boolean = state.abilities.canDelete(message, ownActorId, nowSeconds())

    fun startEdit(message: TalkMessage) {
        if (!canEdit(message) || message.id in state.pendingMessageIds) return
        state = state.copy(editing = message, replyTo = null, notice = null)
    }

    fun cancelEdit() {
        state = state.copy(editing = null)
    }

    fun dismissNotice() {
        state = state.copy(notice = null)
    }

    /**
     * Sends [text] once. While an earlier send is unconfirmed, sending again needs
     * [confirmedResend] and is allowed only after the conversation was checked without finding it.
     */
    fun send(text: String, confirmedResend: Boolean = false) {
        val message = text.trim()
        val current = state
        if (message.isBlank() || current.send == TalkSendState.Sending || !current.abilities.canPost || !storedSendRead) return
        if (current.unconfirmed != null) {
            val checked = (current.send as? TalkSendState.Unconfirmed)?.checked == true
            if (!confirmedResend || !checked) return
        }
        val earlier = current.unconfirmed
        if (talkMessageLength(message) > current.abilities.capabilities.maxMessageLength) {
            val tooLong = TalkFailure(TalkFailureKind.TooLong)
            // With an earlier send unresolved its phase stays, so a shortened text can still be sent.
            state = if (earlier != null) current.copy(notice = tooLong.message) else current.copy(send = TalkSendState.Rejected(tooLong))
            return
        }
        val replyTo = current.replyTo?.takeIf(current.abilities::canReply)?.id
        val referenceId = newReferenceId().takeIf { current.abilities.capabilities.referenceIds && it.isTalkReferenceId() }
        val attempt = TalkUnconfirmedSend(message, referenceId, replyTo)
        // Recorded before the request, so leaving the screen mid-send keeps the recovery path.
        state = current.copy(send = TalkSendState.Sending, unconfirmed = attempt)
        scope.launch {
            if (!recordUnconfirmed(attempt)) {
                state = state.copy(
                    send = if (earlier != null) TalkSendState.Unconfirmed(checked = true) else TalkSendState.Idle,
                    unconfirmed = earlier,
                    notice = "This device could not keep a record of the message, so it was not sent.",
                )
                return@launch
            }
            when (val outcome = write(TalkChatRequests.send(token, message, replyTo, referenceId), ::parseTalkWrittenEntry)) {
                is TalkWriteOutcome.Confirmed -> {
                    outcome.value?.let { entry -> updateTimeline { it.applying(listOf(entry), advanceCursor = false) } }
                    confirmSent(attempt)
                }
                is TalkWriteOutcome.Rejected -> {
                    state = if (earlier != null) {
                        // The refusal answers only this request; the earlier message may still have been sent.
                        state.copy(send = TalkSendState.Unconfirmed(checked = true), unconfirmed = earlier, notice = outcome.failure.message)
                    } else {
                        state.copy(send = TalkSendState.Rejected(outcome.failure), unconfirmed = null)
                    }
                    storeUnconfirmed()
                }
                TalkWriteOutcome.Unknown -> {
                    state = state.copy(send = TalkSendState.Unconfirmed(checked = false))
                    refresh()
                }
            }
        }
    }

    fun saveEdit(text: String) {
        val target = state.editing ?: return
        val message = text.trim()
        if (message.isBlank() || target.id in state.pendingMessageIds) return
        if (message == target.message) {
            cancelEdit()
            return
        }
        if (talkMessageLength(message) > state.abilities.capabilities.maxMessageLength) {
            state = state.copy(notice = TalkFailure(TalkFailureKind.TooLong).message)
            return
        }
        mutate(target.id) {
            when (val outcome = write(TalkChatRequests.edit(token, target.id, message), ::parseTalkWrittenEntry)) {
                is TalkWriteOutcome.Confirmed -> {
                    outcome.value?.let { entry -> updateTimeline { it.applying(listOf(entry), advanceCursor = false) } }
                    finishEdit(target.id)
                }
                is TalkWriteOutcome.Rejected -> state = state.copy(notice = outcome.failure.message)
                TalkWriteOutcome.Unknown -> {
                    val verified = verifyMessage(target.id)
                    when {
                        verified?.message == message -> {
                            updateTimeline { it.replacing(verified) }
                            finishEdit(target.id)
                        }
                        verified != null -> {
                            updateTimeline { it.replacing(verified) }
                            state = state.copy(notice = "The edit was not saved. The message still shows the earlier text.")
                        }
                        else -> state = state.copy(notice = "The edit was not confirmed. Refresh the conversation to check it.")
                    }
                }
            }
        }
    }

    /** Deletes one of this account's messages. The caller confirms with the user first. */
    fun delete(message: TalkMessage) {
        if (!canDelete(message) || message.id in state.pendingMessageIds) return
        mutate(message.id) {
            when (val outcome = write(TalkChatRequests.delete(token, message.id), ::parseTalkWrittenEntry)) {
                is TalkWriteOutcome.Confirmed -> markDeleted(message.id, outcome.value)
                is TalkWriteOutcome.Rejected -> state = state.copy(notice = outcome.failure.message)
                TalkWriteOutcome.Unknown -> {
                    val verified = verifyMessage(message.id)
                    when {
                        verified?.deleted == true -> markDeleted(message.id, null)
                        verified != null -> state = state.copy(
                            notice = "The message was not deleted. Try again if you still want to delete it.",
                        )
                        else -> state = state.copy(notice = "Deleting was not confirmed. Refresh the conversation to check it.")
                    }
                }
            }
        }
    }

    /** Adds the reaction when this account has not used [emoji] on the message, otherwise removes it. */
    fun toggleReaction(message: TalkMessage, emoji: String) {
        if (!state.abilities.canReact || message.deleted || message.id in state.pendingMessageIds) return
        val add = message.reactions.none { it.emoji == emoji && it.reactedByMe }
        mutate(message.id) {
            val outcome = write(TalkChatRequests.react(token, message.id, emoji, add)) { parseTalkReactions(it, ownActorId) }
            when {
                outcome is TalkWriteOutcome.Confirmed && outcome.value != null -> {
                    val reactions = requireNotNull(outcome.value)
                    updateMessage(message.id) { it.withReactions(reactions) }
                }
                // Removing a reaction that is already gone answers 404; check rather than report a failure.
                outcome is TalkWriteOutcome.Rejected && !(outcome.failure.kind == TalkFailureKind.NotFound && !add) ->
                    state = state.copy(notice = outcome.failure.message)
                else -> verifyReaction(message.id, emoji, add)
            }
        }
    }

    private suspend fun verifyReaction(messageId: Long, emoji: String, add: Boolean) {
        val reactions = try {
            parseTalkReactions(execute(TalkChatRequests.reactions(token, messageId, emoji)), ownActorId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (reactions == null) {
            state = state.copy(notice = "The reaction was not confirmed. Refresh the conversation to check it.")
            return
        }
        val reaction = reactions.firstOrNull { it.emoji == emoji }
        updateMessage(messageId) { it.withReaction(emoji, reaction) }
        if ((reaction?.reactedByMe == true) != add) state = state.copy(notice = "The reaction was not saved.")
    }

    private suspend fun verifyMessage(messageId: Long): TalkMessage? {
        if (!state.abilities.capabilities.messageContext) return null
        val read = read(TalkChatRequests.context(token, messageId)) as? TalkChatRead.Messages ?: return null
        return read.entries.firstOrNull { it.message.id == messageId && !it.isTimelineUpdate }?.message
    }

    private fun markDeleted(messageId: Long, entry: TalkChatEntry?) {
        if (entry?.updatedParent != null) {
            updateTimeline { it.applying(listOf(entry), advanceCursor = false) }
        } else {
            // The deleted text is not kept locally once the server confirmed the deletion.
            updateMessage(messageId) {
                it.copy(
                    deleted = true,
                    messageType = TalkMessageType.CommentDeleted,
                    message = TALK_DELETED_MESSAGE_SUMMARY,
                    content = TalkMessageContent.Text(TALK_DELETED_MESSAGE_SUMMARY, markdown = false),
                    parameters = emptyMap(),
                    reactions = emptyList(),
                )
            }
        }
        state = state.copy(
            editing = state.editing?.takeIf { it.id != messageId },
            replyTo = state.replyTo?.takeIf { it.id != messageId },
        )
    }

    private fun finishEdit(messageId: Long) {
        if (state.editing?.id == messageId) state = state.copy(editing = null)
    }

    private fun mutate(messageId: Long, block: suspend () -> Unit) {
        state = state.copy(pendingMessageIds = state.pendingMessageIds + messageId, notice = null)
        scope.launch {
            try {
                block()
            } finally {
                state = state.copy(pendingMessageIds = state.pendingMessageIds - messageId)
            }
        }
    }

    private fun startPolling() {
        if (!liveRequested || loadedGeneration != generation || pollJob?.isActive == true) return
        if (state.live is TalkLiveState.Stopped) return
        val current = generation
        pollJob = scope.launch { poll(current) }
    }

    private suspend fun poll(current: Long) {
        var failures = 0
        state = state.copy(live = TalkLiveState.Connected)
        while (current == generation) {
            val cursor = state.timeline?.newestKnownId ?: 0L
            val read = read(TalkChatRequests.poll(token, cursor))
            if (current != generation) return
            when (read) {
                is TalkChatRead.Messages -> {
                    failures = 0
                    updateTimeline { it.applying(read.entries, read.lastGiven) }
                    state = state.copy(live = TalkLiveState.Connected)
                    reconcileUnconfirmed(read, checkedConversation = false)
                    // A reply that does not move the cursor would otherwise be requested again at once.
                    if ((state.timeline?.newestKnownId ?: 0L) <= cursor) wait(TALK_POLL_RESTART_DELAY_MILLIS)
                }
                TalkChatRead.NotModified -> {
                    failures = 0
                    state = state.copy(live = TalkLiveState.Connected)
                    wait(TALK_POLL_RESTART_DELAY_MILLIS)
                }
                is TalkChatRead.Failed -> {
                    if (read.failure.stopsLiveUpdates) {
                        state = state.copy(live = TalkLiveState.Stopped(read.failure))
                        return
                    }
                    failures += 1
                    state = state.copy(live = TalkLiveState.Reconnecting(read.failure))
                    wait(talkPollBackoffMillis(failures))
                }
            }
        }
    }

    private fun reconcileUnconfirmed(read: TalkChatRead.Messages?, checkedConversation: Boolean) {
        val attempt = state.unconfirmed ?: return
        if (state.send == TalkSendState.Sending) return
        val referenceId = attempt.referenceId
        val found = referenceId != null && read?.entries.orEmpty().any { entry ->
            entry.message.referenceId == referenceId && entry.message.actorId == ownActorId
        }
        when {
            found -> confirmSent(attempt)
            checkedConversation -> state = state.copy(send = TalkSendState.Unconfirmed(checked = true))
        }
    }

    private fun confirmSent(attempt: TalkUnconfirmedSend) {
        state = state.copy(
            send = TalkSendState.Idle,
            unconfirmed = state.unconfirmed?.takeIf { it != attempt },
            replyTo = state.replyTo?.takeIf { it.id != attempt.replyTo },
        )
        storeUnconfirmed()
        onSent(attempt)
    }

    /** Makes [attempt] durable before its request can leave the device, or reports that it could not. */
    private suspend fun recordUnconfirmed(attempt: TalkUnconfirmedSend): Boolean {
        val store = unconfirmedStore ?: return true
        return try {
            store.save(attempt)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    /** Brings the stored record in line with the unresolved send after it was resolved or replaced. */
    private fun storeUnconfirmed() {
        val store = unconfirmedStore ?: return
        val remaining = state.unconfirmed
        scope.launch {
            try {
                if (remaining == null) store.clear() else store.save(remaining)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A record left behind only makes the conversation be checked once more.
            }
        }
    }

    private fun publish(timeline: TalkTimeline, olderCursor: Long?) {
        memory?.store(timeline.messages)
        state = state.copy(
            timeline = timeline,
            olderCursor = olderCursor,
            hasMoreHistory = olderCursor != null,
        )
    }

    private fun updateTimeline(change: (TalkTimeline) -> TalkTimeline) {
        val updated = change(state.timeline ?: return)
        memory?.store(updated.messages)
        state = state.copy(
            timeline = updated,
            editing = state.editing?.let { editing -> updated.message(editing.id) ?: editing },
            replyTo = state.replyTo?.let { reply -> updated.message(reply.id) ?: reply },
        )
    }

    private fun updateMessage(messageId: Long, change: (TalkMessage) -> TalkMessage) =
        updateTimeline { timeline -> timeline.message(messageId)?.let { timeline.replacing(change(it)) } ?: timeline }

    private suspend fun readCapabilities(): TalkCapabilities? = try {
        val response = execute(TalkChatRequests.capabilities())
        withContext(parseDispatcher) { parseTalkCapabilities(response) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private suspend fun readAccess(): TalkConversationAccess? = try {
        val response = execute(TalkChatRequests.conversation(token))
        if (response.status in 200..299) {
            withContext(parseDispatcher) { parseTalkConversationAccess(response) }?.takeIf { it.token == token }
        } else {
            null
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private suspend fun read(request: TalkTransportRequest): TalkChatRead = try {
        val response = execute(request)
        withContext(parseDispatcher) { parseTalkChatRead(response) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        TalkChatRead.Failed(TalkFailure(TalkFailureKind.Offline))
    }

    private suspend fun <T> write(
        request: TalkTransportRequest,
        parse: (TalkTransportResponse) -> T,
    ): TalkWriteOutcome<T> = try {
        val response = execute(request)
        withContext(parseDispatcher) { talkWriteOutcome(response, parse) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // The request may have reached the server before the connection failed.
        TalkWriteOutcome.Unknown
    }
}

internal fun talkPollBackoffMillis(failures: Int): Long =
    (TALK_POLL_FIRST_BACKOFF_MILLIS shl (failures - 1).coerceIn(0, 5)).coerceAtMost(TALK_POLL_MAX_BACKOFF_MILLIS)

internal const val TALK_POLL_RESTART_DELAY_MILLIS = 1_000L
private const val TALK_DELETED_MESSAGE_SUMMARY = "Message deleted"
private const val TALK_POLL_FIRST_BACKOFF_MILLIS = 2_000L
private const val TALK_POLL_MAX_BACKOFF_MILLIS = 60_000L
