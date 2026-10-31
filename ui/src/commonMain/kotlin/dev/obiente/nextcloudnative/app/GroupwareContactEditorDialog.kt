package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import dev.obiente.nextcloudnative.app.design.NextcloudFilterChip
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import kotlinx.serialization.json.Json

@Composable
internal fun ContactEditorDialog(
    contact: GroupwareContact?,
    addressBooks: List<GroupwareAddressBook>,
    error: String?,
    onDismiss: () -> Unit,
    mutationInProgress: Boolean,
    recoveryAvailable: Boolean = false,
    onOpenRecovery: () -> Unit = {},
    navigationRequest: NextcloudPendingNavigationRequest? = null,
    onNavigationConfirmed: (NextcloudPendingNavigationRequest) -> Unit = {},
    onNavigationDiscardConfirmed: (NextcloudPendingNavigationRequest) -> Unit = onNavigationConfirmed,
    onNavigationCancelled: (NextcloudPendingNavigationRequest) -> Unit = {},
    /** Groups used by other contacts, offered while adding this one to a group. */
    knownGroups: List<String> = emptyList(),
    onSave: (ContactDraft, GroupwareAddressBook) -> Unit,
) {
    val initialCard = remember(contact) { contact?.card() ?: ContactCard(name = "") }
    val editorStateKey = contact?.href ?: "new-contact"
    // The draft is kept as one bounded text value, so it restores after recreation on every platform.
    var cardText by rememberSaveable(editorStateKey) { mutableStateOf(ContactEditorJson.encodeToString(ContactCard.serializer(), initialCard)) }
    val card = remember(cardText) {
        try {
            ContactEditorJson.decodeFromString(ContactCard.serializer(), cardText)
        } catch (_: IllegalArgumentException) {
            initialCard
        }
    }
    fun update(changed: ContactCard) {
        cardText = ContactEditorJson.encodeToString(ContactCard.serializer(), changed)
    }
    val initialAddressBookHref = addressBooks.firstOrNull()?.href
    var selectedAddressBookHref by rememberSaveable(editorStateKey) {
        mutableStateOf(initialAddressBookHref)
    }
    val addressBook = addressBooks.firstOrNull { it.href == selectedAddressBookHref }
        ?: addressBooks.firstOrNull()
    val validation = card.validationMessage()
    val dirty = card != initialCard || addressBook?.href != initialAddressBookHref
    val hasDavChanges = card.normalized() != initialCard.normalized() || addressBook?.href != initialAddressBookHref
    var confirmNavigationDiscard by remember(contact) { mutableStateOf(false) }
    LaunchedEffect(navigationRequest?.identity, dirty, mutationInProgress) {
        val request = navigationRequest
        when {
            request == null -> confirmNavigationDiscard = false
            mutationInProgress -> confirmNavigationDiscard = false
            dirty -> confirmNavigationDiscard = true
            else -> onNavigationConfirmed(request)
        }
    }
    val enabled = !mutationInProgress
    AlertDialog(
        onDismissRequest = { if (!mutationInProgress) onDismiss() },
        title = { Text(if (contact == null) "New contact" else "Edit contact") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                item { ContactTextField("Name", card.name, enabled) { update(card.copy(name = it)) } }
                item { ContactTextField("Organization", card.organization, enabled) { update(card.copy(organization = it)) } }
                item { ContactTextField("Job title", card.jobTitle, enabled) { update(card.copy(jobTitle = it)) } }
                item {
                    ContactValueRows("Emails", "email", card.emails,
                        listOf(ContactValueLabel.Home, ContactValueLabel.Work, ContactValueLabel.Other), KeyboardType.Email, enabled) {
                        update(card.copy(emails = it))
                    }
                }
                item {
                    ContactValueRows("Phone numbers", "phone", card.phones, ContactValueLabel.entries.toList(), KeyboardType.Phone, enabled) {
                        update(card.copy(phones = it))
                    }
                }
                item { ContactAddressRows(card.addresses, enabled) { update(card.copy(addresses = it)) } }
                item {
                    ContactValueRows("Websites", "website", card.websites,
                        listOf(ContactValueLabel.Home, ContactValueLabel.Work, ContactValueLabel.Other), KeyboardType.Uri, enabled) {
                        update(card.copy(websites = it))
                    }
                }
                item {
                    ContactTextField(
                        "Birthday", card.birthday, enabled,
                        supportingText = "YYYY-MM-DD, or --MM-DD without a year",
                        isError = card.birthday.isNotBlank() && card.birthday.trim().contactBirthdayCompact() == null,
                    ) { update(card.copy(birthday = it.take(10))) }
                }
                item { ContactGroupsField(card.groups, knownGroups, enabled) { update(card.copy(groups = it)) } }
                item { ContactTextField("Notes", card.notes, enabled, minLines = 2) { update(card.copy(notes = it)) } }
                if (addressBooks.size > 1) item {
                    Text("Address book", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                        addressBooks.forEach { candidate ->
                            NextcloudFilterChip(
                                selected = candidate == addressBook,
                                onClick = { selectedAddressBookHref = candidate.href },
                                label = { Text(candidate.displayName) },
                                enabled = enabled,
                            )
                        }
                    }
                }
                if (validation != null && card.name.isNotBlank()) item { Text(validation, color = MaterialTheme.colorScheme.error) }
                error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            }
        },
        confirmButton = {
            Button(
                enabled = validation == null && addressBook != null && hasDavChanges && !mutationInProgress,
                onClick = { onSave(card.normalized().toContactDraft(), requireNotNull(addressBook)) },
            ) { Text("Save") }
        },
        dismissButton = {
            if (recoveryAvailable) {
                OutlinedButton(onClick = onOpenRecovery) { Text("Recovery options") }
            } else {
                OutlinedButton(onClick = onDismiss, enabled = !mutationInProgress, modifier = Modifier) { Text("Cancel") }
            }
        },
    )

    if (confirmNavigationDiscard) {
        navigationRequest?.let { request ->
            AlertDialog(
                onDismissRequest = {
                    confirmNavigationDiscard = false
                    onNavigationCancelled(request)
                },
                title = { Text("Discard unsaved contact changes?") },
                text = { Text("Your contact changes have not been saved.") },
                dismissButton = {
                    TextButton(
                        onClick = {
                            confirmNavigationDiscard = false
                            onNavigationCancelled(request)
                        },
                    ) { Text("Keep editing") }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            confirmNavigationDiscard = false
                            onNavigationDiscardConfirmed(request)
                        },
                    ) { Text("Discard") }
                },
            )
        }
    }
}

private val ContactEditorJson = Json { ignoreUnknownKeys = true }
