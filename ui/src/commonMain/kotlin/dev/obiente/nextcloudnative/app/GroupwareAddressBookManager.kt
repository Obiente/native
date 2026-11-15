package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** A name dialog: a new address book, or renaming one. */
internal sealed interface AddressBookNaming {
    data object New : AddressBookNaming
    data class Rename(val addressBook: ManagedAddressBook) : AddressBookNaming
}

/**
 * Creates, renames and deletes address books. Each change is confirmed by reading the address book
 * home again; a new address book keeps its address while its dialog is open, so trying again after
 * an unknown result finds the first attempt instead of creating a second address book.
 */
@Stable
internal class AddressBookManager(
    private val services: NextcloudPlatformServices,
    private val session: NextcloudSession,
    private val userId: String,
    private val scope: CoroutineScope,
) {
    var open by mutableStateOf(false)
        private set
    var addressBooks by mutableStateOf<List<ManagedAddressBook>?>(null)
        private set
    var naming by mutableStateOf<AddressBookNaming?>(null)
        private set
    var deleting by mutableStateOf<ManagedAddressBook?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    internal var onChanged: () -> Unit = {}
    private var newHref: String? = null

    /** Every address the last address book home listing named, readable or not. */
    private var listedHrefs = emptySet<String>()

    fun show() {
        open = true
        error = null
        scope.launch { addressBooks = read() ?: addressBooks.also { error = "Address books could not be loaded." } }
    }

    fun dismiss() {
        if (busy) return
        open = false
        naming = null
        deleting = null
        error = null
        newHref = null
    }

    fun startNaming(target: AddressBookNaming) {
        if (busy) return
        error = null
        newHref = null
        naming = target
    }

    fun requestDelete(addressBook: ManagedAddressBook) {
        if (!busy) deleting = addressBook
    }

    fun cancelDialog() {
        if (busy) return
        naming = null
        deleting = null
        newHref = null
    }

    fun saveName(name: String) {
        val target = naming ?: return
        addressBookNameMessage(name)?.let { error = it; return }
        when (target) {
            AddressBookNaming.New -> create(name)
            is AddressBookNaming.Rename -> change(
                addressBookRenameRequest(target.addressBook.href, name),
                provesRejection = ::groupwareMutationResponseProvesRejection,
            ) { books -> books.any { it.href == target.addressBook.href && it.displayName == name.trim() } }
        }
    }

    private fun create(name: String) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            var handedOver = false
            try {
                val href = creationHref(name) ?: return@launch
                busy = false
                handedOver = true
                change(
                    addressBookCreateRequest(href, name),
                    provesRejection = { it != 405 && groupwareMutationResponseProvesRejection(it) },
                    onSettled = { forgetCreation() },
                ) { books -> books.any { it.href == href && it.displayName == name.trim() } }
            } finally {
                if (!handedOver) busy = false
            }
        }
    }

    /**
     * The address to create at, recorded on the device before it is used, so a creation whose
     * result was lost is found again after the dialog closed or the app restarted. Null, with
     * [error] set, when nothing may be sent: the earlier creation exists, or could not be checked.
     */
    private suspend fun creationHref(name: String): String? {
        newHref?.let { return it }
        val home = groupwareAddressBookHomeHref(userId)
        val store = services.textEditorDraftStore(session, PENDING_ADDRESS_BOOK_PATH)
        val earlier = try {
            store?.load()?.text?.takeIf { it.startsWith(home) && it.length <= MAX_PENDING_ADDRESS_BOOK_HREF }
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            // Persistence fault boundary: an unreadable record may be an address book already created.
            error = "An earlier attempt saved on this device could not be read. Check the list before creating another."
            return null
        }
        if (earlier != null) {
            val listed = read()
            if (listed == null) {
                error = "An earlier attempt could not be checked. Check the list before trying again."
                return null
            }
            addressBooks = listed
            // An address book the listing names but could not describe is still there.
            if (listed.any { it.href == earlier } || earlier in listedHrefs) {
                forgetCreation()
                onChanged()
                error = "An earlier attempt was created after all and is now in the list. Check it before creating another."
                return null
            }
        }
        val href = newAddressBookHref(home, name)
        try {
            store?.save(TextEditorDraft(originalText = "", text = href, etag = null))
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            error = "This device could not keep a record of the new address book, so it was not created."
            return null
        }
        newHref = href
        return href
    }

    /**
     * The creation was confirmed or refused; its record is dropped, and with it the address kept
     * for this dialog, so a later attempt is recorded again before it is sent.
     */
    private suspend fun forgetCreation() {
        newHref = null
        try {
            services.textEditorDraftStore(session, PENDING_ADDRESS_BOOK_PATH)?.remove()
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            // A record left behind only causes one more check of the list.
        }
    }

    fun confirmDelete() {
        val target = deleting ?: return
        change(
            addressBookDeleteRequest(target.href),
            provesRejection = { !groupwareDeleteResponseProvesAbsence(it) && groupwareMutationResponseProvesRejection(it) },
        ) { books -> books.none { it.href == target.href } && target.href !in listedHrefs }
    }

    private fun change(
        request: GroupwareDavRequest,
        provesRejection: (Int) -> Boolean,
        /** Runs once the server answered definitely: the change was refused or is confirmed. */
        onSettled: suspend () -> Unit = {},
        confirmed: (List<ManagedAddressBook>) -> Boolean,
    ) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                val status = try {
                    services.executeGroupwareDav(session, request).status
                } catch (failure: CancellationException) {
                    throw failure
                } catch (_: Exception) {
                    null
                }
                if (status != null && provesRejection(status)) {
                    onSettled()
                    error = calendarCollectionRejectionMessage(status).replace("calendar", "address book")
                    return@launch
                }
                val listed = read()
                if (listed != null) addressBooks = listed
                if (listed != null && confirmed(listed)) {
                    onSettled()
                    naming = null
                    deleting = null
                    newHref = null
                    onChanged()
                } else {
                    error = if (status in 200..299) "The change has not appeared on the server yet. Refresh to check it."
                    else "The result is unknown. Check the list before trying again."
                }
            } finally {
                busy = false
            }
        }
    }

    private suspend fun read(): List<ManagedAddressBook>? = try {
        val listing = services.executeGroupwareDav(session, groupwareDavCollectionDiscoveryRequest(groupwareAddressBookHomeHref(userId)))
        parseManagedAddressBooks(listing, userId).also { listedHrefs = groupwareCollectionHrefs(listing) }
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Exception) {
        null
    }
}

@Composable
internal fun rememberAddressBookManager(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    userId: String,
    onChanged: () -> Unit,
): AddressBookManager {
    val scope = rememberCoroutineScope()
    val currentOnChanged by rememberUpdatedState(onChanged)
    return remember(services, session, userId, scope) {
        AddressBookManager(services, session, userId, scope).apply { this.onChanged = { currentOnChanged() } }
    }
}

/** The list of address books with their actions, plus the naming and delete dialogs. */
@Composable
internal fun AddressBookManagerDialogs(manager: AddressBookManager, sharing: GroupwareSharingManager) {
    if (manager.open) {
        AlertDialog(
            onDismissRequest = manager::dismiss,
            title = { Text("Address books") },
            text = {
                LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    item { if (manager.busy || manager.addressBooks == null) LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    items(manager.addressBooks.orEmpty(), key = ManagedAddressBook::href) { book ->
                        AddressBookRow(book, manager, onShare = {
                            sharing.open(GroupwareShareTarget(book.href, book.displayName, publishable = false))
                        })
                    }
                    manager.error?.takeIf { manager.naming == null && manager.deleting == null }?.let { message ->
                        item { Text(message, color = MaterialTheme.colorScheme.error) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = manager::dismiss, enabled = !manager.busy) { Text("Done") } },
            dismissButton = {
                TextButton(onClick = { manager.startNaming(AddressBookNaming.New) }, enabled = !manager.busy) {
                    Icon(NextcloudIcons.Add, null)
                    Text("New address book")
                }
            },
        )
    }
    manager.naming?.let { naming -> AddressBookNameDialog(naming, manager) }
    manager.deleting?.let { book ->
        GroupwareConfirmDialog(
            title = if (book.ownership == AddressBookOwnership.Own) "Delete ${book.displayName}?" else "Remove ${book.displayName}?",
            message = if (book.ownership == AddressBookOwnership.Own) {
                "The address book and every contact in it are deleted for everyone it is shared with. This cannot be undone." +
                    (manager.error?.let { "\n\n$it" } ?: "")
            } else {
                "It is removed from your address books. ${book.ownerName ?: "The owner"} keeps it." + (manager.error?.let { "\n\n$it" } ?: "")
            },
            action = if (book.ownership == AddressBookOwnership.Own) "Delete" else "Remove",
            onConfirm = manager::confirmDelete,
            onDismiss = manager::cancelDialog,
            busy = manager.busy,
        )
    }
}

@Composable
private fun AddressBookRow(book: ManagedAddressBook, manager: AddressBookManager, onShare: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(book.displayName, style = MaterialTheme.typography.bodyMedium)
            Text(
                when (book.ownership) {
                    AddressBookOwnership.Own -> "Yours"
                    AddressBookOwnership.SharedWithMe -> "Shared by ${book.ownerName ?: "someone else"}"
                    AddressBookOwnership.System -> "Managed by the server"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (book.ownership != AddressBookOwnership.System) {
            Box {
                IconButton(onClick = { menuOpen = true }, enabled = !manager.busy) {
                    Icon(NextcloudIcons.More, "Actions for ${book.displayName}")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (book.ownership == AddressBookOwnership.Own) {
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { menuOpen = false; manager.startNaming(AddressBookNaming.Rename(book)) })
                        DropdownMenuItem(text = { Text("Share") }, onClick = { menuOpen = false; onShare() })
                    }
                    DropdownMenuItem(
                        text = { Text(if (book.ownership == AddressBookOwnership.Own) "Delete" else "Remove", color = MaterialTheme.colorScheme.error) },
                        onClick = { menuOpen = false; manager.requestDelete(book) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AddressBookNameDialog(naming: AddressBookNaming, manager: AddressBookManager) {
    val existing = (naming as? AddressBookNaming.Rename)?.addressBook
    var name by remember(naming) { mutableStateOf(existing?.displayName.orEmpty()) }
    AlertDialog(
        onDismissRequest = manager::cancelDialog,
        title = { Text(if (existing == null) "New address book" else "Rename address book") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(200) }, label = { Text("Name") }, singleLine = true,
                    enabled = !manager.busy, modifier = Modifier.fillMaxWidth())
                manager.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = { manager.saveName(name) }, enabled = !manager.busy && addressBookNameMessage(name) == null &&
                name.trim() != existing?.displayName) {
                Text(if (manager.busy) "Saving..." else if (existing == null) "Create" else "Save")
            }
        },
        dismissButton = { TextButton(onClick = manager::cancelDialog, enabled = !manager.busy) { Text("Cancel") } },
    )
}

private const val PENDING_ADDRESS_BOOK_PATH = "nextcloud-native:address-book-pending-creation"
private const val MAX_PENDING_ADDRESS_BOOK_HREF = 2048
