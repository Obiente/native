package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.nativeui.runtime.nativeContactEmailUri
import dev.obiente.nextcloudnative.nativeui.runtime.nativeContactPhoneUri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
    onOpenUri: ((String) -> Unit)? = null,
    onCopy: ((String, String) -> Boolean)? = null,
) {
    AlertDialog(
        onDismissRequest = { if (!editLoading) onDismiss() },
        icon = { Icon(NextcloudIcons.app("contacts"), contentDescription = null) },
        title = { Text(contact.displayName) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                contact.organization?.let { Text(it, fontWeight = FontWeight.SemiBold) }
                // Each way to reach someone is one tap away, handed to the system's mail or phone app.
                contact.emails.forEach { email ->
                    ContactReachRow(NextcloudIcons.Mail, email, "Email $email",
                        nativeContactEmailUri(email)?.takeIf { onOpenUri != null }?.let { uri -> { onOpenUri?.invoke(uri) } })
                }
                contact.phones.forEach { phone ->
                    ContactReachRow(NextcloudIcons.Phone, phone, "Call $phone",
                        nativeContactPhoneUri(phone)?.takeIf { onOpenUri != null }?.let { uri -> { onOpenUri?.invoke(uri) } })
                }
                contact.address?.let { address ->
                    ContactReachRow(NextcloudIcons.Place, address, "Copy address",
                        onCopy?.let { copy -> { copy("Address", address) } }, actionIcon = NextcloudIcons.Copy)
                }
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

@Composable
private fun ContactReachRow(
    icon: ImageVector,
    value: String,
    actionLabel: String,
    onAction: (() -> Unit)?,
    actionIcon: ImageVector? = null,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .then(if (onAction == null) Modifier else Modifier.clickable(role = Role.Button, onClickLabel = actionLabel, onClick = onAction))
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Text(value, Modifier.weight(1f), color = if (onAction != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        if (actionIcon != null && onAction != null) Icon(actionIcon, contentDescription = null, modifier = Modifier.size(18.dp))
    }
}
