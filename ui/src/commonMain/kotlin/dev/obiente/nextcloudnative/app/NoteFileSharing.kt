package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import kotlinx.coroutines.CancellationException

/**
 * Resolution of the Files record that stores a note.
 *
 * The Notes API documents no file path. Its responses carry an `internalPath` hint that the
 * Notes documentation marks as internal, and the Notes implementation uses the file ID as the
 * note ID. Neither alone is a contract, so sharing is offered only when the Files record found
 * by that ID is a file at exactly the hinted path. Otherwise the note stays unshareable here.
 */
sealed interface NoteFileResolution {
    data object Resolving : NoteFileResolution
    data class Verified(val file: NextcloudFile) : NoteFileResolution
    data class Unavailable(val message: String) : NoteFileResolution
}

fun verifyNoteFile(note: NextcloudNote, candidate: NextcloudFile?): NoteFileResolution {
    val hinted = note.internalPath?.normalizedNoteFilePath()
        ?: return NoteFileResolution.Unavailable("Notes did not report where this note is stored.")
    val file = candidate
        ?: return NoteFileResolution.Unavailable("The file for this note was not found in Files.")
    return when {
        file.isDirectory || file.fileId != note.id ->
            NoteFileResolution.Unavailable("The note's file could not be matched in Files.")
        file.path.normalizedNoteFilePath() != hinted ->
            NoteFileResolution.Unavailable("The note's file location could not be confirmed. Refresh and try again.")
        else -> NoteFileResolution.Verified(file)
    }
}

private fun String.normalizedNoteFilePath(): String? =
    trim().trim('/').takeIf { it.isNotEmpty() && it.none(Char::isISOControl) && "/../" !in "/$it/" }

/** Shares the note's file through the same share manager the Files browser uses. */
@Composable
internal fun NoteFileShareHost(
    note: NextcloudNote,
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    access: NotesFilesAccess,
    onDismiss: () -> Unit,
    onMutationRunningChanged: (Boolean) -> Unit,
) {
    var resolution by remember(note.id, note.internalPath) { mutableStateOf<NoteFileResolution>(NoteFileResolution.Resolving) }
    LaunchedEffect(note.id, note.internalPath, access.userId) {
        resolution = try {
            val file = services.resolveFilesById(session, access.userId, listOf(note.id))[note.id]
            verifyNoteFile(note, file)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            NoteFileResolution.Unavailable(failure.message ?: "The note's file could not be loaded.")
        }
    }
    when (val current = resolution) {
        is NoteFileResolution.Verified -> MediaShareDialog(
            file = current.file,
            session = session,
            services = services,
            capabilities = access.sharing,
            onDismiss = onDismiss,
            onMutationRunningChanged = onMutationRunningChanged,
        )
        else -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Share ${note.title}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium)) {
                    if (current is NoteFileResolution.Unavailable) {
                        Text(current.message)
                    } else {
                        Text("Finding the note's file...")
                        CircularProgressIndicator()
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        )
    }
}

internal fun NotesFilesAccess.canShareNotes(): Boolean = sharing.supportsAnyCreation

internal fun NextcloudServerInfo.notesFilesAccess(): NotesFilesAccess? =
    userId.takeIf(String::isNotBlank)?.let { NotesFilesAccess(it, fileSharing) }
