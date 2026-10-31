package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.LocalNextcloudWorkspaceCapabilities
import dev.obiente.nextcloudnative.app.design.NextcloudDetailSheet
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme
import dev.obiente.nextcloudnative.nativeui.runtime.nativeContactEmailUri
import dev.obiente.nextcloudnative.nativeui.runtime.nativeContactPhoneUri

/** Edit and delete availability for the contact shown in details. */
internal data class ContactDetailActions(
    val canEdit: Boolean,
    val editLoading: Boolean,
    val onEdit: () -> Unit,
    val onDelete: () -> Unit,
    /** Hands a mailto: or tel: link to the system, and copies text; null hides those actions. */
    val onOpenUri: ((String) -> Unit)? = null,
    val onCopy: ((String, String) -> Boolean)? = null,
)

/**
 * Contact details where the list is too narrow for the side inspector. Phones get a bottom sheet
 * over the list; narrow desktop windows keep the centered dialog that suits a pointer. The host's
 * saveable selection decides whether either is shown, so both reopen after recreation.
 */
@Composable
internal fun ContactDetailOverlay(
    contact: GroupwareContact,
    actions: ContactDetailActions,
    error: String?,
    onDismiss: () -> Unit,
) {
    if (LocalNextcloudWorkspaceCapabilities.current.isDesktop) {
        ContactDetailDialog(contact, actions.canEdit, actions.editLoading, error, onDismiss, actions.onEdit, actions.onDelete,
            actions.onOpenUri, actions.onCopy)
        return
    }
    NextcloudDetailSheet(
        title = contact.displayName,
        onDismiss = { if (!actions.editLoading) onDismiss() },
        closeLabel = "Close contact",
    ) {
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                .padding(horizontal = NextcloudSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
        ) {
            ContactFacts(contact, error, actions.onOpenUri, actions.onCopy)
        }
        ContactActionRow(actions, Modifier.padding(NextcloudSpacing.Large))
    }
}

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
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium)) {
                ContactFacts(contact, error, onOpenUri, onCopy)
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

/** Wide-list inspector: the contact's name and actions stay put while its details scroll. */
@Composable
internal fun ContactInspector(
    contact: GroupwareContact,
    actions: ContactDetailActions,
    error: String?,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ContactAvatar(contact)
            Column(Modifier.weight(1f)) {
                Text(
                    contact.displayName,
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineSmall,
                )
                contact.organization?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        ContactActionRow(actions, Modifier.padding(horizontal = NextcloudSpacing.Large))
        HorizontalDivider(Modifier.padding(top = NextcloudSpacing.Medium), color = MaterialTheme.colorScheme.outlineVariant)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(NextcloudSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
        ) {
            ContactFacts(contact, error, actions.onOpenUri, actions.onCopy, includeOrganization = false)
        }
    }
}

@Composable
internal fun ContactAvatar(contact: GroupwareContact) {
    Surface(shape = CircleShape, color = NextcloudTheme.colors.appIconContainer) {
        Text(
            contact.initials(),
            modifier = Modifier.padding(NextcloudSpacing.Medium),
            color = NextcloudTheme.colors.appIcon,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun ContactActionRow(actions: ContactDetailActions, modifier: Modifier) {
    if (!actions.canEdit) return
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
        OutlinedButton(onClick = actions.onEdit, enabled = !actions.editLoading) {
            Text(if (actions.editLoading) "Loading contact" else "Edit")
        }
        TextButton(onClick = actions.onDelete, enabled = !actions.editLoading) {
            Text("Delete", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun ColumnScope.ContactFacts(
    contact: GroupwareContact,
    error: String?,
    onOpenUri: ((String) -> Unit)?,
    onCopy: ((String, String) -> Boolean)?,
    includeOrganization: Boolean = true,
) {
    val card = remember(contact.rawVCard) { contact.card() }
    if (includeOrganization) contact.organization?.let { ContactFact("Organization", it) }
    card.jobTitle.takeIf(String::isNotBlank)?.let { ContactFact("Job title", it) }
    // Each way to reach someone is one tap away, handed to the system's mail or phone app.
    card.emails.forEach { email ->
        ContactFact(email.label.factTitle("Email"), email.value, onOpenUri?.let { open ->
            nativeContactEmailUri(email.value)?.let { uri -> ContactFactAction("Email ${email.value}", NextcloudIcons.Mail) { open(uri) } }
        })
    }
    card.phones.forEach { phone ->
        ContactFact(phone.label.factTitle("Phone"), phone.value, onOpenUri?.let { open ->
            nativeContactPhoneUri(phone.value)?.let { uri -> ContactFactAction("Call ${phone.value}", NextcloudIcons.Phone) { open(uri) } }
        })
    }
    card.addresses.forEach { entry ->
        val address = entry.singleLine
        ContactFact(entry.label.factTitle("Address"), address,
            onCopy?.let { copy -> ContactFactAction("Copy address", NextcloudIcons.Copy) { copy("Address", address) } })
    }
    card.websites.forEach { website ->
        // Only a plain web address is handed to the browser; anything else stays text.
        val webAddress = website.value.takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) }
        ContactFact(website.label.factTitle("Website"), website.value, onOpenUri?.let { open ->
            webAddress?.let { address -> ContactFactAction("Open $address", NextcloudIcons.FormatLink) { open(address) } }
        })
    }
    card.birthday.takeIf(String::isNotBlank)?.let { ContactFact("Birthday", it.displayContactBirthday()) }
    card.groups.takeIf(List<String>::isNotEmpty)?.let { ContactFact("Groups", it.joinToString(", ")) }
    contact.notes?.let { ContactFact("Notes", it) }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

private class ContactFactAction(val label: String, val icon: ImageVector, val onClick: () -> Unit)

@Composable
private fun ContactFact(label: String, value: String, action: ContactFactAction? = null) {
    Row(
        Modifier.fillMaxWidth().then(
            if (action == null) Modifier
            else Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = action.label, onClick = action.onClick),
        ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium,
                color = if (action != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        }
        action?.let { Icon(it.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) }
    }
}

private fun ContactValueLabel.factTitle(kind: String): String =
    if (this == ContactValueLabel.Other) kind else "$kind, ${title.lowercase()}"

/** A birthday for reading: `15 January 1990`, or `15 January` without a year. */
internal fun String.displayContactBirthday(): String {
    val compact = contactBirthdayCompact() ?: return this
    return if (compact.startsWith("--")) {
        "2000${compact.drop(2)}".displayCalendarDate().removeSuffix(" 2000")
    } else {
        compact.displayCalendarDate()
    }
}

internal fun GroupwareContact.initials(): String = displayName.split(' ')
    .filter(String::isNotBlank)
    .take(2)
    .mapNotNull(String::firstOrNull)
    .joinToString("")
    .uppercase()
    .ifBlank { "?" }
