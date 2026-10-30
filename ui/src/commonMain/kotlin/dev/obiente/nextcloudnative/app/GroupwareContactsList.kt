package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudCollectionDestination
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing

internal const val ALL_CONTACTS_DESTINATION_ID = "all-contacts"

/**
 * Address books as list filters. A single address book needs no filter, so it gets no
 * destinations and the workspace shows no tabs, rail, or sidebar.
 */
internal fun contactsDestinations(ready: ContactsLoadState.Ready?): List<NextcloudCollectionDestination> {
    val addressBooks = ready?.addressBooks.orEmpty()
    if (addressBooks.size < 2) return emptyList()
    return listOf(
        NextcloudCollectionDestination(ALL_CONTACTS_DESTINATION_ID, "All contacts", count = ready?.contacts?.size),
    ) + addressBooks.map { addressBook ->
        NextcloudCollectionDestination(
            id = addressBook.href,
            label = addressBook.displayName.ifBlank { "Address book" },
            count = ready?.contacts?.count { contact -> contact.addressBookHref == addressBook.href },
            accessibilityId = "address-book",
        )
    }
}

/** The address book a saved filter names, or null for all contacts when it no longer exists. */
internal fun contactsFilterAddressBook(
    ready: ContactsLoadState.Ready?,
    filterHref: String?,
): GroupwareAddressBook? = filterHref?.let { href -> ready?.addressBooks?.firstOrNull { it.href == href } }

internal fun filterContacts(
    contacts: List<GroupwareContact>,
    addressBook: GroupwareAddressBook?,
    query: String,
): List<GroupwareContact> {
    val needle = query.trim().lowercase()
    return contacts.filter { contact ->
        (addressBook == null || contact.addressBookHref == addressBook.href) && (
            needle.isBlank() || listOf(
                contact.displayName,
                contact.organization,
                contact.address,
                contact.emails.joinToString(" "),
                contact.phones.joinToString(" "),
            ).any { value -> value?.lowercase()?.contains(needle) == true }
        )
    }
}

@Composable
internal fun ContactList(
    contacts: List<GroupwareContact>,
    selectedHref: String?,
    listState: LazyListState,
    onSelect: (GroupwareContact) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        state = listState,
        contentPadding = PaddingValues(NextcloudSpacing.Large),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
    ) {
        items(contacts, key = GroupwareContact::href) { contact ->
            val selected = contact.href == selectedHref
            Card(
                modifier = Modifier.fillMaxWidth().semantics { this.selected = selected }.clickable { onSelect(contact) },
                colors = workspaceListCardColors(selected),
                border = workspaceListCardBorder(selected),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ContactAvatar(contact)
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
