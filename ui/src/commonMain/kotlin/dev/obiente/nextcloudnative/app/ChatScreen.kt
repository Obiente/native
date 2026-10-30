package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.LocalNextcloudWorkspaceCapabilities
import dev.obiente.nextcloudnative.app.design.NextcloudCardAction
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Owns a [TalkChatController] for one account and conversation.
 *
 * The draft and an unconfirmed send are saved across recreation; both are small user text that
 * recovery needs. Reply and edit targets are ordinary remembered state.
 */
@Composable
internal fun rememberTalkChatController(
    session: NextcloudSession,
    room: TalkRoom,
    userId: String,
    execute: suspend (TalkTransportRequest) -> TalkTransportResponse,
    unconfirmed: TalkUnconfirmedSend?,
    onUnconfirmedChanged: (TalkUnconfirmedSend?) -> Unit,
): TalkChatController {
    val currentExecute by rememberUpdatedState(execute)
    val currentUnconfirmedChanged by rememberUpdatedState(onUnconfirmedChanged)
    val parent = rememberCoroutineScope()
    val scope = remember(session.accountId, room.token) {
        CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    }
    DisposableEffect(scope) { onDispose { scope.cancel() } }
    val controller = remember(scope, userId) {
        TalkChatController(
            scope = scope,
            token = room.token,
            ownActorId = userId,
            execute = { request -> currentExecute(request) },
            memory = talkChatWorkspaceMemory(session, room.token),
            initialUnconfirmed = unconfirmed,
        )
    }
    LaunchedEffect(controller) {
        snapshotFlow { controller.state.unconfirmed }.collect { currentUnconfirmedChanged(it) }
    }
    return controller
}

@Composable
internal fun ChatScreen(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    userId: String,
    room: TalkRoom,
    onBack: (() -> Unit)?,
    onOpenAttachment: (NextcloudFile) -> Unit,
    execute: suspend (TalkTransportRequest) -> TalkTransportResponse = { services.executeTalkRequest(session, it) },
) {
    var draft by rememberSaveable(session.accountId, room.token) { mutableStateOf("") }
    var savedUnconfirmed by rememberSaveable(session.accountId, room.token, stateSaver = TalkUnconfirmedSendSaver) {
        mutableStateOf<TalkUnconfirmedSend?>(null)
    }
    val controller = rememberTalkChatController(
        session = session,
        room = room,
        userId = userId,
        execute = execute,
        unconfirmed = savedUnconfirmed,
        onUnconfirmedChanged = { savedUnconfirmed = it },
    )
    DisposableEffect(controller) {
        // The draft is read through its state, so a send confirmed later clears the current text.
        controller.onSent = { sent -> if (draft.trim() == sent.text) draft = "" }
        onDispose { controller.onSent = {} }
    }
    val state = controller.state
    var editDraft by remember(controller, state.editing?.id) { mutableStateOf(state.editing?.message.orEmpty()) }
    var reactingTo by remember(controller) { mutableStateOf<Long?>(null) }
    var confirmDelete by remember(controller) { mutableStateOf<TalkMessage?>(null) }
    var confirmResend by remember(controller) { mutableStateOf(false) }
    val messageListState = rememberLazyListState()
    val messages = state.messages
    val historyHeaderVisible = (state.hasMoreHistory && state.olderCursor != null) || state.historyFailure != null
    val windowVisibility = LocalAppWindowVisibility.current

    LaunchedEffect(controller) { controller.start() }
    LaunchedEffect(controller, windowVisibility) {
        windowVisibility.collect(controller::setLiveUpdates)
    }
    DisposableEffect(controller) { onDispose { controller.setLiveUpdates(false) } }
    LaunchedEffect(messages?.lastOrNull()?.id) {
        val lastIndex = talkLastMessageListIndex(messages.orEmpty().size, historyHeaderVisible) ?: return@LaunchedEffect
        messageListState.scrollToItem(lastIndex)
    }

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        if (onBack != null) {
            ScreenHeader(room.displayName, talkLiveStatusLabel(state.live), onBack) {
                TalkRefreshButton(state.refreshing, controller::refresh)
            }
        } else {
            TalkPaneHeader(room.displayName, talkLiveStatusLabel(state.live)) {
                TalkRefreshButton(state.refreshing, controller::refresh)
            }
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.loadFailure != null && messages == null ->
                    ErrorMessage(requireNotNull(state.loadFailure).message, controller::refresh)
                messages == null -> LoadingMessage("Loading messages...")
                messages.isEmpty() -> EmptyMessage("No messages in this conversation yet.")
                else -> LazyColumn(
                    state = messageListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(NextcloudSpacing.Large),
                    verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                ) {
                    if (historyHeaderVisible) {
                        item(key = "talk-load-earlier") { TalkHistoryHeader(state, controller::loadEarlier) }
                    }
                    listItems(messages, key = TalkMessage::id) { message ->
                        TalkMessageCard(
                            services = services,
                            session = session,
                            message = message,
                            mine = message.actorId == userId && message.actorType == "users",
                            onOpenAttachment = { attachment -> onOpenAttachment(attachment.asNextcloudFile()) },
                            interactions = talkMessageInteractions(
                                message = message,
                                controller = controller,
                                onReact = { reactingTo = message.id },
                                onDelete = { confirmDelete = message },
                            ),
                        )
                    }
                }
            }
            if (state.refreshing && messages != null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
            if (state.loadFailure != null && messages != null) {
                RetainedContentNotice(
                    message = requireNotNull(state.loadFailure).message,
                    onRetry = controller::refresh,
                    actionLabel = "Refresh",
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        }
        state.notice?.let { notice ->
            TalkComposerNotice(notice, actionLabel = "Dismiss", onAction = controller::dismissNotice)
        }
        TalkSendStatus(state, onCheck = controller::refresh)
        reactingTo?.let { id -> state.timeline?.message(id) }?.let { message ->
            TalkQuickReactionBar(
                onReact = { emoji ->
                    reactingTo = null
                    controller.toggleReaction(message, emoji)
                },
                onDismiss = { reactingTo = null },
                modifier = Modifier.padding(horizontal = NextcloudSpacing.Medium),
            )
        }
        TalkComposer(
            state = state,
            text = if (state.editing != null) editDraft else draft,
            onTextChange = { if (state.editing != null) editDraft = it else draft = it },
            onSubmit = {
                when {
                    state.editing != null -> controller.saveEdit(editDraft)
                    state.unconfirmed != null -> confirmResend = true
                    else -> controller.send(draft)
                }
            },
            onCancelContext = {
                when {
                    state.editing != null -> controller.cancelEdit()
                    state.replyTo != null -> controller.cancelReply()
                }
            },
        )
    }
    confirmDelete?.let { message ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this message?") },
            text = { Text("Everyone in the conversation will see that a message was deleted. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = null; controller.delete(message) }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
    if (confirmResend) {
        AlertDialog(
            onDismissRequest = { confirmResend = false },
            title = { Text("Send this draft again?") },
            text = { Text("The earlier send may still arrive. Sending again can create a duplicate message.") },
            confirmButton = {
                TextButton(onClick = { confirmResend = false; controller.send(draft, confirmedResend = true) }) {
                    Text("Send again")
                }
            },
            dismissButton = { TextButton(onClick = { confirmResend = false }) { Text("Keep draft") } },
        )
    }
}

private fun talkMessageInteractions(
    message: TalkMessage,
    controller: TalkChatController,
    onReact: () -> Unit,
    onDelete: () -> Unit,
): TalkMessageInteractions {
    val abilities = controller.state.abilities
    val pending = message.id in controller.state.pendingMessageIds
    val actions = buildList {
        if (abilities.canReply(message)) add(NextcloudCardAction("Reply", "talk-reply") { controller.reply(message) })
        if (abilities.canReact && !message.deleted && !message.isSystemMessage) {
            add(NextcloudCardAction("React", "talk-react", enabled = !pending, onClick = onReact))
        }
        if (controller.canEdit(message)) {
            add(NextcloudCardAction("Edit", "talk-edit", enabled = !pending) { controller.startEdit(message) })
        }
        if (controller.canDelete(message)) {
            add(NextcloudCardAction("Delete", "talk-delete", destructive = true, enabled = !pending, onClick = onDelete))
        }
    }
    return TalkMessageInteractions(
        actions = actions,
        onToggleReaction = { emoji: String -> controller.toggleReaction(message, emoji) }
            .takeIf { abilities.canReact && !message.deleted },
        pending = pending,
    )
}

@Composable
private fun TalkHistoryHeader(state: TalkChatUiState, onLoadEarlier: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        state.historyFailure?.let { failure ->
            Text(failure.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (state.hasMoreHistory && state.olderCursor != null) {
            TextButton(enabled = !state.loadingEarlier, onClick = onLoadEarlier) {
                if (state.loadingEarlier) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Load earlier messages")
                }
            }
        }
    }
}

@Composable
private fun TalkRefreshButton(refreshing: Boolean, onRefresh: () -> Unit) {
    IconButton(enabled = !refreshing, onClick = onRefresh) {
        Icon(NextcloudIcons.Refresh, contentDescription = "Refresh conversation")
    }
}

@Composable
private fun TalkSendStatus(state: TalkChatUiState, onCheck: () -> Unit) {
    when (val send = state.send) {
        is TalkSendState.Unconfirmed -> TalkComposerNotice(
            message = if (send.checked) {
                "Sending was not confirmed and the message is not in the conversation yet. Your draft is kept; you can send it again."
            } else {
                "Sending was not confirmed. Your draft is kept. Check the conversation before sending again."
            },
            actionLabel = "Check conversation".takeUnless { send.checked || state.refreshing },
            onAction = onCheck,
            error = true,
        )
        is TalkSendState.Rejected -> TalkComposerNotice(
            message = "The message was not sent. ${send.failure.message}",
            error = true,
        )
        TalkSendState.Idle, TalkSendState.Sending -> Unit
    }
}

@Composable
private fun TalkComposerNotice(
    message: String,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    error: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = NextcloudSpacing.Medium),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
    ) {
        Text(
            message,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        actionLabel?.let { TextButton(onClick = onAction) { Text(it) } }
    }
}

@Composable
private fun TalkComposer(
    state: TalkChatUiState,
    text: String,
    onTextChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancelContext: () -> Unit,
) {
    val desktop = LocalNextcloudWorkspaceCapabilities.current.isDesktop
    val editing = state.editing
    val sending = state.send == TalkSendState.Sending
    val editPending = editing != null && editing.id in state.pendingMessageIds
    val canWrite = if (editing != null) !editPending else state.abilities.canPost && !sending
    val unconfirmedLocked = editing == null && (state.send as? TalkSendState.Unconfirmed)?.checked == false
    val canSubmit = canWrite && text.isNotBlank() && !unconfirmedLocked
    val context = when {
        editing != null -> "Editing your message" to "Cancel editing"
        state.replyTo != null -> "Replying to ${requireNotNull(state.replyTo).actorDisplayName}: ${requireNotNull(state.replyTo).message}" to "Cancel reply"
        else -> null
    }
    Column(modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium)) {
        context?.let { (label, cancelLabel) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = onCancelContext) { Icon(NextcloudIcons.Close, contentDescription = cancelLabel) }
            }
        }
        if (!state.abilities.canPost && editing == null && state.timeline != null && !state.refreshing) {
            Text(
                talkComposerUnavailableLabel(state.abilities),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f)
                    .semantics { contentDescription = if (editing != null) "Edit message" else "Message" }
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when {
                            event.key == Key.Escape && context != null -> { onCancelContext(); true }
                            // Desktop sends with Enter and keeps Shift+Enter for a new line.
                            desktop && event.key == Key.Enter && !event.isShiftPressed -> {
                                if (canSubmit) onSubmit()
                                true
                            }
                            else -> false
                        }
                    },
                placeholder = { Text(if (editing != null) "Edit message" else "Message") },
                enabled = canWrite,
                minLines = 1,
                maxLines = 6,
                shape = RoundedCornerShape(NextcloudRadii.Card),
            )
            IconButton(enabled = canSubmit, onClick = onSubmit) {
                if (sending || editPending) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        if (editing != null) NextcloudIcons.Save else NextcloudIcons.Send,
                        contentDescription = when {
                            editing != null -> "Save edit"
                            state.unconfirmed != null -> "Review sending again"
                            else -> "Send message"
                        },
                    )
                }
            }
        }
    }
}

private fun talkComposerUnavailableLabel(abilities: TalkChatAbilities): String {
    val access = abilities.access ?: return "Sending is unavailable until the conversation can be checked. Refresh to try again."
    return when {
        access.lobbyBlocksParticipant -> "The lobby is active. You can read, but only moderators can write."
        access.readOnly -> "This conversation is read-only."
        else -> "You do not have permission to write in this conversation."
    }
}

internal fun talkLiveStatusLabel(live: TalkLiveState): String = when (live) {
    TalkLiveState.Paused -> "Talk"
    TalkLiveState.Connected -> "Live"
    is TalkLiveState.Reconnecting -> "Reconnecting..."
    is TalkLiveState.Stopped -> live.failure.message
}

internal fun talkLastMessageListIndex(messageCount: Int, hasHistoryHeader: Boolean): Int? =
    if (messageCount <= 0) null else messageCount - 1 + if (hasHistoryHeader) 1 else 0

private val TalkUnconfirmedSendSaver: Saver<TalkUnconfirmedSend?, Any> = Saver(
    save = { value -> value?.let { listOf(it.text, it.referenceId.orEmpty(), it.replyTo?.toString().orEmpty()) } ?: emptyList<String>() },
    restore = { saved ->
        val fields = (saved as? List<*>)?.filterIsInstance<String>()
        if (fields?.size != 3 || fields[0].isBlank()) {
            null
        } else {
            TalkUnconfirmedSend(
                text = fields[0],
                referenceId = fields[1].takeIf(String::isTalkReferenceId),
                replyTo = fields[2].toLongOrNull()?.takeIf { it > 0L },
            )
        }
    },
)
