package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import dev.obiente.nextcloudnative.app.design.NextcloudCardAction
import dev.obiente.nextcloudnative.app.design.NextcloudCardOverflow
import dev.obiente.nextcloudnative.app.design.NextcloudFilterChip
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import kotlin.time.Clock

/** Comment thread of one file: read, post, edit and delete own comments. */
@Composable
internal fun FileCommentsSection(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    userId: String,
    fileId: Long,
) {
    val scope = rememberCoroutineScope()
    val controller = remember(services, session, userId, fileId, scope) {
        FileCommentsController(scope, userId, fileId) { request -> services.executeFilesDav(session, request) }
    }
    LaunchedEffect(controller) { controller.start() }
    val state = controller.state
    // A comment draft is short-lived; it intentionally does not survive process death.
    var draft by remember(fileId) { mutableStateOf("") }
    var editingId by remember(fileId) { mutableStateOf<Long?>(null) }
    var editDraft by remember(fileId) { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Comments", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).semantics { heading() })
            if (state.loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            else TextButton(onClick = controller::refresh) { Text("Refresh") }
        }
        val comments = state.comments
        when {
            state.unavailable -> SectionNote("Comments are not available for this item.")
            comments == null && state.failure != null -> SectionNote(commentFailureReason(state.failure))
            comments == null -> SectionNote("Loading comments...")
            else -> {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(MAX_FILE_COMMENT_CHARACTERS + 50) },
                    label = { Text("Write a comment") },
                    enabled = state.canPost,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = {
                        val problem = draft.takeIf(String::isNotBlank)?.let(::fileCommentDraftProblem)
                        Text(problem ?: "${draft.trim().length}/$MAX_FILE_COMMENT_CHARACTERS")
                    },
                )
                Button(
                    onClick = { controller.post(draft) { draft = "" } },
                    enabled = state.canPost && fileCommentDraftProblem(draft) == null,
                ) { Text(if (state.posting) "Posting..." else "Post comment") }
                state.notice?.let { SectionNote(it, error = true) }
                if (comments.isEmpty()) SectionNote("No comments yet.")
                val now = remember(comments) { Clock.System.now().epochSeconds }
                comments.forEach { comment ->
                    if (editingId == comment.id) {
                        OutlinedTextField(
                            value = editDraft,
                            onValueChange = { editDraft = it.take(MAX_FILE_COMMENT_CHARACTERS + 50) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Edit comment") },
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                            Button(
                                onClick = { controller.edit(comment, editDraft) { editingId = null } },
                                enabled = fileCommentDraftProblem(editDraft) == null && comment.id !in state.pendingIds,
                            ) { Text("Save") }
                            OutlinedButton(onClick = { editingId = null }) { Text("Cancel") }
                        }
                    } else {
                        CommentRow(comment, state, userId, now,
                            onEdit = {
                                editingId = comment.id
                                editDraft = comment.message
                            },
                            onDelete = { controller.requestDelete(comment) },
                        )
                    }
                }
                if (state.mayHaveMore) {
                    TextButton(onClick = controller::loadMore, enabled = !state.loadingMore) {
                        Text(if (state.loadingMore) "Loading..." else "Show older comments")
                    }
                }
            }
        }
    }
    if (state.confirmDeleteId != null) {
        AlertDialog(
            onDismissRequest = controller::cancelDelete,
            title = { Text("Delete this comment?") },
            text = { Text("The comment is removed for everyone who can see this file.") },
            dismissButton = { OutlinedButton(onClick = controller::cancelDelete) { Text("Cancel") } },
            confirmButton = {
                Button(
                    onClick = controller::confirmDelete,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
        )
    }
}

@Composable
private fun CommentRow(
    comment: NextcloudFileComment,
    state: FileCommentsState,
    userId: String,
    now: Long,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember(comment.id) { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(
                buildString {
                    append(comment.actorDisplayName)
                    comment.createdAtEpochSeconds?.let { append(" - ").append(compactAgeLabel(it, now)) }
                    if (comment.unread) append(" - new")
                    if (comment.id in state.pendingIds) append(" - saving...")
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(comment.displayMessage, style = MaterialTheme.typography.bodyMedium)
        }
        if (comment.isAuthoredBy(userId)) {
            val enabled = state.canChange(comment, userId)
            NextcloudCardOverflow(
                itemLabel = "comment by ${comment.actorDisplayName}",
                actions = listOf(
                    NextcloudCardAction("Edit", enabled = enabled, onClick = onEdit),
                    NextcloudCardAction("Delete", destructive = true, enabled = enabled, onClick = onDelete),
                ),
                expanded = menuOpen,
                onExpandedChange = { menuOpen = it },
            )
        }
    }
}

/** System tags of one file: toggle assignable tags and create a new public tag. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FileSystemTagsSection(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    fileId: Long,
) {
    val scope = rememberCoroutineScope()
    val controller = remember(services, session, fileId, scope) {
        FileTagsController(
            scope = scope,
            fileId = fileId,
            execute = { request -> services.executeFilesDav(session, request) },
            loadCatalog = { services.listSystemTags(session) },
        )
    }
    LaunchedEffect(controller) { controller.start() }
    val state = controller.state
    var newTag by remember(fileId) { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
        Text("Tags", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        when {
            state.unavailable -> SectionNote("Tags are not available for this item.")
            state.assigned == null && state.failure != null -> SectionNote(tagFailureReason(state.failure))
            state.assigned == null -> SectionNote("Loading tags...")
            else -> {
                val assignedIds = state.assignedIds
                if (state.offered.isEmpty()) SectionNote("No tags yet.")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                    verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                ) {
                    state.offered.forEach { tag ->
                        NextcloudFilterChip(
                            selected = tag.id in assignedIds,
                            enabled = state.canToggle(tag),
                            onClick = { controller.toggle(tag) },
                            label = { Text(if (tag.access == SystemTagAccess.Restricted) "${tag.name} (restricted)" else tag.name) },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                    OutlinedTextField(
                        value = newTag,
                        onValueChange = { newTag = it.take(80) },
                        label = { Text("New tag") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        onClick = { controller.createAndAssign(newTag) { newTag = "" } },
                        enabled = !state.creating && fileTagNameProblem(newTag) == null,
                    ) { Text(if (state.creating) "Adding..." else "Add") }
                }
                state.notice?.let { SectionNote(it, error = true) }
            }
        }
    }
}

@Composable
private fun SectionNote(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
