package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing

@Composable
internal fun ContactDetailDialog(
    contact: GroupwareContact,
    canEdit: Boolean,
    editLoading: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!editLoading) onDismiss() },
        icon = { Icon(NextcloudIcons.app("contacts"), contentDescription = null) },
        title = { Text(contact.displayName) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                contact.organization?.let { Text(it, fontWeight = FontWeight.SemiBold) }
                contact.emails.forEach { Text(it) }
                contact.phones.forEach { Text(it) }
                contact.address?.let { Text(it) }
                contact.birthday?.let { Text("Birthday: $it") }
                contact.notes?.let { Text(it) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            if (canEdit) TextButton(onClick = onEdit, enabled = !editLoading) {
                Text(if (editLoading) "Loading contact" else "Edit")
            } else {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
        dismissButton = {
            if (canEdit) TextButton(onClick = onDelete, enabled = !editLoading) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
    )
}
