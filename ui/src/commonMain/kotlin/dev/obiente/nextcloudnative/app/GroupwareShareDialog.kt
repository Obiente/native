package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import kotlinx.coroutines.delay

/** Shares the open calendar or address book with people and groups, and manages its public link. */
@Composable
internal fun GroupwareShareDialog(manager: GroupwareSharingManager) {
    val target = manager.target ?: return
    var query by remember(target.href) { mutableStateOf("") }
    var confirmUnshare by remember(target.href) { mutableStateOf<DavSharee?>(null) }
    var confirmUnpublish by remember(target.href) { mutableStateOf(false) }
    var copied by remember(target.href) { mutableStateOf(false) }
    LaunchedEffect(manager, target.href, query) {
        delay(SEARCH_SETTLE_MILLIS)
        manager.search(query)
    }
    val state = manager.state
    AlertDialog(
        onDismissRequest = { if (!manager.busy) manager.dismiss() },
        title = { Text("Share ${target.name}") },
        text = {
            LazyColumn(Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    if (manager.loading || manager.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it.take(100) },
                        label = { Text("Add people or groups") },
                        singleLine = true,
                        enabled = !manager.busy && state != null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    manager.searchMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                items(manager.results, key = { "result:" + it.principal }) { sharee ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        ShareeLabel(sharee, Modifier.weight(1f))
                        TextButton(onClick = { manager.share(sharee) }, enabled = !manager.busy) { Text("Share") }
                    }
                }
                item {
                    HorizontalDivider()
                    Text("Shared with", Modifier.padding(top = 8.dp).semantics { heading() }, style = MaterialTheme.typography.titleSmall)
                    when {
                        state == null && manager.loading -> Text("Loading...", style = MaterialTheme.typography.bodySmall)
                        state != null && state.shares.isEmpty() -> Text(
                            "Only you can see this.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(state?.shares.orEmpty(), key = { "share:" + it.sharee.principal }) { share ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ShareeLabel(share.sharee, Modifier.weight(1f))
                        Text("Can edit", style = MaterialTheme.typography.bodySmall)
                        Switch(
                            checked = share.writable,
                            onCheckedChange = { manager.setWritable(share, it) },
                            enabled = !manager.busy,
                            modifier = Modifier.semantics { contentDescription = "${share.sharee.displayName} can edit" },
                        )
                        IconButton(onClick = { confirmUnshare = share.sharee }, enabled = !manager.busy) {
                            Icon(NextcloudIcons.Close, "Stop sharing with ${share.sharee.displayName}")
                        }
                    }
                }
                if (target.publishable && state?.canPublish == true) item {
                    HorizontalDivider()
                    Text("Public link", Modifier.padding(top = 8.dp).semantics { heading() }, style = MaterialTheme.typography.titleSmall)
                    val url = state.publishUrl
                    if (url == null) {
                        Text("Anyone with the link can view this calendar, without signing in.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = { manager.setPublished(true) }, enabled = !manager.busy) { Text("Create link") }
                    } else {
                        Text(url, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { copied = manager.copyLink() }) {
                                Icon(NextcloudIcons.Copy, null, Modifier.size(16.dp))
                                Text(if (copied) "Copied" else "Copy link", Modifier.padding(start = 6.dp))
                            }
                            TextButton(onClick = { confirmUnpublish = true }, enabled = !manager.busy) {
                                Text("Delete link", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
                manager.error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            }
        },
        confirmButton = { TextButton(onClick = manager::dismiss, enabled = !manager.busy) { Text("Done") } },
        dismissButton = { TextButton(onClick = manager::refresh, enabled = !manager.busy && !manager.loading) { Text("Refresh") } },
    )
    confirmUnshare?.let { sharee ->
        GroupwareConfirmDialog(
            title = "Stop sharing with ${sharee.displayName}?",
            message = "${sharee.displayName} will no longer see ${target.name}. You can share it again later.",
            action = "Stop sharing",
            onConfirm = { confirmUnshare = null; manager.unshare(sharee) },
            onDismiss = { confirmUnshare = null },
        )
    }
    if (confirmUnpublish) {
        GroupwareConfirmDialog(
            title = "Delete the public link?",
            message = "The link stops working for everyone who has it. A new link would be a different address.",
            action = "Delete link",
            onConfirm = { confirmUnpublish = false; manager.setPublished(false) },
            onDismiss = { confirmUnpublish = false },
        )
    }
}

@Composable
private fun ShareeLabel(sharee: DavSharee, modifier: Modifier) {
    Column(modifier) {
        Text(sharee.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(if (sharee.kind == DavShareeKind.Group) "Group" else "Person",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A separate, explicit confirmation for an action that takes something away. */
@Composable
internal fun GroupwareConfirmDialog(
    title: String,
    message: String,
    action: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    busy: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = { Text(message) },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) { Text(action) }
        },
    )
}

private const val SEARCH_SETTLE_MILLIS = 350L
