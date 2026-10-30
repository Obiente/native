package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import dev.obiente.nextcloudnative.app.design.NextcloudCollectionNavigationModel
import dev.obiente.nextcloudnative.app.design.NextcloudCollectionWorkspaceScaffold
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudRefreshBar
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.time.Clock

@Composable
fun NativeGroupwareContactsScreen(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    userId: String,
    onBack: () -> Unit,
    navigationRequest: NextcloudPendingNavigationRequest? = null,
    onNavigationConfirmed: (NextcloudPendingNavigationRequest) -> Unit = {},
    onNavigationCancelled: (NextcloudPendingNavigationRequest) -> Unit = {},
    navigationCommitInProgress: Boolean = false,
    onMutationInProgressChanged: (Boolean) -> Unit = {},
) {
    val accountScope = remember(session.serverUrl, session.loginName) {
        durableMutationAccountScope(session)
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
    var addressBookFilterHref by rememberSaveable(accountScope) { mutableStateOf<String?>(null) }
    val contactListState = rememberLazyListState()
    var editing by rememberSaveable(accountScope) { mutableStateOf(false) }
    var editingContact by remember(accountScope) { mutableStateOf<GroupwareContact?>(null) }
    var editLoading by remember(accountScope) { mutableStateOf(false) }
    var creating by rememberSaveable(accountScope) { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var mutationError by remember { mutableStateOf<String?>(null) }
    var showRecoveryOptions by remember(accountScope) { mutableStateOf(false) }
    var recoveryResetInProgress by remember(accountScope) { mutableStateOf(false) }
    var mutationOperationInProgress by remember(accountScope) { mutableStateOf(false) }
    var mutationRequestRunning by remember(accountScope) { mutableStateOf(false) }
    var mutationRecoveryLoaded by remember(accountScope, services) { mutableStateOf(false) }
    var mutationRecoveryState by remember(accountScope, services) { mutableStateOf<String?>(null) }
    val recoverySnapshot = remember(accountScope, mutationRecoveryState) {
        contactRecoverySnapshot(mutationRecoveryState, accountScope)
    }
    val mutationPostcondition = recoverySnapshot.postcondition
    val durableMutationInProgress =
        !mutationRecoveryLoaded || mutationOperationInProgress || mutationRecoveryState != null
    val mutationInProgress = mutationOrLinkCommitBlocksInteraction(
        durableMutationInProgress,
        navigationCommitInProgress,
    )
    val scope = rememberCoroutineScope()

    LaunchedEffect(navigationCommitInProgress) {
        if (navigationCommitInProgress) {
            creating = false
            editing = false
            editingContact = null
        }
    }

    suspend fun retainMutationRecovery(postcondition: ContactMutationPostcondition): Boolean {
        if (!mutationRecoveryLoaded || mutationRecoveryState != null || mutationOperationInProgress) {
            mutationError = "Another contact change is still awaiting server verification."
            return false
        }
        val encoded = ContactMutationRecoveryState(accountScope, postcondition).encodeForSavedState()
        mutationOperationInProgress = true
        onMutationInProgressChanged(true)
        val saved = try {
            services.saveDurableMutationRecovery(session, accountScope, DurableMutationRecoveryKind.Contacts, encoded)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            false
        }
        if (!saved) {
            mutationRecoveryState = try {
                services.loadDurableMutationRecovery(accountScope, DurableMutationRecoveryKind.Contacts)
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                null
            }
            mutationError = if (mutationRecoveryState != null) {
                "Another contact change is still awaiting server verification."
            } else {
                "The contact change could not be safely recorded. Check local storage and try again."
            }
            mutationOperationInProgress = false
            onMutationInProgressChanged(mutationRecoveryState != null || !mutationRecoveryLoaded)
            return false
        }
        mutationRequestRunning = true
        mutationRecoveryState = encoded
        return true
    }

    suspend fun clearMutationRecovery(expectedRecord: String? = mutationRecoveryState): Boolean {
        val expectedEncoded = expectedRecord ?: return false
        val cleared = try {
            services.clearDurableMutationRecovery(
                accountScope,
                DurableMutationRecoveryKind.Contacts,
                expectedEncoded,
            )
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            false
        }
        if (!cleared) {
            val current = try {
                services.loadDurableMutationRecovery(accountScope, DurableMutationRecoveryKind.Contacts)
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                null
            }
            if (current != null) mutationRecoveryState = current
            mutationError = "The verified contact recovery record could not be cleared safely. Refresh to inspect the current pending change."
            return false
        }
        mutationRecoveryState = null
        showRecoveryOptions = false
        mutationOperationInProgress = false
        mutationError = null
        refreshError = null
        onMutationInProgressChanged(false)
        return true
    }

    LaunchedEffect(accountScope, services, loadAttempt) {
        mutationRecoveryLoaded = false
        mutationRecoveryState = null
        try {
            mutationRecoveryState = services.loadDurableMutationRecovery(
                accountScope,
                DurableMutationRecoveryKind.Contacts,
            )
            mutationRecoveryLoaded = true
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            val message = "Contact recovery storage could not be read securely. Check local storage and retry."
            mutationError = message
            refreshError = message
        }
    }

    LaunchedEffect(accountScope, mutationRecoveryLoaded, recoverySnapshot) {
        if (mutationRecoveryLoaded && recoverySnapshot.unreadable) {
            refreshError = "The previous contact recovery record cannot be read. Writes remain blocked."
            showRecoveryOptions = true
        }
    }

    LaunchedEffect(durableMutationInProgress) {
        onMutationInProgressChanged(durableMutationInProgress)
    }
    DisposableEffect(Unit) { onDispose { onMutationInProgressChanged(false) } }

    LaunchedEffect(session, userId, loadAttempt, mutationRecoveryLoaded, recoverySnapshot, mutationRequestRunning) {
        if (!recoverySnapshot.readyToVerify(mutationRecoveryLoaded, mutationRequestRunning)) return@LaunchedEffect
        val cacheProducer = ContactsWorkspaceMemoryCache.producer(session)
        val reconciliationConfirmed = mutationPostcondition?.let { postcondition ->
            runCatchingPreservingCancellation {
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
        runCatchingPreservingCancellation {
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
            val retentionBudget = GroupwareContactRetentionBudget()
            var concurrentlyDeletedObjectCount = 0
            val contacts = addressBooks.flatMap { addressBook ->
                loadGroupwareContactsInBatches(
                    addressBookHref = addressBook.href,
                    retentionBudget = retentionBudget,
                    onConcurrentDeletion = { count -> concurrentlyDeletedObjectCount += count },
                ) { request -> services.executeGroupwareDav(session, request) }
            }.sortedBy { it.displayName.lowercase() }
            ContactsLoadState.Ready(addressBooks, contacts) to concurrentlyDeletedObjectCount
        }.onSuccess { loaded ->
            state = loaded.first
            ContactsWorkspaceMemoryCache.store(session, userId, loaded.first, cacheProducer)
            if (loaded.second > 0) {
                refreshError = "${loaded.second} contacts changed during refresh; the remaining contacts are current."
            }
            if (mutationPostcondition != null) {
                if (reconciliationConfirmed) {
                    if (!clearMutationRecovery(recoverySnapshot.encoded)) return@onSuccess
                    when (mutationPostcondition) {
                        is ContactMutationPostcondition.Upsert -> {
                            if (mutationPostcondition.previousEtag == null) creating = false
                            editing = false
                            editingContact = null
                            selectedContactHref = null
                        }
                        is ContactMutationPostcondition.Delete -> selectedContactHref = null
                    }
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
            if (mutationRecoveryState != null) showRecoveryOptions = true
        }
        refreshing = false
    }

    val ready = state as? ContactsLoadState.Ready
    val selected = ready?.contacts?.firstOrNull { contact -> contact.href == selectedContactHref }
    val selectedAddressBook = ready?.addressBooks?.firstOrNull { addressBook ->
        addressBook.href == selected?.addressBookHref
    }
    LaunchedEffect(ready, selectedContactHref) {
        if (ready != null && selectedContactHref != null && selected == null) {
            selectedContactHref = null
            editing = false
            editingContact = null
        }
    }
    LaunchedEffect(
        editing,
        selected?.href,
        selectedAddressBook?.href,
        mutationInProgress,
        services,
        session,
    ) {
        if (mutationInProgress || !contactEditRequiresFullLoad(editing, selected?.href, editingContact?.href)) {
            return@LaunchedEffect
        }
        val contact = selected ?: run {
            editing = false
            return@LaunchedEffect
        }
        val addressBook = selectedAddressBook?.takeIf(GroupwareAddressBook::writable) ?: run {
            editing = false
            return@LaunchedEffect
        }
        editLoading = true
        mutationError = null
        try {
            val loaded = loadGroupwareContactForEditing(
                addressBook.href,
                contact.href,
                contact.etag,
            ) { request -> services.executeGroupwareDav(session, request) }
            if (editing && selectedContactHref == contact.href) editingContact = loaded
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            if (selectedContactHref == contact.href) {
                editing = false
                mutationError = "The complete contact could not be loaded for editing. Try again."
            }
        } finally {
            editLoading = false
        }
    }
    val editorVisible = creating ||
        (editing && selectedContactHref != null)
    LaunchedEffect(navigationRequest?.identity, editorVisible, mutationInProgress) {
        navigationRequest
            ?.takeIf { !editorVisible && !mutationInProgress }
            ?.let(onNavigationConfirmed)
    }
    val editorShown = selected != null && editing && selectedAddressBook != null &&
        editingContact?.href == selected.href
    val filterAddressBook = contactsFilterAddressBook(ready, addressBookFilterHref)
    val filtered = remember(ready?.contacts, filterAddressBook, query) {
        filterContacts(ready?.contacts.orEmpty(), filterAddressBook, query)
    }
    val writableAddressBooks = ready?.addressBooks.orEmpty().filter(GroupwareAddressBook::writable)
        .sortedByDescending { addressBook -> addressBook.href == filterAddressBook?.href }

    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        val besideList = workspaceShowsDetailBesideList(maxWidth)
        val destinations = remember(ready) { contactsDestinations(ready) }
        val navigation = remember(destinations, filterAddressBook) {
            NextcloudCollectionNavigationModel.create(
                destinations,
                if (destinations.isEmpty()) null else filterAddressBook?.href ?: ALL_CONTACTS_DESTINATION_ID,
            )
        }
        val detailActions = selected?.let { contact ->
            ContactDetailActions(
                canEdit = !mutationInProgress && selectedAddressBook?.writable == true && contact.etag != null,
                editLoading = editLoading,
                onEdit = {
                    if (!mutationInProgress && !editLoading) {
                        mutationError = null
                        editing = true
                    }
                },
                onDelete = { if (!mutationInProgress) confirmDelete = true },
            )
        }
        NextcloudCollectionWorkspaceScaffold(
            model = navigation,
            mode = workspaceCollectionNavigationMode(maxWidth, destinations.size),
            workspaceLabel = "Contacts",
            contentTitle = filterAddressBook?.displayName?.ifBlank { null } ?: "Contacts",
            contentSubtitle = ready?.let { "${filtered.size} contacts" },
            onBack = { if (!mutationInProgress) onBack() },
            hasHierarchyBack = false,
            onDestinationSelected = { destination ->
                addressBookFilterHref = destination.id.takeUnless { it == ALL_CONTACTS_DESTINATION_ID }
            },
            destinationIcon = { NextcloudIcons.app("contacts") },
            headerActions = {
                IconButton(onClick = { loadAttempt += 1 }) {
                    Icon(NextcloudIcons.Refresh, contentDescription = "Refresh contacts")
                }
                IconButton(
                    onClick = { if (!mutationInProgress) creating = true },
                    enabled = !mutationInProgress && writableAddressBooks.isNotEmpty(),
                ) {
                    Icon(NextcloudIcons.Add, contentDescription = "Create contact")
                }
            },
        ) {
            Row(Modifier.fillMaxSize()) {
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    NextcloudRefreshBar(refreshing)
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
                                if (mutationRecoveryState != null) {
                                    TextButton(onClick = { showRecoveryOptions = true }) { Text("Recovery options") }
                                }
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
                        is ContactsLoadState.Error -> WorkspaceLoadError(value.message) { loadAttempt += 1 }
                        is ContactsLoadState.Ready -> {
                            if (value.addressBooks.isEmpty()) {
                                WorkspaceLoadError("No address books were found.") { loadAttempt += 1 }
                            } else {
                                ContactList(
                                    filtered, selectedContactHref, contactListState,
                                    onSelect = { selectedContactHref = it.href },
                                )
                            }
                        }
                    }
                }
                if (besideList) {
                    WorkspaceSideInspector(selected?.let { it to requireNotNull(detailActions) }, { (contact, _) -> contact.href }) {
                        (contact, actions) -> ContactInspector(contact, actions, mutationError)
                    }
                }
            }
        }
        if (!besideList && selected != null && !editorShown && !confirmDelete) {
            ContactDetailOverlay(
                contact = selected,
                actions = requireNotNull(detailActions),
                error = mutationError,
                onDismiss = {
                    selectedContactHref = null
                    editingContact = null
                    mutationError = null
                },
            )
        }
    }

    if (showRecoveryOptions && mutationRecoveryState != null) {
        DurableMutationRecoveryDialog(
            title = "Resolve contact recovery",
            recordReadable = mutationPostcondition != null,
            resetting = recoveryResetInProgress,
            onCheckAgain = {
                showRecoveryOptions = false
                loadAttempt += 1
            },
            onReset = {
                if (!recoveryResetInProgress) {
                    recoveryResetInProgress = true
                    scope.launch {
                        if (clearMutationRecovery()) showRecoveryOptions = false
                        recoveryResetInProgress = false
                    }
                }
            },
            onDismiss = { showRecoveryOptions = false },
        )
    }

    if (creating && ready != null) {
        ContactEditorDialog(
            contact = null,
            addressBooks = writableAddressBooks,
            error = mutationError,
            onDismiss = { creating = false; mutationError = null },
            mutationInProgress = mutationInProgress,
            recoveryAvailable = mutationRecoveryState != null,
            onOpenRecovery = {
                creating = false
                showRecoveryOptions = true
            },
            navigationRequest = navigationRequest,
            onNavigationConfirmed = onNavigationConfirmed,
            onNavigationDiscardConfirmed = { request ->
                creating = false
                onNavigationConfirmed(request)
            },
            onNavigationCancelled = onNavigationCancelled,
            onSave = save@{ draft, addressBook ->
                val uid = "nextcloud-native-${Clock.System.now().toEpochMilliseconds()}"
                val objectHref = "${addressBook.href}$uid.vcf"
                val request = prepareGroupwareDavMutation(
                    onInvalid = {
                        mutationError = "The contact is too large or contains invalid data. Review its fields and try again."
                    },
                ) {
                    GroupwareDavMutationSpec(
                        kind = GroupwareDavKind.Contact,
                        mutation = GroupwareDavMutation.Create,
                        objectHref = objectHref,
                        content = createGroupwareContactContent(
                            uid, draft.name, draft.email, draft.phone,
                            draft.organization, draft.address, draft.notes,
                        ),
                    ).toGroupwareDavRequest()
                } ?: return@save
                scope.launch {
                    if (!retainMutationRecovery(
                            ContactMutationPostcondition.Upsert(
                                href = objectHref,
                                addressBookHref = addressBook.href,
                                expectedUid = uid,
                                previousEtag = null,
                                draft = draft,
                            ),
                        )
                    ) return@launch
                    mutationError = null
                    try {
                        val response = services.executeGroupwareDav(session, request)
                        if (response.status !in 200..299) {
                            if (groupwareMutationResponseProvesRejection(response.status)) {
                                if (clearMutationRecovery()) {
                                    mutationError = "Creating the contact failed (HTTP ${response.status})."
                                }
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
                    } finally {
                        mutationRequestRunning = false
                    }
                }
            },
        )
    }

    selected?.let { contact ->
        val addressBook = selectedAddressBook
        val fullContact = editingContact?.takeIf { it.href == contact.href }
        if (editorShown && addressBook != null && fullContact != null) {
            ContactEditorDialog(
                contact = fullContact,
                addressBooks = listOf(addressBook),
                error = mutationError,
                onDismiss = { editing = false; editingContact = null; mutationError = null },
                mutationInProgress = mutationInProgress,
                recoveryAvailable = mutationRecoveryState != null,
                onOpenRecovery = {
                    editing = false
                    editingContact = null
                    showRecoveryOptions = true
                },
                navigationRequest = navigationRequest,
                onNavigationConfirmed = onNavigationConfirmed,
                onNavigationDiscardConfirmed = { request ->
                    editing = false
                    editingContact = null
                    onNavigationConfirmed(request)
                },
                onNavigationCancelled = onNavigationCancelled,
                onSave = save@{ draft, _ ->
                    val prepared = prepareGroupwareDavMutation(
                        onInvalid = {
                            mutationError = "The contact is too large or contains invalid data. Review its fields and try again."
                        },
                    ) {
                        val content = updateGroupwareContactContent(
                            fullContact, draft.name, draft.email, draft.phone,
                            draft.organization, draft.address, draft.notes,
                        )
                        content to GroupwareDavMutationSpec(
                            kind = GroupwareDavKind.Contact,
                            mutation = GroupwareDavMutation.Update,
                            objectHref = fullContact.href,
                            etag = fullContact.etag,
                            content = content,
                        ).toGroupwareDavRequest()
                    } ?: return@save
                    val (updatedContent, request) = prepared
                    val postcondition = contactUpdatePostcondition(fullContact, draft, updatedContent)
                    if (postcondition == null) {
                        mutationError = "The updated contact could not be verified locally. Check its fields and try again."
                        return@save
                    }
                    scope.launch {
                        if (!retainMutationRecovery(postcondition)) return@launch
                        mutationError = null
                        try {
                            val response = services.executeGroupwareDav(session, request)
                            if (response.status !in 200..299) {
                                if (groupwareMutationResponseProvesRejection(response.status)) {
                                    if (clearMutationRecovery()) {
                                        mutationError = "Saving the contact failed (HTTP ${response.status})."
                                    }
                                } else {
                                    mutationError = CONTACT_MUTATION_RESULT_UNKNOWN_MESSAGE
                                    loadAttempt += 1
                                }
                                return@launch
                            }
                            editing = false
                            editingContact = null
                            selectedContactHref = null
                            loadAttempt += 1
                        } catch (failure: CancellationException) {
                            throw failure
                        } catch (_: Exception) {
                            mutationError = CONTACT_MUTATION_RESULT_UNKNOWN_MESSAGE
                            loadAttempt += 1
                        } finally {
                            mutationRequestRunning = false
                        }
                    }
                },
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
                        onClick = delete@{
                            val request = prepareGroupwareDavMutation(
                                onInvalid = {
                                    mutationError = "The contact cannot be changed safely. Refresh it and try again."
                                },
                            ) {
                                GroupwareDavMutationSpec(
                                    kind = GroupwareDavKind.Contact,
                                    mutation = GroupwareDavMutation.Delete,
                                    objectHref = contact.href,
                                    etag = contact.etag,
                                ).toGroupwareDavRequest()
                            } ?: return@delete
                            scope.launch {
                                if (!retainMutationRecovery(ContactMutationPostcondition.Delete(contact.href))) {
                                    return@launch
                                }
                                val expectedRecovery = mutationRecoveryState
                                confirmDelete = false
                                try {
                                    val response = services.executeGroupwareDav(session, request)
                                    if (response.status !in 200..299) {
                                        if (groupwareDeleteResponseProvesAbsence(response.status)) {
                                            if (clearMutationRecovery(expectedRecovery)) {
                                                selectedContactHref = null
                                                loadAttempt += 1
                                            }
                                        } else if (groupwareMutationResponseProvesRejection(response.status)) {
                                            if (clearMutationRecovery(expectedRecovery)) {
                                                mutationError = "Deleting the contact failed (HTTP ${response.status})."
                                            }
                                        } else {
                                            mutationError = CONTACT_MUTATION_RESULT_UNKNOWN_MESSAGE
                                            loadAttempt += 1
                                        }
                                        return@launch
                                    }
                                    val verified = verifyContactDeletion(contact.href) { services.executeGroupwareDav(session, it) }
                                    if (verified) {
                                        if (clearMutationRecovery(expectedRecovery)) selectedContactHref = null
                                    } else mutationError = CONTACT_MUTATION_RESULT_UNKNOWN_MESSAGE
                                    loadAttempt += 1
                                } catch (failure: CancellationException) {
                                    throw failure
                                } catch (_: Exception) {
                                    mutationError = CONTACT_MUTATION_RESULT_UNKNOWN_MESSAGE
                                    loadAttempt += 1
                                } finally {
                                    mutationRequestRunning = false
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
