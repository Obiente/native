package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.obiente.nextcloudnative.app.design.NextcloudCardAction
import dev.obiente.nextcloudnative.app.design.NextcloudCardOverflow
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class AlbumManagementDialog { Rename, Remove }

/**
 * Album header actions: rename and delete for an owned album, leave for a shared one. Every
 * change is confirmed separately; [onAlbumChanged] asks the caller to re-read the album catalog
 * so the authoritative server state replaces the local copy.
 */
@Composable
internal fun NativeAlbumManagementMenu(
    collection: NativeMediaCollection,
    currentUserId: String?,
    existingAlbums: List<NativeMediaCollection>,
    session: NextcloudSession,
    mutationService: NativeMediaCollectionMutationService,
    onAlbumChanged: (removed: Boolean) -> Unit,
) {
    if (collection.type != NativeMediaCollectionType.Album) return
    val owned = collection.ownerUserId != null && collection.ownerUserId == currentUserId
    val scope = rememberCoroutineScope()
    var menuOpen by remember(collection.key) { mutableStateOf(false) }
    var dialog by remember(collection.key) { mutableStateOf<AlbumManagementDialog?>(null) }
    var newName by remember(collection.key) { mutableStateOf(collection.name) }
    var running by remember(collection.key) { mutableStateOf(false) }
    var error by remember(collection.key) { mutableStateOf<String?>(null) }

    NextcloudCardOverflow(
        itemLabel = collection.name,
        actions = buildList {
            if (owned) add(NextcloudCardAction("Rename album") { newName = collection.name; error = null; dialog = AlbumManagementDialog.Rename })
            add(
                NextcloudCardAction(if (owned) "Delete album" else "Leave album", destructive = true) {
                    error = null
                    dialog = AlbumManagementDialog.Remove
                },
            )
        },
        expanded = menuOpen,
        onExpandedChange = { menuOpen = it },
    )

    val current = dialog ?: return
    val plan = when (current) {
        AlbumManagementDialog.Rename -> planRenameMediaAlbum(collection, newName, currentUserId, existingAlbums)
        AlbumManagementDialog.Remove -> planDeleteOrLeaveMediaAlbum(collection, currentUserId)
    }
    fun close() {
        if (!running) dialog = null
    }
    AlertDialog(
        onDismissRequest = ::close,
        title = { Text(if (current == AlbumManagementDialog.Rename) "Rename album" else plan.confirmation.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium)) {
                Text(plan.confirmation.message)
                if (current == AlbumManagementDialog.Rename) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it.take(MAX_ALBUM_NAME_INPUT) },
                        label = { Text("Album name") },
                        singleLine = true,
                        enabled = !running,
                    )
                }
                val note = error ?: plan.disabledReason?.takeIf { current == AlbumManagementDialog.Remove || newName.isNotBlank() }
                note?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (running) Text("Updating album...", style = MaterialTheme.typography.bodySmall)
            }
        },
        dismissButton = { TextButton(onClick = ::close, enabled = !running) { Text("Cancel") } },
        confirmButton = {
            Button(
                enabled = plan.enabled && !running,
                colors = if (current == AlbumManagementDialog.Remove) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.buttonColors()
                },
                onClick = {
                    running = true
                    error = null
                    scope.launch {
                        val outcome = try {
                            mutationService.executeConfirmed(session, plan, confirmed = true)
                            null
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: IllegalArgumentException) {
                            failure.message ?: "The album was not changed."
                        } catch (_: Exception) {
                            // A transport failure may come after the server applied the change.
                            UNKNOWN_ALBUM_RESULT
                        }
                        running = false
                        if (outcome == null) {
                            dialog = null
                            onAlbumChanged(current == AlbumManagementDialog.Remove)
                        } else {
                            error = outcome
                            if (outcome == UNKNOWN_ALBUM_RESULT) {
                                // Never repeat a structural album change blindly; refresh first.
                                dialog = null
                                onAlbumChanged(false)
                            }
                        }
                    }
                },
            ) { Text(plan.confirmation.confirmLabel) }
        },
    )
}

private const val UNKNOWN_ALBUM_RESULT = "Couldn't confirm the album change. The albums were refreshed; check before trying again."
private const val MAX_ALBUM_NAME_INPUT = 200
