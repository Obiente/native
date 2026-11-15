package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudCardAction
import dev.obiente.nextcloudnative.app.design.NextcloudCardOverflow
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing

/** Board access sheet: who can see the board, what each share may change, and adding people. */
@Composable
internal fun DeckBoardSharingDialog(
    state: DeckUiSharingState,
    currentUserId: String,
    onQueryChange: (String) -> Unit,
    onAdd: (DeckSharee) -> Unit,
    onSetPermissions: (DeckAcl, DeckAclPermissions) -> Unit,
    onRequestRemoval: (DeckAcl) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    val board = state.board
    DeckUiAdaptiveDialog(
        title = "Share ${board.title}",
        supportingText = "Everyone listed here can open this board. Choose what each share may change.",
        onDismiss = onDismiss,
        dismissLabel = "Close",
        busy = state.busy,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
            contentPadding = PaddingValues(NextcloudSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        ) {
            state.errorMessage?.let { message ->
                item(key = "error") { DeckUiInlineError(message) }
            }
            state.notice?.let { notice ->
                item(key = "notice") { DeckToolNotice(notice) }
            }
            if (state.outcomeUnknown) {
                item(key = "reconcile") {
                    OutlinedButton(onClick = onRefresh, enabled = !state.busy) { Text("Refresh board access") }
                }
            }
            item(key = "add-section") {
                DeckShareAddSection(state = state, onQueryChange = onQueryChange)
            }
            items(state.addableResults, key = { "sharee:${it.type.name}:${it.id}" }) { sharee ->
                DeckShareeRow(sharee = sharee, enabled = state.writesEnabled, onAdd = { onAdd(sharee) })
            }
            item(key = "access-heading") {
                Text(
                    "Has access",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = NextcloudSpacing.Medium).semantics { heading() },
                )
            }
            board.owner?.let { owner ->
                item(key = "owner") {
                    DeckAccessRow(
                        name = owner.displayName,
                        detail = "Owner - full access",
                        actions = emptyList(),
                    )
                }
            }
            items(board.acl, key = { "acl:${it.id}" }) { acl ->
                val editability = board.aclEditability(acl, currentUserId)
                DeckAccessRow(
                    name = acl.participant.displayName,
                    detail = "${acl.type.label} - ${acl.permissionSummary()}",
                    actions = if (state.writesEnabled) {
                        acl.permissionActions(editability, onSetPermissions, onRequestRemoval)
                    } else {
                        emptyList()
                    },
                )
            }
            if (board.acl.isEmpty()) {
                item(key = "no-shares") {
                    Text(
                        "This board is not shared with anyone else yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun DeckShareAddSection(
    state: DeckUiSharingState,
    onQueryChange: (String) -> Unit,
) {
    val board = state.board
    Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
        when {
            board.archived -> DeckToolHint("Archived boards keep their current shares. Unarchive the board to add people.")
            !board.canAddShares() -> DeckToolHint("You can see who has access. Adding people needs share permission on this board.")
            else -> {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Add people, groups, or teams") },
                    leadingIcon = { Icon(NextcloudIcons.Search, contentDescription = null) },
                    trailingIcon = if (state.searching) {
                        { CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) }
                    } else {
                        null
                    },
                    singleLine = true,
                    enabled = state.writesEnabled,
                )
                state.searchError?.let { DeckUiInlineError(it) }
                if (!state.searching && state.searchError == null && state.query.isNotBlank() &&
                    state.addableResults.isEmpty()
                ) {
                    DeckToolHint("No one else matches \"${state.query.trim()}\".")
                }
            }
        }
    }
}

@Composable
private fun DeckShareeRow(sharee: DeckSharee, enabled: Boolean, onAdd: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(sharee.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(sharee.type.label, sharee.detail).joinToString(" - "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onAdd, enabled = enabled) { Text("Add") }
    }
}

@Composable
private fun DeckAccessRow(
    name: String,
    detail: String,
    actions: List<NextcloudCardAction>,
) {
    var menuExpanded by remember(name, detail) { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        NextcloudCardOverflow(
            itemLabel = name,
            actions = actions,
            expanded = menuExpanded,
            onExpandedChange = { menuExpanded = it },
        )
    }
}

internal fun DeckAcl.permissionSummary(): String =
    listOfNotNull(
        "Can edit".takeIf { canEdit },
        "Can share".takeIf { canShare },
        "Can manage".takeIf { canManage },
    ).joinToString(", ").ifEmpty { "View only" }

internal fun DeckAcl.permissionActions(
    editability: DeckAclEditability,
    onSetPermissions: (DeckAcl, DeckAclPermissions) -> Unit,
    onRequestRemoval: (DeckAcl) -> Unit,
): List<NextcloudCardAction> = buildList {
    val current = permissions()
    if (editability.canChangeEdit) {
        add(NextcloudCardAction(if (canEdit) "Remove edit access" else "Allow editing") {
            onSetPermissions(this@permissionActions, current.copy(canEdit = !canEdit))
        })
    }
    if (editability.canChangeShare) {
        add(NextcloudCardAction(if (canShare) "Stop resharing" else "Allow resharing") {
            onSetPermissions(this@permissionActions, current.copy(canShare = !canShare))
        })
    }
    if (editability.canChangeManage) {
        add(NextcloudCardAction(if (canManage) "Remove manage access" else "Allow managing") {
            onSetPermissions(this@permissionActions, current.copy(canManage = !canManage))
        })
    }
    if (editability.canRemove) {
        add(NextcloudCardAction("Remove access", destructive = true) { onRequestRemoval(this@permissionActions) })
    }
}

@Composable
internal fun DeckToolNotice(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(NextcloudRadii.Small),
    ) {
        Text(
            message,
            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
internal fun DeckToolHint(message: String) {
    Text(
        message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
