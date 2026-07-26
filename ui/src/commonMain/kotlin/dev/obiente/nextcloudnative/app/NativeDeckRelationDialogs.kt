package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import dev.obiente.nextcloudnative.app.design.NextcloudCardAction
import dev.obiente.nextcloudnative.app.design.NextcloudCardOverflow
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme
import dev.obiente.nextcloudnative.app.design.nextcloudCardInteractions

@Composable
fun DeckUiMoveCardDialog(
    card: DeckCard,
    stacks: List<DeckStack>,
    busy: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onMove: (target: DeckStack, placement: DeckUiCardPlacement) -> Unit,
) {
    val currentStack = stacks.firstOrNull { it.id == card.stackId }
    var target by remember(card, stacks) {
        mutableStateOf(currentStack ?: stacks.firstOrNull())
    }
    var placement by remember(card) { mutableStateOf(DeckUiCardPlacement.Bottom) }
    DeckUiAdaptiveDialog(
        title = "Move card",
        supportingText = card.title,
        onDismiss = onDismiss,
        dismissLabel = "Cancel",
        confirmLabel = "Move",
        confirmEnabled = target != null && !busy,
        busy = busy,
        onConfirm = { target?.let { onMove(it, placement) } },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
            contentPadding = PaddingValues(NextcloudSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        ) {
            item {
                Text("Destination list", style = MaterialTheme.typography.labelLarge)
            }
            items(stacks, key = DeckStack::id) { stack ->
                DeckUiSelectionRow(
                    title = stack.title,
                    supportingText = listOfNotNull(
                        "${stack.cards.size} cards",
                        "Current list".takeIf { stack.id == card.stackId },
                    ).joinToString(" - "),
                    selected = stack == target,
                    enabled = !busy,
                    onClick = { target = stack },
                )
            }
            item {
                Text(
                    "Position",
                    modifier = Modifier.padding(top = NextcloudSpacing.Medium),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                    items(DeckUiCardPlacement.entries) { candidate ->
                        FilterChip(
                            selected = placement == candidate,
                            enabled = !busy,
                            onClick = { placement = candidate },
                            label = {
                                Text(
                                    when (candidate) {
                                        DeckUiCardPlacement.Top -> "Top of list"
                                        DeckUiCardPlacement.Bottom -> "Bottom of list"
                                    },
                                )
                            },
                        )
                    }
                }
            }
            errorMessage?.let { message ->
                item { DeckUiInlineError(message) }
            }
        }
    }
}

@Composable
fun DeckUiLabelPickerDialog(
    card: DeckCard,
    availableLabels: List<DeckLabel>,
    busy: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSubmit: (List<DeckLabel>) -> Unit,
) {
    var selectedKeys by remember(card) {
        mutableStateOf(card.labels.map(DeckLabel::id).toSet())
    }
    DeckUiAdaptiveDialog(
        title = "Labels",
        supportingText = card.title,
        onDismiss = onDismiss,
        dismissLabel = "Cancel",
        confirmLabel = "Save labels",
        confirmEnabled = !busy,
        busy = busy,
        onConfirm = {
            onSubmit(availableLabels.filter { it.id in selectedKeys })
        },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
            contentPadding = PaddingValues(NextcloudSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        ) {
            if (availableLabels.isEmpty()) {
                item {
                    DeckUiEmptyRelationState(
                        title = "No labels available",
                        message = "Create labels in the board settings, then return to this card.",
                    )
                }
            }
            items(availableLabels, key = DeckLabel::id) { label ->
                val selected = label.id in selectedKeys
                DeckUiCheckboxRow(
                    title = label.title,
                    selected = selected,
                    enabled = !busy,
                    leading = {
                        Box(
                            modifier = Modifier.size(18.dp).background(
                                color = label.color.toDeckUiColor(
                                    MaterialTheme.colorScheme.primary,
                                ),
                                shape = RoundedCornerShape(5.dp),
                            ),
                        )
                    },
                    onClick = {
                        selectedKeys = selectedKeys.toggle(label.id)
                    },
                )
            }
            errorMessage?.let { message ->
                item { DeckUiInlineError(message) }
            }
        }
    }
}

@Composable
fun DeckUiAssigneePickerDialog(
    card: DeckCard,
    availableUsers: List<DeckUser>,
    busy: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSubmit: (List<DeckUser>) -> Unit,
) {
    var query by remember(card) { mutableStateOf("") }
    var selectedKeys by remember(card) {
        mutableStateOf(card.assignees.map(DeckUser::id).toSet())
    }
    val visibleUsers = remember(query, availableUsers) {
        val needle = query.trim().lowercase()
        if (needle.isBlank()) {
            availableUsers
        } else {
            availableUsers.filter { user ->
                user.displayName.lowercase().contains(needle)
            }
        }
    }
    DeckUiAdaptiveDialog(
        title = "Assignees",
        supportingText = card.title,
        onDismiss = onDismiss,
        dismissLabel = "Cancel",
        confirmLabel = "Save assignees",
        confirmEnabled = !busy,
        busy = busy,
        onConfirm = {
            onSubmit(availableUsers.filter { it.id in selectedKeys })
        },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp),
            contentPadding = PaddingValues(NextcloudSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        ) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Find a person") },
                    leadingIcon = {
                        Icon(NextcloudIcons.Search, contentDescription = null)
                    },
                    singleLine = true,
                    enabled = !busy,
                )
            }
            if (visibleUsers.isEmpty()) {
                item {
                    DeckUiEmptyRelationState(
                        title = if (availableUsers.isEmpty()) "No board members" else "No matches",
                        message = if (availableUsers.isEmpty()) {
                            "Share the board with someone before assigning cards to them."
                        } else {
                            "Try a different name."
                        },
                    )
                }
            }
            items(visibleUsers, key = DeckUser::id) { user ->
                val selected = user.id in selectedKeys
                DeckUiCheckboxRow(
                    title = user.displayName,
                    selected = selected,
                    enabled = !busy,
                    leading = {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = CircleShape,
                            modifier = Modifier.size(36.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    user.deckUiInitials(),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    },
                    onClick = {
                        selectedKeys = selectedKeys.toggle(user.id)
                    },
                )
            }
            errorMessage?.let { message ->
                item { DeckUiInlineError(message) }
            }
        }
    }
}

@Composable
fun DeckUiCommentsDialog(
    card: DeckCard,
    state: DeckUiCommentsState,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onSubmit: (message: String, replyTo: DeckUiComment?) -> Unit,
    onEdit: (DeckUiComment, String) -> Unit,
    onDelete: (DeckUiComment) -> Unit,
) {
    var draft by remember(card) { mutableStateOf("") }
    var editing by remember(card) { mutableStateOf<DeckUiComment?>(null) }
    var replyingTo by remember(card) { mutableStateOf<DeckUiComment?>(null) }
    var deleteCandidate by remember(card) { mutableStateOf<DeckUiComment?>(null) }
    val validationError = validateDeckUiComment(draft)
    DeckUiAdaptiveDialog(
        title = "Comments",
        supportingText = card.title,
        onDismiss = onDismiss,
        dismissLabel = "Close",
        busy = state.submitting,
    ) {
        Column(modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp)) {
            LazyColumn(
                modifier = Modifier.weight(1f, fill = false).fillMaxWidth(),
                contentPadding = PaddingValues(NextcloudSpacing.Large),
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
            ) {
                if (state.loading) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.XLarge),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(32.dp))
                        }
                    }
                } else if (state.comments.isEmpty()) {
                    item {
                        DeckUiEmptyRelationState(
                            title = "No comments yet",
                            message = "Start the conversation about this card.",
                        )
                    }
                }
                items(state.comments, key = DeckUiComment::key) { comment ->
                    DeckUiCommentItem(
                        comment = comment,
                        onReply = if (state.canComment) {
                            {
                                editing = null
                                replyingTo = comment
                                draft = ""
                            }
                        } else {
                            null
                        },
                        onEdit = {
                            replyingTo = null
                            editing = comment
                            draft = comment.messageMarkdown
                        },
                        onDelete = { deleteCandidate = comment },
                    )
                }
                if (state.hasMore || state.loadingMore) {
                    item {
                        OutlinedButton(
                            onClick = onLoadMore,
                            enabled = !state.loadingMore,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (state.loadingMore) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(NextcloudSpacing.Small))
                            }
                            Text(if (state.loadingMore) "Loading..." else "Load older comments")
                        }
                    }
                }
                state.errorMessage?.let { message ->
                    item {
                        DeckUiInlineError(message)
                        TextButton(onClick = onRefresh) { Text("Try again") }
                    }
                }
            }
            if (state.canComment || editing != null) {
                HorizontalDivider()
                Column(
                    modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
                    verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                ) {
                    val contextComment = editing ?: replyingTo
                    contextComment?.let { comment ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (editing != null) {
                                    "Editing ${comment.author?.displayName ?: "comment"}"
                                } else {
                                    "Replying to ${comment.author?.displayName ?: "comment"}"
                                },
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            TextButton(
                                onClick = {
                                    editing = null
                                    replyingTo = null
                                    draft = ""
                                },
                            ) {
                                Text("Cancel edit")
                            }
                        }
                    }
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = {
                            Text(
                                when {
                                    editing != null -> "Edit comment"
                                    replyingTo != null -> "Write a reply"
                                    else -> "Add a comment"
                                },
                            )
                        },
                        minLines = 2,
                        maxLines = 5,
                        enabled = !state.submitting,
                        supportingText = { Text("Markdown supported") },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Button(
                            enabled = validationError == null && !state.submitting,
                            onClick = {
                                val message = draft.trim()
                                editing?.let { onEdit(it, message) }
                                    ?: onSubmit(message, replyingTo)
                            },
                        ) {
                            Icon(NextcloudIcons.Send, contentDescription = null)
                            Spacer(Modifier.width(NextcloudSpacing.Small))
                            Text(
                                when {
                                    state.submitting -> "Saving..."
                                    editing != null -> "Save"
                                    replyingTo != null -> "Reply"
                                    else -> "Comment"
                                },
                            )
                        }
                    }
                }
            }
        }
    }
    deleteCandidate?.let { comment ->
        DeckUiDeleteConfirmation(
            title = "Delete comment?",
            message = "This comment will be removed from the card.",
            onDismiss = { deleteCandidate = null },
            onConfirm = {
                deleteCandidate = null
                onDelete(comment)
            },
        )
    }
}

@Composable
fun DeckUiAttachmentsDialog(
    card: DeckCard,
    state: DeckUiAttachmentsState,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onAdd: () -> Unit,
    onOpen: (DeckUiAttachment) -> Unit,
    onDelete: (DeckUiAttachment) -> Unit,
) {
    var deleteCandidate by remember(card) { mutableStateOf<DeckUiAttachment?>(null) }
    DeckUiAdaptiveDialog(
        title = "Attachments",
        supportingText = card.title,
        onDismiss = onDismiss,
        dismissLabel = "Close",
        busy = state.adding,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp),
            contentPadding = PaddingValues(NextcloudSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        ) {
            if (state.canAdd) {
                item {
                    Button(
                        onClick = onAdd,
                        enabled = !state.adding,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(NextcloudIcons.Add, contentDescription = null)
                        Spacer(Modifier.width(NextcloudSpacing.Small))
                        Text(if (state.adding) "Adding..." else "Add attachment")
                    }
                }
            }
            if (state.loading) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.XLarge),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    }
                }
            } else if (state.attachments.isEmpty()) {
                item {
                    DeckUiEmptyRelationState(
                        title = "No attachments",
                        message = "Add a file when this card needs more context.",
                    )
                }
            }
            items(state.attachments, key = DeckUiAttachment::key) { attachment ->
                DeckUiAttachmentItem(
                    attachment = attachment,
                    onOpen = { onOpen(attachment) },
                    onDelete = { deleteCandidate = attachment },
                )
            }
            if (state.hasMore || state.loadingMore) {
                item {
                    OutlinedButton(
                        onClick = onLoadMore,
                        enabled = !state.loadingMore,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (state.loadingMore) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(NextcloudSpacing.Small))
                        }
                        Text(if (state.loadingMore) "Loading..." else "Load more attachments")
                    }
                }
            }
            state.errorMessage?.let { message ->
                item {
                    DeckUiInlineError(message)
                    TextButton(onClick = onRefresh) { Text("Try again") }
                }
            }
        }
    }
    deleteCandidate?.let { attachment ->
        DeckUiDeleteConfirmation(
            title = "Delete attachment?",
            message = "${attachment.fileName} will be removed from this card.",
            onDismiss = { deleteCandidate = null },
            onConfirm = {
                deleteCandidate = null
                onDelete(attachment)
            },
        )
    }
}

@Composable
private fun DeckUiSelectionRow(
    title: String,
    supportingText: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.52f)
            } else {
                NextcloudTheme.colors.appTile
            },
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
        ) {
            RadioButton(selected = selected, onClick = null, enabled = enabled)
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    supportingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DeckUiCheckboxRow(
    title: String,
    selected: Boolean,
    enabled: Boolean,
    leading: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = NextcloudTheme.colors.appTile),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
        ) {
            leading()
            Text(
                title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Checkbox(selected, onCheckedChange = null, enabled = enabled)
        }
    }
}

@Composable
private fun DeckUiCommentItem(
    comment: DeckUiComment,
    onReply: (() -> Unit)?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember(comment.key) { mutableStateOf(false) }
    val actions = buildList {
        onReply?.let { add(NextcloudCardAction("Reply", onClick = it)) }
        if (comment.canEdit) add(NextcloudCardAction("Edit", onClick = onEdit))
        if (comment.canDelete) {
            add(NextcloudCardAction("Delete", destructive = true, onClick = onDelete))
        }
    }
    Surface(
        modifier = Modifier.fillMaxWidth().nextcloudCardInteractions(
            onOpen = null,
            onShowActions = actions.takeIf { it.isNotEmpty() }?.let {
                { menuExpanded = true }
            },
            actionsLabel = "Actions for comment",
        ),
        color = NextcloudTheme.colors.appTile,
        shape = RoundedCornerShape(NextcloudRadii.Card),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = CircleShape,
                    modifier = Modifier.size(34.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            comment.author?.deckUiInitials() ?: "?",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Spacer(Modifier.width(NextcloudSpacing.Small))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        comment.author?.displayName ?: "Unknown author",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        listOfNotNull(
                            comment.createdLabel,
                            "Edited".takeIf { comment.edited },
                        ).joinToString(" - "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                NextcloudCardOverflow(
                    itemLabel = "comment by ${comment.author?.displayName ?: "unknown author"}",
                    actions = actions,
                    expanded = menuExpanded,
                    onExpandedChange = { menuExpanded = it },
                )
            }
            comment.replyToLabel?.let { label ->
                Text(
                    "Reply to $label",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Markdown(content = comment.messageMarkdown)
        }
    }
}

@Composable
private fun DeckUiAttachmentItem(
    attachment: DeckUiAttachment,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember(attachment.key) { mutableStateOf(false) }
    val actions = if (attachment.canDelete) {
        listOf(NextcloudCardAction("Delete", destructive = true, onClick = onDelete))
    } else {
        emptyList()
    }
    Card(
        modifier = Modifier.fillMaxWidth().nextcloudCardInteractions(
            onOpen = onOpen.takeIf { attachment.canOpen },
            onShowActions = actions.takeIf { it.isNotEmpty() }?.let {
                { menuExpanded = true }
            },
            openLabel = "Open ${attachment.fileName}",
            actionsLabel = "Actions for ${attachment.fileName}",
        ),
        colors = CardDefaults.cardColors(containerColor = NextcloudTheme.colors.appTile),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
        ) {
            Surface(
                shape = RoundedCornerShape(NextcloudRadii.Small),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.size(42.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(NextcloudIcons.File, contentDescription = null)
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    attachment.fileName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                attachment.supportingText?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            NextcloudCardOverflow(
                itemLabel = attachment.fileName,
                actions = actions,
                expanded = menuExpanded,
                onExpandedChange = { menuExpanded = it },
            )
        }
    }
}

@Composable
private fun DeckUiEmptyRelationState(
    title: String,
    message: String,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.XLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DeckUiDeleteConfirmation(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Cancel") }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("Delete")
            }
        },
    )
}

private fun <T> Set<T>.toggle(value: T): Set<T> =
    if (value in this) this - value else this + value

private fun DeckUser.deckUiInitials(): String = displayName.split(' ')
    .filter(String::isNotBlank)
    .take(2)
    .mapNotNull(String::firstOrNull)
    .joinToString("")
    .uppercase()
    .ifBlank { "?" }

private fun String?.toDeckUiColor(fallback: Color): Color {
    val normalized = this?.trim()?.removePrefix("#") ?: return fallback
    if (normalized.length !in setOf(3, 6, 8) ||
        normalized.any { !it.isDigit() && it.lowercaseChar() !in 'a'..'f' }
    ) {
        return fallback
    }
    val expanded = if (normalized.length == 3) {
        normalized.flatMap { listOf(it, it) }.joinToString("")
    } else {
        normalized
    }
    val argb = when (expanded.length) {
        6 -> "ff$expanded"
        8 -> expanded
        else -> return fallback
    }
    return runCatching { Color(argb.toLong(16)) }.getOrDefault(fallback)
}
