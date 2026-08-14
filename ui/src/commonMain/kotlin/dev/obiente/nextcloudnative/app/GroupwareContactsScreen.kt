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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Clock

private sealed interface ContactsLoadState {
    data object Loading : ContactsLoadState
    data class Ready(
        val addressBooks: List<GroupwareAddressBook>,
        val contacts: List<GroupwareContact>,
    ) : ContactsLoadState
    data class Error(val message: String) : ContactsLoadState
}

private object ContactsWorkspaceMemoryCache {
    private val entries = linkedMapOf<String, ContactsLoadState.Ready>()

    fun get(session: NextcloudSession, userId: String): ContactsLoadState.Ready? {
        val key = "${session.serverUrl.trimEnd('/')}\n${session.loginName}\n$userId"
        return entries.remove(key)?.also { entries[key] = it }
    }

    fun store(session: NextcloudSession, userId: String, value: ContactsLoadState.Ready) {
        val key = "${session.serverUrl.trimEnd('/')}\n${session.loginName}\n$userId"
        entries.remove(key)
        entries[key] = value
        while (entries.size > MAXIMUM_RETAINED_CONTACT_ACCOUNTS) entries.remove(entries.keys.first())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NativeGroupwareContactsScreen(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    userId: String,
    onBack: () -> Unit,
    navigationRequest: NextcloudPendingNavigationRequest? = null,
    onNavigationConfirmed: (NextcloudPendingNavigationRequest) -> Unit = {},
    onNavigationCancelled: (NextcloudPendingNavigationRequest) -> Unit = {},
    onMutationInProgressChanged: (Boolean) -> Unit = {},
) {
    val accountScope = remember(session.serverUrl, session.loginName, userId) {
        groupwareMutationAccountScope(session, userId)
    }
    var state by remember(session, userId) {
        mutableStateOf<ContactsLoadState>(
            ContactsWorkspaceMemoryCache.get(session, userId) ?: ContactsLoadState.Loading,
        )
    }
    var refreshing by remember { mutableStateOf(false) }
    var refreshError by remember { mutableStateOf<String?>(null) }
    var query by rememberSaveable(accountScope) { mutableStateOf("") }
    var loadAttempt by remember { mutableStateOf(0) }
    var selectedContactHref by rememberSaveable(accountScope) { mutableStateOf<String?>(null) }
    var editing by rememberSaveable(accountScope) { mutableStateOf(false) }
    var creating by rememberSaveable(accountScope) { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var mutationError by remember { mutableStateOf<String?>(null) }
    var mutationOperationInProgress by remember(accountScope) { mutableStateOf(false) }
    var mutationRecoveryState by rememberSaveable(accountScope) { mutableStateOf<String?>(null) }
    val mutationPostcondition = remember(accountScope, mutationRecoveryState) {
        mutationRecoveryState?.let { decodeContactMutationRecoveryState(it, accountScope) }
    }
    val mutationInProgress = mutationOperationInProgress || mutationPostcondition != null
    val scope = rememberCoroutineScope()

    fun retainMutationRecovery(postcondition: ContactMutationPostcondition) {
        check(mutationRecoveryState == null && !mutationOperationInProgress) {
            "Another contact change is still awaiting server verification."
        }
        mutationRecoveryState = ContactMutationRecoveryState(accountScope, postcondition).encodeForSavedState()
        mutationOperationInProgress = true
        onMutationInProgressChanged(true)
    }

    fun clearMutationRecovery() {
        mutationRecoveryState = null
        mutationOperationInProgress = false
        onMutationInProgressChanged(false)
    }

    LaunchedEffect(mutationInProgress) {
        onMutationInProgressChanged(mutationInProgress)
    }
    DisposableEffect(Unit) {
        onDispose { onMutationInProgressChanged(false) }
    }

    LaunchedEffect(session, userId, loadAttempt) {
        val reconciliationConfirmed = mutationPostcondition?.let { postcondition ->
            runCatching {
                val response = services.executeGroupwareDav(
                    session,
                    groupwareDavDetailRequest(postcondition.href),
                )
                postcondition.isSatisfiedBy(response)
            }.getOrDefault(false)
        } == true
        val cached = ContactsWorkspaceMemoryCache.get(session, userId)
        if (cached != null) state = cached
        val retained = cached ?: state as? ContactsLoadState.Ready
        refreshError = null
        if (retained == null) {
            state = ContactsLoadState.Loading
        } else {
            refreshing = true
        }
        runCatching {
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
        }.onSuccess { loaded ->
            state = loaded
            ContactsWorkspaceMemoryCache.store(session, userId, loaded)
            if (mutationPostcondition != null) {
                if (reconciliationConfirmed) {
                    when (mutationPostcondition) {
                        is ContactMutationPostcondition.Upsert -> {
                            if (mutationPostcondition.previousEtag == null) creating = false
                            editing = false
                            selectedContactHref = null
                        }
                        is ContactMutationPostcondition.Delete -> selectedContactHref = null
                    }
                    clearMutationRecovery()
                } else {
                    refreshError = "The contact change has not appeared on the server yet. Refresh to verify it before leaving."
                }
            }
        }.onFailure { failure ->
            val message = failure.message ?: "Could not load contacts."
            if (retained == null) {
                state = ContactsLoadState.Error(message)
            } else {
                refreshError = message
            }
        }
        refreshing = false
    }

    val ready = state as? ContactsLoadState.Ready
    val selected = ready?.contacts?.firstOrNull { contact -> contact.href == selectedContactHref }
    LaunchedEffect(ready, selectedContactHref) {
        if (ready != null && selectedContactHref != null && selected == null) {
            selectedContactHref = null
            editing = false
        }
    }
    val editorVisible = creating ||
        (editing && selectedContactHref != null)
    LaunchedEffect(navigationRequest?.identity, editorVisible, mutationInProgress) {
        navigationRequest
            ?.takeIf { !editorVisible && !mutationInProgress }
            ?.let(onNavigationConfirmed)
    }
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
                    IconButton(onClick = onBack, enabled = !mutationInProgress) {
                        Icon(NextcloudIcons.Back, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { loadAttempt += 1 }) {
                        Icon(NextcloudIcons.Refresh, contentDescription = "Refresh contacts")
                    }
                    IconButton(
                        onClick = { if (!mutationInProgress) creating = true },
                        enabled = !mutationInProgress &&
                            ready?.addressBooks?.any(GroupwareAddressBook::writable) == true,
                    ) {
                        Icon(NextcloudIcons.Add, contentDescription = "Create contact")
                    }
                },
            )
        },
    ) { insets ->
        Column(modifier = Modifier.fillMaxSize().padding(insets)) {
            if (refreshing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            refreshError?.let { message ->
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(
                        horizontal = NextcloudSpacing.Large,
                        vertical = NextcloudSpacing.Small,
                    ),
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = NextcloudSpacing.Medium),
                        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { loadAttempt += 1 }) { Text("Retry") }
                    }
                }
            }
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
                        ContactList(filtered, onSelect = { selectedContactHref = it.href })
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
            mutationInProgress = mutationInProgress,
            navigationRequest = navigationRequest,
            onNavigationConfirmed = onNavigationConfirmed,
            onNavigationCancelled = onNavigationCancelled,
            onSave = { draft, addressBook ->
                val uid = "nextcloud-native-${Clock.System.now().toEpochMilliseconds()}"
                val objectHref = "${addressBook.href}$uid.vcf"
                val request = GroupwareDavMutationSpec(
                    kind = GroupwareDavKind.Contact,
                    mutation = GroupwareDavMutation.Create,
                    objectHref = objectHref,
                    content = createGroupwareContactContent(
                        uid, draft.name, draft.email, draft.phone,
                        draft.organization, draft.address, draft.notes,
                    ),
                ).toGroupwareDavRequest()
                retainMutationRecovery(
                    ContactMutationPostcondition.Upsert(
                        href = objectHref,
                        addressBookHref = addressBook.href,
                        expectedUid = uid,
                        previousEtag = null,
                        draft = draft,
                    ),
                )
                scope.launch {
                    mutationError = null
                    try {
                        val response = services.executeGroupwareDav(session, request)
                        if (response.status !in 200..299) {
                            if (groupwareMutationResponseProvesRejection(response.status)) {
                                clearMutationRecovery()
                                mutationError = "Creating the contact failed (HTTP ${response.status})."
                            } else {
                                mutationError = CONTACT_MUTATION_RESULT_UNKNOWN_MESSAGE
                                loadAttempt += 1
                            }
                            return@launch
                        }
                        creating = false
                        loadAttempt += 1
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (_: Exception) {
                        mutationError = CONTACT_MUTATION_RESULT_UNKNOWN_MESSAGE
                        loadAttempt += 1
                    }
                }
            },
        )
    }

    selected?.let { contact ->
        val addressBook = ready.addressBooks.firstOrNull { it.href == contact.addressBookHref }
        if (editing && addressBook != null) {
            ContactEditorDialog(
                contact = contact,
                addressBooks = listOf(addressBook),
                error = mutationError,
                onDismiss = { editing = false; mutationError = null },
                mutationInProgress = mutationInProgress,
                navigationRequest = navigationRequest,
                onNavigationConfirmed = onNavigationConfirmed,
                onNavigationCancelled = onNavigationCancelled,
                onSave = { draft, _ ->
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
                    retainMutationRecovery(
                        ContactMutationPostcondition.Upsert(
                            href = contact.href,
                            addressBookHref = contact.addressBookHref,
                            expectedUid = contact.uid,
                            previousEtag = contact.etag,
                            draft = draft,
                        ),
                    )
                    scope.launch {
                        mutationError = null
                        try {
                            val response = services.executeGroupwareDav(session, request)
                            if (response.status !in 200..299) {
                                if (groupwareMutationResponseProvesRejection(response.status)) {
                                    clearMutationRecovery()
                                    mutationError = "Saving the contact failed (HTTP ${response.status})."
                                } else {
                                    mutationError = CONTACT_MUTATION_RESULT_UNKNOWN_MESSAGE
                                    loadAttempt += 1
                                }
                                return@launch
                            }
                            editing = false
                            selectedContactHref = null
                            loadAttempt += 1
                        } catch (failure: CancellationException) {
                            throw failure
                        } catch (_: Exception) {
                            mutationError = CONTACT_MUTATION_RESULT_UNKNOWN_MESSAGE
                            loadAttempt += 1
                        }
                    }
                },
            )
        } else if (!confirmDelete) {
            ContactDetailDialog(
                contact = contact,
                canEdit = !mutationInProgress && addressBook?.writable == true && contact.etag != null,
                error = mutationError,
                onDismiss = { selectedContactHref = null; mutationError = null },
                onEdit = { if (!mutationInProgress) editing = true },
                onDelete = { if (!mutationInProgress) confirmDelete = true },
            )
        }
        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { if (!mutationInProgress) confirmDelete = false },
                title = { Text("Delete ${contact.displayName}?") },
                text = { Text("This removes the contact from Nextcloud. This cannot be undone.") },
                confirmButton = {
                    TextButton(
                        enabled = !mutationInProgress,
                        onClick = {
                            confirmDelete = false
                            val request = GroupwareDavMutationSpec(
                                kind = GroupwareDavKind.Contact,
                                mutation = GroupwareDavMutation.Delete,
                                objectHref = contact.href,
                                etag = contact.etag,
                            ).toGroupwareDavRequest()
                            retainMutationRecovery(ContactMutationPostcondition.Delete(contact.href))
                            scope.launch {
                                try {
                                    val response = services.executeGroupwareDav(session, request)
                                    if (response.status !in 200..299) {
                                        if (groupwareMutationResponseProvesRejection(response.status)) {
                                            clearMutationRecovery()
                                            mutationError = "Deleting the contact failed (HTTP ${response.status})."
                                        } else {
                                            mutationError = CONTACT_MUTATION_RESULT_UNKNOWN_MESSAGE
                                            loadAttempt += 1
                                        }
                                        return@launch
                                    }
                                    selectedContactHref = null
                                    loadAttempt += 1
                                } catch (failure: CancellationException) {
                                    throw failure
                                } catch (_: Exception) {
                                    mutationError = CONTACT_MUTATION_RESULT_UNKNOWN_MESSAGE
                                    loadAttempt += 1
                                }
                            }
                        },
                    ) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(
                        enabled = !mutationInProgress,
                        onClick = { confirmDelete = false },
                    ) { Text("Cancel") }
                },
            )
        }
    }
}

private const val MAXIMUM_RETAINED_CONTACT_ACCOUNTS = 4
private const val CONTACT_MUTATION_RESULT_UNKNOWN_MESSAGE =
    "The server response was interrupted, so the contact result is unknown. " +
        "Refresh to verify it before trying another change."

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

@Serializable
internal data class ContactDraft(
    val name: String,
    val email: String,
    val phone: String,
    val organization: String,
    val address: String,
    val notes: String,
) {
    fun normalizedForDav(): ContactDraft = copy(
        email = email.trim(),
        phone = phone.trim(),
        organization = organization.trim(),
        address = address.trim(),
        notes = notes.trim(),
    )
}

@Serializable
internal sealed interface ContactMutationPostcondition {
    val href: String
    fun isSatisfiedBy(response: NextcloudApiResponse): Boolean

    @Serializable
    data class Upsert(
        override val href: String,
        val addressBookHref: String,
        val expectedUid: String,
        val previousEtag: String?,
        val draft: ContactDraft,
    ) : ContactMutationPostcondition {
        override fun isSatisfiedBy(response: NextcloudApiResponse): Boolean {
            if (response.status !in 200..299) return false
            val expected = draft.normalizedForDav()
            val contact = parseGroupwareContact(
                addressBookHref = addressBookHref,
                href = href,
                etag = response.etag,
                content = response.body.decodeToString(),
            ) ?: return false
            return contact.href == href &&
                contact.uid == expectedUid &&
                (previousEtag == null || contact.etag != null && contact.etag != previousEtag) &&
                contact.displayName == expected.name &&
                contact.emails.firstOrNull().orEmpty() == expected.email &&
                contact.phones.firstOrNull().orEmpty() == expected.phone &&
                contact.organization.orEmpty() == expected.organization &&
                contact.address.orEmpty() == expected.address &&
                contact.notes.orEmpty() == expected.notes
        }
    }

    @Serializable
    data class Delete(override val href: String) : ContactMutationPostcondition {
        override fun isSatisfiedBy(response: NextcloudApiResponse): Boolean =
            response.status == 404 || response.status == 410
    }
}

@Serializable
internal data class ContactMutationRecoveryState(
    val accountScope: String,
    val postcondition: ContactMutationPostcondition,
) {
    init {
        require(accountScope.isCanonicalGroupwareMutationAccountScope())
    }
}

private val contactMutationRecoveryJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

internal fun ContactMutationRecoveryState.encodeForSavedState(): String =
    contactMutationRecoveryJson.encodeToString(this)

internal fun decodeContactMutationRecoveryState(
    encoded: String,
    expectedAccountScope: String,
): ContactMutationPostcondition? = runCatching {
    contactMutationRecoveryJson.decodeFromString<ContactMutationRecoveryState>(encoded)
}.getOrNull()?.takeIf { recovery -> recovery.accountScope == expectedAccountScope }?.postcondition

internal fun contactDraftIsDirty(
    initial: ContactDraft,
    current: ContactDraft,
    initialAddressBookHref: String?,
    currentAddressBookHref: String?,
): Boolean = initial != current || initialAddressBookHref != currentAddressBookHref

@Composable
private fun ContactEditorDialog(
    contact: GroupwareContact?,
    addressBooks: List<GroupwareAddressBook>,
    error: String?,
    onDismiss: () -> Unit,
    mutationInProgress: Boolean,
    navigationRequest: NextcloudPendingNavigationRequest? = null,
    onNavigationConfirmed: (NextcloudPendingNavigationRequest) -> Unit = {},
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
    val dirty = contactDraftIsDirty(
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
                                onClick = { selectedAddressBookHref = candidate.href },
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
                enabled = name.isNotBlank() && addressBook != null && !mutationInProgress,
                onClick = {
                    onSave(
                        currentDraft,
                        requireNotNull(addressBook),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss, enabled = !mutationInProgress) { Text("Cancel") }
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
                            onNavigationConfirmed(request)
                        },
                    ) { Text("Discard") }
                },
            )
        }
    }
}

private fun GroupwareContact.initials(): String = displayName.split(' ')
    .filter(String::isNotBlank)
    .take(2)
    .mapNotNull(String::firstOrNull)
    .joinToString("")
    .uppercase()
    .ifBlank { "?" }
