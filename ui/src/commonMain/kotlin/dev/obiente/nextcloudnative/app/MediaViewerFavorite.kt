package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Sets the Files favorite of the photo or video shown in the viewer. Both choices are offered
 * because the viewer's record can be older than the server state; the favorite PROPPATCH sets an
 * absolute value, so choosing the current state again changes nothing.
 */
@Composable
internal fun MediaFavoriteDialog(
    file: NextcloudFile,
    session: NextcloudSession,
    userId: String,
    services: NextcloudPlatformServices,
    writeFence: FileWriteFence,
    onDismiss: () -> Unit,
    onMutationRunningChanged: (Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var running by remember(file.path) { mutableStateOf(false) }
    var result by remember(file.path) { mutableStateOf<String?>(null) }
    var failed by remember(file.path) { mutableStateOf(false) }

    fun set(favorite: Boolean) {
        writeFence.writeBlock(file.path)?.let { block ->
            result = block.message
            failed = true
            return
        }
        running = true
        onMutationRunningChanged(true)
        scope.launch {
            try {
                val followUp = services.setFileFavorite(session, userId, file, favorite)
                result = followUp.reportFor(if (favorite) "${file.name} is a favorite" else "${file.name} is no longer a favorite")
                failed = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: FileMutationOutcomeUnknownException) {
                result = "Couldn't confirm the change. Open the photo again to check before retrying."
                failed = true
            } catch (failure: Exception) {
                result = failure.message?.take(200) ?: "The favorite could not be changed."
                failed = true
            } finally {
                running = false
                onMutationRunningChanged(false)
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text("Favorite") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium)) {
                Text(if (file.favorite) "${file.name} was a favorite when it was loaded." else "Mark ${file.name} as a favorite in Files and Photos.")
                if (running) Text("Saving...", style = MaterialTheme.typography.bodySmall)
                result?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        dismissButton = {
            if (result != null && !failed) {
                TextButton(onClick = onDismiss) { Text("Close") }
            } else {
                OutlinedButton(onClick = { set(false) }, enabled = !running) { Text("Remove favorite") }
            }
        },
        confirmButton = {
            if (result != null && !failed) {
                Button(onClick = onDismiss) { Text("Done") }
            } else {
                Button(onClick = { set(true) }, enabled = !running) { Text("Add to favorites") }
            }
        },
    )
}
