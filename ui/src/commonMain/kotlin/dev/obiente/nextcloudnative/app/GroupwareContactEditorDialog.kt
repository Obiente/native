package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import dev.obiente.nextcloudnative.app.design.NextcloudFilterChip
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing

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
    onSave: (ContactDraft, GroupwareAddressBook) -> Unit,
) {
    val initialDraft = remember(contact) {
        ContactDraft(
            name = contact?.displayName.orEmpty(),
            email = contact?.emails?.firstOrNull().orEmpty(),
            phone = contact?.phones?.firstOrNull().orEmpty(),
            organization = contact?.organization.orEmpty(),
            address = contact?.address.orEmpty(),
            notes = contact?.notes.orEmpty(),
        )
    }
    val editorStateKey = contact?.href ?: "new-contact"
    var name by rememberSaveable(editorStateKey) { mutableStateOf(initialDraft.name) }
    var email by rememberSaveable(editorStateKey) { mutableStateOf(initialDraft.email) }
    var phone by rememberSaveable(editorStateKey) { mutableStateOf(initialDraft.phone) }
    var organization by rememberSaveable(editorStateKey) { mutableStateOf(initialDraft.organization) }
    var address by rememberSaveable(editorStateKey) { mutableStateOf(initialDraft.address) }
    var notes by rememberSaveable(editorStateKey) { mutableStateOf(initialDraft.notes) }
    val initialAddressBookHref = addressBooks.firstOrNull()?.href
    var selectedAddressBookHref by rememberSaveable(editorStateKey) {
        mutableStateOf(initialAddressBookHref)
    }
    val addressBook = addressBooks.firstOrNull { it.href == selectedAddressBookHref }
        ?: addressBooks.firstOrNull()
    val currentDraft = ContactDraft(name, email, phone, organization, address, notes)
    val emailIsSingleValue = groupwareContactEmailIsSingleValue(email)
    val dirty = contactDraftIsDirty(
        initialDraft,
        currentDraft,
        initialAddressBookHref,
        addressBook?.href,
    )
    val hasDavChanges = contactDraftHasDavChanges(
        initialDraft,
        currentDraft,
        initialAddressBookHref,
        addressBook?.href,
    )
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
    AlertDialog(
        onDismissRequest = { if (!mutationInProgress) onDismiss() },
        title = { Text(if (contact == null) "New contact" else "Edit contact") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                item {
                    OutlinedTextField(
                        name,
                        { name = it },
                        label = { Text("Name") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !mutationInProgress,
                    )
                }
                item {
                    OutlinedTextField(
                        email,
                        { email = it },
                        label = { Text("Email") },
                        modifier = Modifier.fillMaxWidth(),
                        isError = !emailIsSingleValue,
                        supportingText = if (!emailIsSingleValue) {
                            { Text("Use one email address without line breaks.") }
                        } else {
                            null
                        },
                        enabled = !mutationInProgress,
                    )
                }
                item {
                    OutlinedTextField(
                        phone,
                        { phone = it },
                        label = { Text("Phone") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !mutationInProgress,
                    )
                }
                item {
                    OutlinedTextField(
                        organization, { organization = it },
                        label = { Text("Organization") }, modifier = Modifier.fillMaxWidth(),
                        enabled = !mutationInProgress,
                    )
                }
                item {
                    OutlinedTextField(
                        address,
                        { address = it },
                        label = { Text("Address") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !mutationInProgress,
                    )
                }
                item {
                    OutlinedTextField(
                        notes, { notes = it }, label = { Text("Notes") },
                        modifier = Modifier.fillMaxWidth(), minLines = 2, enabled = !mutationInProgress,
                    )
                }
                if (addressBooks.size > 1) item {
                    Text("Address book", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                        addressBooks.forEach { candidate ->
                            NextcloudFilterChip(
                                selected = candidate == addressBook,
                                onClick = { selectedAddressBookHref = candidate.href },
                                label = { Text(candidate.displayName) },
                                enabled = !mutationInProgress,
                            )
                        }
                    }
                }
                error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank() &&
                    emailIsSingleValue &&
                    addressBook != null &&
                    hasDavChanges &&
                    !mutationInProgress,
                onClick = {
                    onSave(
                        currentDraft,
                        requireNotNull(addressBook),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = {
            if (recoveryAvailable) {
                OutlinedButton(onClick = onOpenRecovery) { Text("Recovery options") }
            } else {
                OutlinedButton(onClick = onDismiss, enabled = !mutationInProgress) { Text("Cancel") }
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
