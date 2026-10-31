package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudCardAction
import dev.obiente.nextcloudnative.app.design.NextcloudCardOverflow
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing

/** Groups with member counts; create and delete are password-confirmed. */
@Composable
internal fun AdminGroupsPane(controller: AdminUsersController, modifier: Modifier = Modifier) {
    val state = controller.state
    var newGroup by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<AdminGroupMutation?>(null) }
    LaunchedEffect(controller) { controller.loadGroups() }
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = newGroup,
                onValueChange = { newGroup = it.take(255) },
                modifier = Modifier.weight(1f).testTag("admin-new-group"),
                label = { Text("New group name") },
                singleLine = true,
                enabled = controller.canWrite,
            )
            Button(
                onClick = { pending = AdminGroupMutation.Create(newGroup.trim(), newGroup.trim()) },
                enabled = controller.canWrite && newGroup.trim().isGroupIdentifier() && state.groups.none { it.id == newGroup.trim() },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Create") }
        }
        state.mutationMessage?.let { message ->
            Text(
                message,
                modifier = Modifier.padding(horizontal = NextcloudSpacing.Medium).semantics { liveRegion = LiveRegionMode.Polite },
                color = if (state.mutationFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(NextcloudSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            state.groupsFailure?.let { failure -> item(key = "failure") { Text(failure, color = MaterialTheme.colorScheme.error) } }
            if (!state.groupsLoaded) item(key = "loading") { Text("Loading groups...") }
            items(state.groups, key = AdminGroup::id) { group ->
                AdminGroupRow(group, canWrite = controller.canWrite, onDelete = { pending = AdminGroupMutation.Delete(group.id) })
            }
        }
    }
    pending?.let { mutation ->
        AccountPasswordConfirmationDialog(
            title = when (mutation) {
                is AdminGroupMutation.Create -> "Create group ${mutation.groupId}?"
                is AdminGroupMutation.Delete -> "Delete group ${mutation.groupId}?"
            },
            description = when (mutation) {
                is AdminGroupMutation.Create -> "The group is created without members."
                is AdminGroupMutation.Delete ->
                    "Members stay, but they lose shares, app access, and settings limited to this group. This cannot be undone."
            },
            confirmLabel = if (mutation is AdminGroupMutation.Delete) "Delete group" else "Create group",
            destructive = mutation is AdminGroupMutation.Delete,
            busy = state.mutating,
            onConfirm = { secret ->
                controller.mutateGroup(mutation, secret) {
                    pending = null
                    if (mutation is AdminGroupMutation.Create) newGroup = ""
                }
            },
            onDismiss = { pending = null },
        )
    }
}

@Composable
private fun AdminGroupRow(group: AdminGroup, canWrite: Boolean, onDelete: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(NextcloudRadii.Card)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = NextcloudSpacing.Medium),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(group.displayName, style = MaterialTheme.typography.titleSmall)
                Text(
                    listOfNotNull(
                        group.id.takeIf { it != group.displayName },
                        group.userCount?.let { "$it member(s)" },
                        group.disabledCount?.takeIf { it > 0 }?.let { "$it disabled" },
                    ).joinToString(" - "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // The admin group holds the server administrators and cannot be removed.
            if (canWrite && group.id != "admin") {
                NextcloudCardOverflow(
                    itemLabel = group.displayName,
                    expanded = menuOpen,
                    onExpandedChange = { menuOpen = it },
                    actions = listOf(NextcloudCardAction("Delete group", destructive = true, onClick = onDelete)),
                )
            }
        }
    }
}
