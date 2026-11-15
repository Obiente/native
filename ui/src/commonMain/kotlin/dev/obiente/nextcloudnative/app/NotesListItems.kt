package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudCardAction
import dev.obiente.nextcloudnative.app.design.NextcloudCardOverflow
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme
import dev.obiente.nextcloudnative.app.design.nextcloudCardInteractions
import kotlin.time.Instant

@Composable
internal fun NotesBreadcrumbs(
    breadcrumbs: List<NativeNoteBreadcrumb>,
    onNavigate: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        breadcrumbs.forEachIndexed { index, breadcrumb ->
            if (index > 0) Text("/", color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { onNavigate(breadcrumb.path) }) {
                Text(breadcrumb.label, maxLines = 1)
            }
        }
    }
}

@Composable
internal fun NoteFolderCard(
    folder: NativeNoteFolder,
    mutationEnabled: Boolean,
    onOpen: () -> Unit,
    onRename: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    var menuExpanded by remember(folder.path) { mutableStateOf(false) }
    val actions = listOfNotNull(
        onRename?.let { NextcloudCardAction(label = "Rename", enabled = mutationEnabled, onClick = it) },
        onDelete?.let { NextcloudCardAction(label = "Delete", enabled = mutationEnabled, destructive = true, onClick = it) },
    )
    Card(
        modifier = Modifier.fillMaxWidth().nextcloudCardInteractions(
            onOpen = onOpen,
            onShowActions = { menuExpanded = true },
            openLabel = "Open ${folder.name}",
            actionsLabel = "Show actions for ${folder.name}",
        ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(NextcloudRadii.Card),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(color = NextcloudTheme.colors.appIconContainer, shape = CircleShape) {
                Icon(
                    NextcloudIcons.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(10.dp).size(22.dp),
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(folder.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    buildString {
                        append(folder.descendantNoteCount)
                        append(if (folder.descendantNoteCount == 1) " note" else " notes")
                        if (folder.directNoteCount != folder.descendantNoteCount) {
                            append(" · ")
                            append(folder.directNoteCount)
                            append(" here")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            NextcloudCardOverflow(
                itemLabel = folder.name,
                actions = actions,
                expanded = menuExpanded,
                onExpandedChange = { menuExpanded = it },
            )
            Icon(NextcloudIcons.ChevronRight, contentDescription = "Open ${folder.name}")
        }
    }
}

@Composable
internal fun NoteCard(
    note: NextcloudNote,
    enabled: Boolean,
    onClick: () -> Unit,
    selected: Boolean = false,
    actions: List<NextcloudCardAction> = emptyList(),
) {
    var menuExpanded by remember(note.id) { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().semantics { this.selected = selected }.nextcloudCardInteractions(
            onOpen = onClick.takeIf { enabled },
            onShowActions = actions.takeIf { enabled && it.isNotEmpty() }?.let { { menuExpanded = true } },
            openLabel = "Open ${note.title}",
            actionsLabel = "Show actions for ${note.title}",
        ),
        colors = workspaceListCardColors(selected, unselected = MaterialTheme.colorScheme.surfaceContainer),
        border = workspaceListCardBorder(selected),
        shape = RoundedCornerShape(NextcloudRadii.Card),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(color = NextcloudTheme.colors.appIconContainer, shape = CircleShape) {
                Icon(
                    if (note.favorite) NextcloudIcons.Favorite else NextcloudIcons.app("notes"),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(10.dp).size(22.dp),
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val markdown = remember(note.content) { note.markdownMetadata() }
                Text(
                    note.title, Modifier.sharedElement(SharedElementKeys.noteTitle(note.id)),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                markdown.preview?.let { preview ->
                    Text(
                        preview.trimStart('#', ' '),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val metadata = listOfNotNull(
                    note.category.takeIf(String::isNotBlank),
                    note.internalPath?.substringAfterLast('.')?.takeIf(String::isNotBlank)?.uppercase(),
                    "${markdown.wordCount} words".takeIf { note.content != null },
                    "${markdown.completedTasks}/${markdown.totalTasks} tasks".takeIf { markdown.totalTasks > 0 },
                    note.modified.takeIf { it > 0L }?.let { "Updated ${formatNoteModified(it)}" },
                    "Shared".takeIf { note.isShared },
                    "Read only".takeIf { note.readOnly },
                ).joinToString(" · ")
                Text(metadata, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (actions.isEmpty() || !enabled) {
                Icon(NextcloudIcons.ChevronRight, contentDescription = "Open ${note.title}")
            } else {
                NextcloudCardOverflow(
                    itemLabel = note.title,
                    actions = actions,
                    expanded = menuExpanded,
                    onExpandedChange = { menuExpanded = it },
                )
            }
        }
    }
}

private fun formatNoteModified(epochSeconds: Long): String = runCatching {
    Instant.fromEpochSeconds(epochSeconds).toString().replace('T', ' ').take(16)
}.getOrDefault("recently")
