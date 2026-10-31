package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudCardOverflow
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.nextcloudAnimatedColor
import dev.obiente.nextcloudnative.app.design.nextcloudCardInteractions
import dev.obiente.nextcloudnative.app.design.nextcloudItemMotion
import kotlin.time.Clock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
internal fun MailMessageListPane(
    state: MailWorkspaceUiState,
    controller: MailWorkspaceController,
    listState: LazyListState,
    onFolderDialog: (MailFolderDialog) -> Unit,
) {
    var messageDialog by remember { mutableStateOf<MailMessageDialog?>(null) }
    Column(modifier = Modifier.fillMaxSize()) {
        if (state.selectedMailbox != null) MailSearchField(state, controller)
        (state.messages as? MailMessagesState.Loaded)?.refreshFailure?.let { failure ->
            MailNoticeBar("${failure.title}. Showing the messages loaded earlier.", "Try again", controller::refresh)
        }
        Box(modifier = Modifier.weight(1f)) {
            when (val messages = state.messages) {
                MailMessagesState.Idle -> EmptyMessage(if (state.mailboxes?.isEmpty() == true) "This account has no folders." else "")
                MailMessagesState.Loading -> LoadingMessage("Loading messages...")
                is MailMessagesState.Failed -> MailFailurePane(messages.failure, onRetry = controller::refresh)
                is MailMessagesState.Loaded -> if (messages.items.isEmpty()) {
                    EmptyMessage(
                        if (state.searchQuery.isNotBlank()) {
                            "No messages in this folder match \"${state.searchQuery}\"."
                        } else {
                            "This folder is empty."
                        },
                    )
                } else {
                    MailMessageLazyList(messages, state, controller, listState) { messageDialog = it }
                }
            }
        }
    }
    messageDialog?.let { dialog -> MailMessageDialogHost(dialog, state, controller, onDismiss = { messageDialog = null }) }
}

@Composable
private fun MailSearchField(state: MailWorkspaceUiState, controller: MailWorkspaceController) {
    var query by rememberSaveable(state.selectedMailboxId) { mutableStateOf(state.searchQuery) }
    LaunchedEffect(query) {
        // Searching asks the server, so wait until typing pauses.
        delay(MAIL_SEARCH_DEBOUNCE_MILLIS)
        controller.search(query.trim())
    }
    OutlinedTextField(
        value = query,
        onValueChange = { query = it.take(MAX_MAIL_FILTER_CHARACTERS) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = NextcloudSpacing.Large, vertical = NextcloudSpacing.Small),
        singleLine = true,
        leadingIcon = { Icon(NextcloudIcons.Search, contentDescription = null) },
        placeholder = { Text("Search ${state.selectedMailbox?.displayName ?: "this folder"}") },
    )
}

@Composable
private fun MailMessageLazyList(
    messages: MailMessagesState.Loaded,
    state: MailWorkspaceUiState,
    controller: MailWorkspaceController,
    listState: LazyListState,
    onDialog: (MailMessageDialog) -> Unit,
) {
    LaunchedEffect(listState, messages.endReached) {
        if (messages.endReached) return@LaunchedEffect
        snapshotFlow {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - MAIL_PREFETCH_DISTANCE
        }.distinctUntilChanged().collect { nearEnd -> if (nearEnd) controller.loadMore() }
    }
    val now = remember(messages.items) { Clock.System.now().epochSeconds }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        items(messages.items, key = { it.id }) { message ->
            Column(nextcloudItemMotion()) {
                MailMessageRow(
                    message = message,
                    selected = state.opened?.summary?.id == message.id,
                    pending = message.id in state.pendingMessageIds,
                    sentFolder = state.selectedMailbox?.let(state::role).let { it == MailMailboxRole.Sent || it == MailMailboxRole.Drafts },
                    nowEpochSeconds = now,
                    actions = mailMessageActions(message, state, controller, onDialog),
                    onOpen = { controller.open(message) },
                )
                HorizontalDivider()
            }
        }
        item(key = "mail-list-footer") {
            Box(modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large), contentAlignment = Alignment.Center) {
                when {
                    messages.loadingMore -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    messages.pageFailure != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(messages.pageFailure.title, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = controller::loadMore) { Text("Try again") }
                    }
                    messages.endReached -> Text(
                        "End of folder",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun MailMessageRow(
    message: MailMessageSummary,
    selected: Boolean,
    pending: Boolean,
    sentFolder: Boolean,
    nowEpochSeconds: Long,
    actions: List<dev.obiente.nextcloudnative.app.design.NextcloudCardAction>,
    onOpen: () -> Unit,
) {
    var menuOpen by remember(message.id) { mutableStateOf(false) }
    val unread = !message.flags.seen
    val people = (if (sentFolder) message.to else message.from).joinToString(", ") { it.displayName }.ifBlank { "(no sender)" }
    Surface(
        color = nextcloudAnimatedColor(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .nextcloudCardInteractions(
                    onOpen = onOpen,
                    onShowActions = { menuOpen = true },
                    openLabel = "Read message",
                    actionsLabel = "Message actions",
                )
                .semantics(mergeDescendants = true) {
                    stateDescription = listOfNotNull(
                        if (unread) "Unread" else "Read",
                        "Flagged".takeIf { message.flags.flagged },
                        "Has attachments".takeIf { message.flags.hasAttachments },
                        "Updating".takeIf { pending },
                    ).joinToString(", ")
                }
                .padding(start = NextcloudSpacing.Large, top = NextcloudSpacing.Medium, bottom = NextcloudSpacing.Medium),
            verticalAlignment = Alignment.Top,
        ) {
            Box(modifier = Modifier.padding(top = 7.dp, end = NextcloudSpacing.Medium).size(8.dp)) {
                val dot = nextcloudAnimatedColor(if (unread) MaterialTheme.colorScheme.primary else Color.Transparent)
                Box(Modifier.fillMaxSize().background(dot, CircleShape))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        people,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    message.sentAtEpochSeconds?.let { sent ->
                        Text(
                            compactAgeLabel(sent, nowEpochSeconds),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = NextcloudSpacing.Small),
                        )
                    }
                }
                Text(
                    message.subject.ifBlank { "(no subject)" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (unread) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                message.previewText?.takeIf(String::isNotBlank)?.let { preview ->
                    Text(
                        preview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (message.tags.isNotEmpty()) MailTagChips(message.tags)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = NextcloudSpacing.XSmall)) {
                if (message.flags.flagged) {
                    Icon(Icons.Outlined.Flag, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                }
                if (message.flags.hasAttachments) {
                    Icon(Icons.Outlined.AttachFile, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }
            NextcloudCardOverflow(
                itemLabel = message.subject.ifBlank { "message" },
                actions = actions,
                expanded = menuOpen,
                onExpandedChange = { menuOpen = it },
            )
        }
    }
}

@Composable
internal fun MailTagChips(tags: List<MailTag>) {
    Row(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall)) {
        tags.take(4).forEach { tag ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = NextcloudSpacing.Small, vertical = 2.dp),
                ) {
                    tag.color?.let(::parseMailTagColor)?.let { color ->
                        Box(Modifier.padding(end = NextcloudSpacing.XSmall).size(8.dp).background(color, CircleShape))
                    }
                    Text(tag.displayName, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
        }
    }
}

internal fun parseMailTagColor(value: String): Color? {
    val hex = value.removePrefix("#")
    val rgb = when (hex.length) {
        3 -> hex.map { "$it$it" }.joinToString("")
        6, 8 -> hex.take(6)
        else -> return null
    }
    return rgb.toLongOrNull(16)?.let { Color(0xFF000000 or it) }
}

private const val MAIL_PREFETCH_DISTANCE = 8
private const val MAIL_SEARCH_DEBOUNCE_MILLIS = 400L
