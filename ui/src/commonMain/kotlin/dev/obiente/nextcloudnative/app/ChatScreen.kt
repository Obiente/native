package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.nativeui.runtime.runCatchingUnlessCancelled
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun ChatScreen(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    userId: String,
    room: TalkRoom,
    onBack: () -> Unit,
    onOpenAttachment: (NextcloudFile) -> Unit,
) {
    var messages by remember(session, room.token) {
        mutableStateOf(TalkWorkspaceMemoryCache.messages(session, room.token))
    }
    var olderCursor by remember(session, room.token) { mutableStateOf<Long?>(null) }
    var hasMoreHistory by remember(session, room.token) { mutableStateOf(false) }
    var loadingEarlier by remember(session, room.token) { mutableStateOf(false) }
    var historyError by remember(session, room.token) { mutableStateOf<String?>(null) }
    var draft by rememberSaveable(session.serverUrl, session.loginName, room.token) { mutableStateOf("") }
    var error by remember(session, room.token) { mutableStateOf<String?>(null) }
    var sendUnconfirmed by rememberSaveable(session.serverUrl, session.loginName, room.token) { mutableStateOf(false) }
    var sendReviewed by remember(session, room.token) { mutableStateOf(false) }
    var confirmResend by remember(session, room.token) { mutableStateOf(false) }
    var refreshing by remember(session, room.token) { mutableStateOf(false) }
    var sending by remember(session, room.token) { mutableStateOf(false) }
    var loadAttempt by remember(session, room.token) { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val messageListState = rememberLazyListState()
    val orderedMessages = remember(messages) { messages?.sortedBy(TalkMessage::id) }
    val historyHeaderVisible = (hasMoreHistory && olderCursor != null) || historyError != null

    suspend fun refresh() {
        val page = services.listTalkMessagePage(session, room.token)
        messages = page.messages
        TalkWorkspaceMemoryCache.storeMessages(session, room.token, page.messages)
        olderCursor = page.olderCursor
        hasMoreHistory = page.hasMoreHistory
    }
    fun sendDraft() {
        val submittedDraft = draft.trim()
        if (submittedDraft.isBlank() || sending) return
        sending = true
        sendUnconfirmed = true
        sendReviewed = false
        scope.launch {
            try {
                when (submitTalkDraft(
                    send = { services.sendTalkMessage(session, room.token, submittedDraft) },
                    onAcknowledged = { draft = ""; sendUnconfirmed = false },
                    refresh = ::refresh,
                )) {
                    TalkSendResult.Sent -> error = null
                    TalkSendResult.SentRefreshFailed -> error = "Message sent. Could not refresh the conversation."
                    TalkSendResult.Unconfirmed -> sendUnconfirmed = true
                }
            } finally {
                sending = false
            }
        }
    }
    LaunchedEffect(session, room.token, loadAttempt) {
        refreshing = messages != null
        error = null
        try {
            refresh()
            if (sendUnconfirmed && loadAttempt > 0) sendReviewed = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = "Could not refresh the conversation."
        } finally {
            refreshing = false
        }
    }
    LaunchedEffect(orderedMessages?.lastOrNull()?.id) {
        val lastIndex = talkLastMessageListIndex(orderedMessages.orEmpty().size, historyHeaderVisible)
            ?: return@LaunchedEffect
        messageListState.scrollToItem(lastIndex)
    }

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        ScreenHeader(room.displayName, "Talk", onBack)
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                error != null && messages == null -> ErrorMessage(requireNotNull(error)) { loadAttempt += 1 }
                messages == null -> LoadingMessage("Loading messages...")
                messages?.isEmpty() == true -> EmptyMessage("No messages in this conversation yet.")
                else -> LazyColumn(
                    state = messageListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(NextcloudSpacing.Large),
                    verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                ) {
                    if (historyHeaderVisible) {
                        item(key = "talk-load-earlier") {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                historyError?.let { message ->
                                    Text(
                                        message,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                                if (hasMoreHistory && olderCursor != null) {
                                    TextButton(
                                        enabled = !loadingEarlier,
                                        onClick = {
                                            val cursor = olderCursor ?: return@TextButton
                                            loadingEarlier = true
                                            historyError = null
                                            scope.launch {
                                                runCatchingUnlessCancelled {
                                                    services.listTalkMessagePage(
                                                        session = session,
                                                        token = room.token,
                                                        olderCursor = cursor,
                                                    )
                                                }.onSuccess { page ->
                                                    messages = mergeTalkMessageHistory(
                                                        messages.orEmpty(),
                                                        page.messages,
                                                    )
                                                    TalkWorkspaceMemoryCache.storeMessages(
                                                        session,
                                                        room.token,
                                                        messages.orEmpty(),
                                                    )
                                                    olderCursor = page.olderCursor
                                                    hasMoreHistory = page.hasMoreHistory
                                                }.onFailure { failure ->
                                                    historyError =
                                                        failure.message ?: "Could not load earlier messages."
                                                }
                                                loadingEarlier = false
                                            }
                                        },
                                    ) {
                                        if (loadingEarlier) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(18.dp),
                                                strokeWidth = 2.dp,
                                            )
                                        } else {
                                            Text("Load earlier messages")
                                        }
                                    }
                                }
                            }
                        }
                    }
                    listItems(requireNotNull(orderedMessages), key = TalkMessage::id) { message ->
                        TalkMessageCard(
                            services = services,
                            session = session,
                            message = message,
                            mine = message.actorId == userId,
                            onOpenAttachment = { attachment ->
                                onOpenAttachment(attachment.asNextcloudFile())
                            },
                        )
                    }
                }
            }
            if (refreshing) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
                )
            }
            if (error != null && messages != null) {
                RetainedContentNotice(
                    message = requireNotNull(error),
                    onRetry = { loadAttempt += 1 },
                    actionLabel = "Refresh",
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        }
        if (sendUnconfirmed && !sending) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = NextcloudSpacing.Medium)) {
                Text(
                    "Sending was not confirmed. Your draft is kept. Refresh and check whether the message arrived before sending again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(enabled = !refreshing, onClick = { loadAttempt += 1 }) { Text("Refresh conversation") }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message") },
                enabled = !sending,
                minLines = 1,
                maxLines = 6,
                shape = RoundedCornerShape(NextcloudRadii.Card),
            )
            IconButton(
                enabled = draft.isNotBlank() && !sending && (!sendUnconfirmed || sendReviewed),
                onClick = {
                    if (sendUnconfirmed) confirmResend = true else sendDraft()
                },
            ) {
                if (sending) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(NextcloudIcons.Send, contentDescription = if (sendUnconfirmed) "Review sending again" else "Send message")
            }
        }
    }
    if (confirmResend) {
        AlertDialog(
            onDismissRequest = { confirmResend = false },
            title = { Text("Send this draft again?") },
            text = { Text("The earlier send may have succeeded. Sending again can create a duplicate. Continue only after checking the conversation.") },
            confirmButton = {
                TextButton(onClick = { confirmResend = false; sendDraft() }) { Text("Send again") }
            },
            dismissButton = { TextButton(onClick = { confirmResend = false }) { Text("Keep draft") } },
        )
    }
}

internal fun talkLastMessageListIndex(messageCount: Int, hasHistoryHeader: Boolean): Int? =
    if (messageCount <= 0) null else messageCount - 1 + if (hasHistoryHeader) 1 else 0

internal enum class TalkSendResult { Sent, SentRefreshFailed, Unconfirmed }

internal suspend fun submitTalkDraft(
    send: suspend () -> Unit,
    onAcknowledged: () -> Unit,
    refresh: suspend () -> Unit,
): TalkSendResult {
    try {
        send()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        return TalkSendResult.Unconfirmed
    }
    onAcknowledged()
    return try {
        refresh()
        TalkSendResult.Sent
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        TalkSendResult.SentRefreshFailed
    }
}
