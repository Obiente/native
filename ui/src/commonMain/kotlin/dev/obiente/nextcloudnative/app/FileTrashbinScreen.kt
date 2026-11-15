package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudCardAction
import dev.obiente.nextcloudnative.app.design.NextcloudCardOverflow
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudStatusState
import dev.obiente.nextcloudnative.app.design.nextcloudCardInteractions
import kotlin.time.Clock

/**
 * Native trash bin for the active account. Restoring returns items to their original folder;
 * deleting permanently and emptying always ask for a separate confirmation.
 */
@Composable
internal fun FileTrashbinScreen(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    userId: String,
    onBack: () -> Unit,
    onItemsRestored: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val currentOnRestored by rememberUpdatedState(onItemsRestored)
    val controller = remember(services, session, userId, scope) {
        FileTrashbinController(
            scope = scope,
            userId = userId,
            execute = { request -> services.executeFilesDav(session, request) },
            readCapabilities = { services.executeNextcloudApi(session, trashbinCapabilitiesRequest()) },
            onItemsRestored = { currentOnRestored() },
        )
    }
    LaunchedEffect(controller) { controller.start() }
    PlatformBackHandler(enabled = true) {
        if (controller.state.selectionActive) controller.clearSelection() else onBack()
    }
    FileTrashbinContent(controller.state, controller, onBack)
}

@Composable
private fun FileTrashbinContent(state: FileTrashbinState, controller: FileTrashbinController, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = if (state.selectionActive) "${state.selectedKeys.size} selected" else "Deleted files",
            subtitle = state.items?.let { items ->
                if (state.truncated) "Showing the newest ${items.size}" else itemCount(items.size)
            },
            onBack = { if (state.selectionActive) controller.clearSelection() else onBack() },
            trailingContent = {
                if (state.selectionActive) {
                    TrashbinSelectionActions(state, controller)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = controller::refresh, enabled = !state.refreshing) { Text("Refresh") }
                        if (state.permanentDeleteAllowed) {
                            TextButton(onClick = controller::requestEmpty, enabled = state.canEmpty) {
                                Text("Empty", color = if (state.canEmpty) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        state.report?.let { report ->
            BulkOperationReportCard(
                report = report,
                onDismiss = controller::dismissReport,
                modifier = Modifier.padding(horizontal = NextcloudSpacing.Large, vertical = NextcloudSpacing.Small),
            )
        }
        state.notice?.let { notice ->
            Text(
                notice,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = NextcloudSpacing.Large, vertical = NextcloudSpacing.Small),
            )
        }
        val items = state.items
        val failure = state.failure
        when {
            state.availability == TrashbinAvailability.NotEnabled -> NextcloudStatusState(
                icon = NextcloudIcons.Info,
                title = "The trash bin is not available",
                body = "This server does not keep deleted files for this account. Deleted items cannot be restored here.",
            )
            items == null && failure != null -> ErrorMessage(trashListFailureTitle(failure), controller::refresh)
            items == null -> LoadingMessage("Loading deleted files...")
            else -> {
                if (failure != null) {
                    Text(
                        "${trashListFailureTitle(failure)}. Showing the last loaded list.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = NextcloudSpacing.Large, vertical = NextcloudSpacing.Small),
                    )
                }
                if (state.skippedEntries > 0) {
                    Text(
                        "${itemCount(state.skippedEntries)} could not be read safely and are not shown.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = NextcloudSpacing.Large),
                    )
                }
                if (items.isEmpty()) {
                    EmptyMessage("The trash bin is empty")
                } else {
                    TrashbinList(items, state, controller)
                }
            }
        }
    }
    TrashbinConfirmationDialog(state, controller)
}

@Composable
private fun TrashbinSelectionActions(state: FileTrashbinState, controller: FileTrashbinController) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = controller::selectAll) { Text("Select all") }
        TextButton(onClick = { controller.restore(state.selectedKeys) }) { Text("Restore") }
        if (state.permanentDeleteAllowed) {
            TextButton(onClick = { controller.requestDelete(state.selectedKeys) }) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun TrashbinList(items: List<NextcloudTrashItem>, state: FileTrashbinState, controller: FileTrashbinController) {
    val now = remember(items) { Clock.System.now().epochSeconds }
    LazyColumn(Modifier.fillMaxSize()) {
        items(items, key = NextcloudTrashItem::key) { item ->
            TrashbinRow(item, state, controller, now)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        }
    }
}

@Composable
private fun TrashbinRow(item: NextcloudTrashItem, state: FileTrashbinState, controller: FileTrashbinController, now: Long) {
    var menuOpen by remember(item.key) { mutableStateOf(false) }
    val selected = item.key in state.selectedKeys
    val changeable = state.canChange(item)
    val pending = item.key in state.pendingKeys
    Row(
        modifier = Modifier.fillMaxWidth()
            .semantics {
                this.selected = selected
                if (pending) stateDescription = "Working"
                else if (item.key in state.unconfirmedKeys) stateDescription = "Result not confirmed"
            }
            .nextcloudCardInteractions(
                onOpen = { if (state.selectionActive) controller.toggleSelection(item.key) else menuOpen = true },
                onShowActions = { controller.toggleSelection(item.key) },
                openLabel = if (state.selectionActive) "Toggle selection" else "Show actions",
                actionsLabel = "Select",
            )
            .padding(horizontal = NextcloudSpacing.Large, vertical = NextcloudSpacing.Medium),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
    ) {
        if (state.selectionActive) {
            Checkbox(checked = selected, onCheckedChange = { controller.toggleSelection(item.key) }, enabled = changeable || selected)
        } else {
            Icon(
                if (item.isDirectory) NextcloudIcons.Folder else NextcloudIcons.File,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                trashItemSupportingText(item, now, pending, item.key in state.unconfirmedKeys),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            NextcloudCardOverflow(
                itemLabel = item.name,
                actions = listOfNotNull(
                    NextcloudCardAction("Restore", enabled = changeable) { controller.restore(setOf(item.key)) },
                    NextcloudCardAction("Select", enabled = changeable) { controller.toggleSelection(item.key) },
                    if (state.permanentDeleteAllowed) {
                        NextcloudCardAction("Delete permanently", destructive = true, enabled = changeable) {
                            controller.requestDelete(setOf(item.key))
                        }
                    } else {
                        null
                    },
                ),
                expanded = menuOpen,
                onExpandedChange = { menuOpen = it },
            )
        }
    }
}

private fun trashItemSupportingText(item: NextcloudTrashItem, now: Long, pending: Boolean, unconfirmed: Boolean): String = buildList {
    when {
        pending -> add("Working...")
        unconfirmed -> add("Result not confirmed. Refresh before trying again")
    }
    item.originalFolder?.let { add(if (it.isEmpty()) "From All files" else "From $it") }
    item.deletedAtEpochSeconds?.let { add("Deleted ${compactAgeLabel(it, now).replaceFirstChar(Char::lowercaseChar)}") }
    if (!item.isDirectory) item.sizeBytes?.let { add(formatByteSize(it)) }
}.joinToString(" - ")

@Composable
private fun TrashbinConfirmationDialog(state: FileTrashbinState, controller: FileTrashbinController) {
    val confirmation = state.confirmation ?: return
    val (title, body) = when (confirmation) {
        is TrashbinConfirmation.DeleteItems -> {
            val names = confirmation.keys.mapNotNull { state.item(it)?.name }
            val title = if (names.size == 1) "Delete ${names.first()} permanently?" else "Delete ${itemCount(names.size)} permanently?"
            title to "Permanently deleted items cannot be restored by you or by the server."
        }
        TrashbinConfirmation.Empty ->
            "Empty the trash bin?" to "Every item in the trash bin, including items not shown here, is deleted permanently and cannot be restored."
    }
    AlertDialog(
        onDismissRequest = controller::cancelConfirmation,
        title = { Text(title) },
        text = { Text(body) },
        dismissButton = { OutlinedButton(onClick = controller::cancelConfirmation) { Text("Cancel") } },
        confirmButton = {
            Button(
                onClick = controller::confirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                Text(if (confirmation == TrashbinConfirmation.Empty) "Empty trash bin" else "Delete permanently")
            }
        },
    )
}
