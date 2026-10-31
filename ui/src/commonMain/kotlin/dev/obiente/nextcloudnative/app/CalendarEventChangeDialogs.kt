package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing

/** Asks whether a change to a repeating event is for this occurrence or for the whole series. */
@Composable
internal fun CalendarChangeScopeDialog(
    event: GroupwareCalendarEvent,
    onChoose: (CalendarChangeScope) -> Unit,
    onDismiss: () -> Unit,
) {
    val occurrence = event.asSingleOccurrence() != null
    val series = event.seriesMaster() != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit repeating event") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                Text("${event.title} repeats. Choose what to change.")
                OutlinedButton(onClick = { onChoose(CalendarChangeScope.Occurrence) }, enabled = occurrence,
                    modifier = Modifier.fillMaxWidth()) { Text("Only this occurrence") }
                OutlinedButton(onClick = { onChoose(CalendarChangeScope.Series) }, enabled = series,
                    modifier = Modifier.fillMaxWidth()) { Text("Every occurrence") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Confirms deleting an event. A repeating event offers removing one occurrence, which keeps the
 * rest of the series, or the complete series; both are explicit, separate choices.
 */
@Composable
internal fun CalendarEventDeleteDialog(
    event: GroupwareCalendarEvent,
    busy: Boolean,
    error: String?,
    onConfirm: (CalendarChangeScope) -> Unit,
    onDismiss: () -> Unit,
) {
    val repeating = event.isPartOfSeries
    val meeting = event.invitation().isMeeting
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Delete ${event.title}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                Text(
                    if (repeating) "This event repeats. Delete only the occurrence on ${event.start.take(8).displayCalendarDate()}, " +
                        "or every occurrence of the series."
                    else "This removes the event from its Nextcloud calendar. Deleted events stay in the calendar trash bin " +
                        "for a while, where you can restore them.",
                )
                if (meeting) Text("People invited to it are told it was cancelled.", color = MaterialTheme.colorScheme.error)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (repeating) {
                    OutlinedButton(
                        onClick = { onConfirm(CalendarChangeScope.Occurrence) },
                        enabled = !busy && event.occurrenceId() != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Delete this occurrence") }
                }
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
        confirmButton = {
            Button(
                enabled = !busy,
                onClick = { onConfirm(CalendarChangeScope.Series) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(if (repeating) "Delete every occurrence" else "Delete")
            }
        },
    )
}

/** Explains a change the server refused, from its status. */
internal fun calendarEventRejectionMessage(action: String, status: Int): String = when (status) {
    401, 403 -> "$action failed: you don't have permission to change this calendar."
    404, 410 -> "$action failed: the event no longer exists. Refresh the calendar."
    409, 412 -> "$action failed: the event changed on the server. Refresh and try again."
    413 -> "$action failed: the event is too large for the server."
    else -> "$action failed (HTTP $status)."
}
