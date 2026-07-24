package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme
import kotlinx.coroutines.launch
import kotlin.time.Clock

private sealed interface ContactsLoadState {
    data object Loading : ContactsLoadState
    data class Ready(
        val addressBooks: List<GroupwareAddressBook>,
        val contacts: List<GroupwareContact>,
    ) : ContactsLoadState
    data class Error(val message: String) : ContactsLoadState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NativeGroupwareContactsScreen(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    userId: String,
    onBack: () -> Unit,
) {
    var state by remember { mutableStateOf<ContactsLoadState>(ContactsLoadState.Loading) }
    var query by remember { mutableStateOf("") }
    var loadAttempt by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf<GroupwareContact?>(null) }
    var editing by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var mutationError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(session, userId, loadAttempt) {
        state = ContactsLoadState.Loading
        state = runCatching {
            val principal = parseGroupwarePrincipalHref(
                services.executeGroupwareDav(session, groupwareDavPrincipalDiscoveryRequest()),
            )
            val homes = parseGroupwareDavHomes(
                services.executeGroupwareDav(session, groupwareDavHomeDiscoveryRequest(principal)),
            )
            val addressBookHome = homes.addressBookHref ?: groupwareAddressBookHomeHref(userId)
            val discovery = services.executeGroupwareDav(
                session,
                groupwareDavCollectionDiscoveryRequest(addressBookHome),
            )
            val addressBooks = parseGroupwareAddressBooks(discovery)
            val contacts = addressBooks.flatMap { addressBook ->
                parseGroupwareContacts(
                    addressBook.href,
                    services.executeGroupwareDav(
                        session,
                        groupwareDavCollectionQueryRequest(
                            collectionHref = addressBook.href,
                            kind = GroupwareDavKind.Contact,
                            maxResults = 250,
                        ),
                    ),
                )
            }.sortedBy { it.displayName.lowercase() }
            ContactsLoadState.Ready(addressBooks, contacts)
        }.getOrElse { ContactsLoadState.Error(it.message ?: "Could not load contacts.") }
    }

    val ready = state as? ContactsLoadState.Ready
    val filtered = remember(ready?.contacts, query) {
        val needle = query.trim().lowercase()
        ready?.contacts.orEmpty().filter { contact ->
            needle.isBlank() || listOf(
                contact.displayName,
                contact.organization,
                contact.address,
                contact.emails.joinToString(" "),
                contact.phones.joinToString(" "),
            ).any { value -> value?.lowercase()?.contains(needle) == true }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Contacts", fontWeight = FontWeight.SemiBold)
                        ready?.let {
                            Text(
                                "${it.contacts.size} contacts",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(NextcloudIcons.Back, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = { loadAttempt += 1 }) {
                        Icon(NextcloudIcons.Refresh, contentDescription = "Refresh contacts")
                    }
                    IconButton(
                        onClick = { creating = true },
                        enabled = ready?.addressBooks?.any(GroupwareAddressBook::writable) == true,
                    ) {
                        Icon(NextcloudIcons.Add, contentDescription = "Create contact")
                    }
                },
            )
        },
    ) { insets ->
        Column(modifier = Modifier.fillMaxSize().padding(insets)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search contacts") },
                leadingIcon = { Icon(NextcloudIcons.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(
                    horizontal = NextcloudSpacing.Large,
                    vertical = NextcloudSpacing.Small,
                ),
            )
            when (val value = state) {
                ContactsLoadState.Loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
                is ContactsLoadState.Error -> ContactsError(value.message) { loadAttempt += 1 }
                is ContactsLoadState.Ready -> {
                    if (value.addressBooks.isEmpty()) {
                        ContactsError("No address books were found.") { loadAttempt += 1 }
                    } else {
                        ContactList(filtered, onSelect = { selected = it })
                    }
                }
            }
        }
    }

    if (creating && ready != null) {
        ContactEditorDialog(
            contact = null,
            addressBooks = ready.addressBooks.filter(GroupwareAddressBook::writable),
            error = mutationError,
            onDismiss = { creating = false; mutationError = null },
            onSave = { draft, addressBook ->
                scope.launch {
                    mutationError = null
                    runCatching {
                        val uid = "nextcloud-native-${Clock.System.now().toEpochMilliseconds()}"
                        val request = GroupwareDavMutationSpec(
                            kind = GroupwareDavKind.Contact,
                            mutation = GroupwareDavMutation.Create,
                            objectHref = "${addressBook.href}$uid.vcf",
                            content = createGroupwareContactContent(
                                uid, draft.name, draft.email, draft.phone,
                                draft.organization, draft.address, draft.notes,
                            ),
                        ).toGroupwareDavRequest()
                        val response = services.executeGroupwareDav(session, request)
                        check(response.status in 200..299) {
                            "Creating the contact failed (HTTP ${response.status})."
                        }
                    }.onSuccess {
                        creating = false
                        loadAttempt += 1
                    }.onFailure { mutationError = it.message ?: "Could not create the contact." }
                }
            },
        )
    }

    selected?.let { contact ->
        val addressBook = ready?.addressBooks?.firstOrNull { it.href == contact.addressBookHref }
        if (editing && addressBook != null) {
            ContactEditorDialog(
                contact = contact,
                addressBooks = listOf(addressBook),
                error = mutationError,
                onDismiss = { editing = false; mutationError = null },
                onSave = { draft, _ ->
                    scope.launch {
                        mutationError = null
                        runCatching {
                            val request = GroupwareDavMutationSpec(
                                kind = GroupwareDavKind.Contact,
                                mutation = GroupwareDavMutation.Update,
                                objectHref = contact.href,
                                etag = contact.etag,
                                content = updateGroupwareContactContent(
                                    contact, draft.name, draft.email, draft.phone,
                                    draft.organization, draft.address, draft.notes,
                                ),
                            ).toGroupwareDavRequest()
                            val response = services.executeGroupwareDav(session, request)
                            check(response.status in 200..299) {
                                "Saving the contact failed (HTTP ${response.status})."
                            }
                        }.onSuccess {
                            editing = false
                            selected = null
                            loadAttempt += 1
                        }.onFailure { mutationError = it.message ?: "Could not save the contact." }
                    }
                },
            )
        } else if (!confirmDelete) {
            ContactDetailDialog(
                contact = contact,
                canEdit = addressBook?.writable == true && contact.etag != null,
                error = mutationError,
                onDismiss = { selected = null; mutationError = null },
                onEdit = { editing = true },
                onDelete = { confirmDelete = true },
            )
        }
        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text("Delete ${contact.displayName}?") },
                text = { Text("This removes the contact from Nextcloud. This cannot be undone.") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmDelete = false
                        scope.launch {
                            runCatching {
                                val request = GroupwareDavMutationSpec(
                                    kind = GroupwareDavKind.Contact,
                                    mutation = GroupwareDavMutation.Delete,
                                    objectHref = contact.href,
                                    etag = contact.etag,
                                ).toGroupwareDavRequest()
                                val response = services.executeGroupwareDav(session, request)
                                check(response.status in 200..299) {
                                    "Deleting the contact failed (HTTP ${response.status})."
                                }
                            }.onSuccess {
                                selected = null
                                loadAttempt += 1
                            }.onFailure { mutationError = it.message ?: "Could not delete the contact." }
                        }
                    }) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
            )
        }
    }
}

@Composable
private fun ContactList(contacts: List<GroupwareContact>, onSelect: (GroupwareContact) -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(NextcloudSpacing.Large),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
    ) {
        items(contacts, key = GroupwareContact::href) { contact ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onSelect(contact) },
                colors = CardDefaults.cardColors(containerColor = NextcloudTheme.colors.appTile),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(shape = CircleShape, color = NextcloudTheme.colors.appIconContainer) {
                        Text(
                            contact.initials(),
                            modifier = Modifier.padding(NextcloudSpacing.Medium),
                            color = NextcloudTheme.colors.appIcon,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            contact.displayName,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val subtitle = contact.organization ?: contact.emails.firstOrNull()
                            ?: contact.phones.firstOrNull()
                        subtitle?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Icon(NextcloudIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(20.dp))
                }
            }
        }
        if (contacts.isEmpty()) item {
            Text("No contacts match your search.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ContactsError(message: String, retry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(NextcloudSpacing.XLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(NextcloudIcons.Error, contentDescription = null, modifier = Modifier.size(38.dp))
        Text(message, modifier = Modifier.padding(NextcloudSpacing.Medium))
        Button(onClick = retry) { Text("Try again") }
    }
}

@Composable
private fun ContactDetailDialog(
    contact: GroupwareContact,
    canEdit: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
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
            if (canEdit) TextButton(onClick = onEdit) { Text("Edit") }
            else TextButton(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = {
            if (canEdit) TextButton(onClick = onDelete) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
    )
}

private data class ContactDraft(
    val name: String,
    val email: String,
    val phone: String,
    val organization: String,
    val address: String,
    val notes: String,
)

@Composable
private fun ContactEditorDialog(
    contact: GroupwareContact?,
    addressBooks: List<GroupwareAddressBook>,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (ContactDraft, GroupwareAddressBook) -> Unit,
) {
    var name by remember(contact) { mutableStateOf(contact?.displayName.orEmpty()) }
    var email by remember(contact) { mutableStateOf(contact?.emails?.firstOrNull().orEmpty()) }
    var phone by remember(contact) { mutableStateOf(contact?.phones?.firstOrNull().orEmpty()) }
    var organization by remember(contact) { mutableStateOf(contact?.organization.orEmpty()) }
    var address by remember(contact) { mutableStateOf(contact?.address.orEmpty()) }
    var notes by remember(contact) { mutableStateOf(contact?.notes.orEmpty()) }
    var addressBook by remember(addressBooks) { mutableStateOf(addressBooks.firstOrNull()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (contact == null) "New contact" else "Edit contact") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                item { OutlinedTextField(name, { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(email, { email = it }, label = { Text("Email") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(phone, { phone = it }, label = { Text("Phone") }, modifier = Modifier.fillMaxWidth()) }
                item {
                    OutlinedTextField(
                        organization, { organization = it },
                        label = { Text("Organization") }, modifier = Modifier.fillMaxWidth(),
                    )
                }
                item { OutlinedTextField(address, { address = it }, label = { Text("Address") }, modifier = Modifier.fillMaxWidth()) }
                item {
                    OutlinedTextField(
                        notes, { notes = it }, label = { Text("Notes") },
                        modifier = Modifier.fillMaxWidth(), minLines = 2,
                    )
                }
                if (addressBooks.size > 1) item {
                    Text("Address book", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                        addressBooks.forEach { candidate ->
                            FilterChip(
                                selected = candidate == addressBook,
                                onClick = { addressBook = candidate },
                                label = { Text(candidate.displayName) },
                            )
                        }
                    }
                }
                error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank() && addressBook != null,
                onClick = {
                    onSave(
                        ContactDraft(name, email, phone, organization, address, notes),
                        requireNotNull(addressBook),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun GroupwareContact.initials(): String = displayName.split(' ')
    .filter(String::isNotBlank)
    .take(2)
    .mapNotNull(String::firstOrNull)
    .joinToString("")
    .uppercase()
    .ifBlank { "?" }
