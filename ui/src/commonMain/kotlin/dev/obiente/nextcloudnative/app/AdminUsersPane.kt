package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudCardAction
import dev.obiente.nextcloudnative.app.design.NextcloudCardOverflow
import dev.obiente.nextcloudnative.app.design.NextcloudChoiceField
import dev.obiente.nextcloudnative.app.design.NextcloudChoiceOption
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing

/** Accounts list with search and paging, and the selected account's administration detail. */
@Composable
internal fun AdminUsersPane(controller: AdminUsersController, signedInUserId: String, modifier: Modifier = Modifier) {
    val state = controller.state
    var query by rememberSaveable { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    LaunchedEffect(controller, query) {
        if (query != controller.state.query || !controller.state.listLoaded) {
            if (controller.state.listLoaded) kotlinx.coroutines.delay(300)
            controller.search(query)
        }
    }
    LaunchedEffect(controller) { if (!controller.state.groupsLoaded) controller.loadGroups() }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val twoPane = maxWidth >= 720.dp
        val list: @Composable (Modifier) -> Unit = { listModifier ->
            AdminUserList(state, query, { query = it.take(120) }, controller::select, controller::loadMore,
                onCreate = { creating = true }, canWrite = controller.canWrite, modifier = listModifier)
        }
        when {
            twoPane -> Row(Modifier.fillMaxSize()) {
                list(Modifier.width(340.dp).fillMaxHeight())
                VerticalDivider()
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (state.selectedUserId == null) {
                        EmptyMessage("Choose an account to manage it.")
                    } else {
                        AdminUserDetail(controller, signedInUserId, onClose = null)
                    }
                }
            }
            state.selectedUserId != null -> {
                PlatformBackHandler(enabled = true) { controller.select(null) }
                AdminUserDetail(controller, signedInUserId, onClose = { controller.select(null) })
            }
            else -> list(Modifier.fillMaxSize())
        }
    }
    if (creating) {
        AdminCreateUserFlow(controller, onClose = { creating = false })
    }
}

@Composable
private fun AdminUserList(
    state: AdminUsersUiState,
    query: String,
    onQueryChanged: (String) -> Unit,
    onSelect: (String) -> Unit,
    onLoadMore: () -> Unit,
    onCreate: () -> Unit,
    canWrite: Boolean,
    modifier: Modifier,
) {
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChanged,
                modifier = Modifier.weight(1f).testTag("admin-users-search"),
                label = { Text("Search accounts") },
                singleLine = true,
            )
            Button(onClick = onCreate, enabled = canWrite, modifier = Modifier.heightIn(min = 48.dp)) { Text("New") }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = NextcloudSpacing.Medium, vertical = NextcloudSpacing.Small),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (state.listFailure != null) {
                item(key = "failure") { Text(state.listFailure, color = MaterialTheme.colorScheme.error) }
            }
            if (state.listLoaded && state.users.isEmpty() && state.listFailure == null && !state.loadingList) {
                item(key = "empty") { Text("No accounts match this search.", style = MaterialTheme.typography.bodyMedium) }
            }
            items(state.users, key = AdminUser::id) { user ->
                AdminUserRow(user, selected = user.id == state.selectedUserId, onOpen = { onSelect(user.id) })
            }
            if (state.loadingList) {
                item(key = "loading") { Text("Loading accounts...", style = MaterialTheme.typography.bodySmall) }
            } else if (state.mayHaveMore) {
                item(key = "more") { TextButton(onClick = onLoadMore) { Text("Load more accounts") } }
            }
        }
    }
}

@Composable
private fun AdminUserRow(user: AdminUser, selected: Boolean, onOpen: () -> Unit) {
    Surface(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("admin-user-${user.id}"),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(NextcloudRadii.Card),
    ) {
        Row(Modifier.padding(NextcloudSpacing.Medium), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(user.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(user.email ?: user.id, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (user.enabled == false) {
                Text("Disabled", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun AdminUserDetail(controller: AdminUsersController, signedInUserId: String, onClose: (() -> Unit)?) {
    val state = controller.state
    val user = state.selectedUser
    var pending by remember(state.selectedUserId) { mutableStateOf<AdminUserMutation?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var groupToAdd by remember(state.selectedUserId) { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(NextcloudSpacing.Large),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            onClose?.let { TextButton(onClick = it) { Text("Back") } }
            Column(Modifier.weight(1f)) {
                Text(user?.displayName ?: state.selectedUserId.orEmpty(), style = MaterialTheme.typography.headlineSmall)
                Text(state.selectedUserId.orEmpty(), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val allowed = user != null && adminUserActionsAllowed(user, signedInUserId) && controller.canWrite
            if (allowed && user != null) {
                NextcloudCardOverflow(
                    itemLabel = user.displayName,
                    expanded = menuOpen,
                    onExpandedChange = { menuOpen = it },
                    actions = listOf(
                        NextcloudCardAction(if (user.enabled == false) "Enable account" else "Disable account",
                            destructive = user.enabled != false) {
                            pending = AdminUserMutation.SetEnabled(user.id, user.enabled == false)
                        },
                        NextcloudCardAction("Delete account", destructive = true) { pending = AdminUserMutation.Delete(user.id) },
                    ),
                )
            }
        }
        state.detailFailure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.mutationMessage?.let { message ->
            Text(message, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = if (state.mutationFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall)
        }
        if (user == null) return@Column
        if (user.id == signedInUserId) {
            Text("This is your own account. Change your details in Personal info.", style = MaterialTheme.typography.bodySmall)
        }
        AdminFacts(user)
        val editable = controller.canWrite && adminUserActionsAllowed(user, signedInUserId)
        Text("Groups", style = MaterialTheme.typography.titleSmall)
        if (user.groups.isEmpty()) Text("Not in any group.", style = MaterialTheme.typography.bodySmall)
        user.groups.forEach { group ->
            AdminMembershipRow(group, subadmin = group in user.subadminGroups, editable = editable,
                onRemove = { pending = AdminUserMutation.RemoveFromGroup(user.id, group) },
                onToggleSubadmin = {
                    pending = if (group in user.subadminGroups) AdminUserMutation.RemoveSubadmin(user.id, group)
                    else AdminUserMutation.AddSubadmin(user.id, group)
                })
        }
        if (editable) {
            val candidates = state.groups.filter { it.id !in user.groups }
            Row(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small), verticalAlignment = Alignment.CenterVertically) {
                NextcloudChoiceField(
                    label = "Add to group",
                    options = candidates.map { NextcloudChoiceOption(it.id, it.displayName, searchTerms = listOf(it.id)) },
                    selectedId = groupToAdd,
                    onSelected = { groupToAdd = it },
                    modifier = Modifier.weight(1f),
                    enabled = candidates.isNotEmpty(),
                    fieldKey = "add-group-${user.id}",
                )
                OutlinedButton(onClick = { groupToAdd?.let { pending = AdminUserMutation.AddToGroup(user.id, it) } },
                    enabled = groupToAdd != null) { Text("Add") }
            }
            NextcloudChoiceField(
                label = "Quota",
                options = AdminQuotaPreset.entries.map { NextcloudChoiceOption(it.name, it.label) },
                selectedId = null,
                placeholder = user.quota?.quotaBytes?.let(::formatByteSize) ?: "Choose a quota",
                onSelected = { id -> AdminQuotaPreset.entries.firstOrNull { it.name == id }?.let { pending = AdminUserMutation.SetQuota(user.id, it) } },
                fieldKey = "quota-${user.id}",
            )
        }
    }
    pending?.let { mutation ->
        AccountPasswordConfirmationDialog(
            title = mutation.confirmationTitle(),
            description = mutation.confirmationDescription(),
            confirmLabel = mutation.confirmationLabel(),
            destructive = mutation.destructive || mutation is AdminUserMutation.SetEnabled && !mutation.enabled,
            busy = state.mutating,
            onConfirm = { secret -> controller.apply(mutation, secret) { pending = null } },
            onDismiss = { pending = null },
        )
    }
}

@Composable
private fun AdminFacts(user: AdminUser) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(NextcloudRadii.Card)) {
        Column(Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOfNotNull(
                "Status" to when (user.enabled) { true -> "Enabled"; false -> "Disabled"; null -> "Unknown" },
                user.email?.let { "Email" to it },
                user.quota?.usedBytes?.let { used ->
                    "Storage" to (user.quota.quotaBytes?.let { "${formatByteSize(used)} of ${formatByteSize(it)}" } ?: formatByteSize(used))
                },
                user.backend?.let { "Account backend" to it },
                "Last sign-in" to (user.lastLoginEpochMillis?.let { formatTalkMessageTimeUtc(it / 1_000L) } ?: "Never"),
            ).forEach { (label, value) ->
                Row(Modifier.fillMaxWidth()) {
                    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun AdminMembershipRow(group: String, subadmin: Boolean, editable: Boolean, onRemove: () -> Unit, onToggleSubadmin: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(group, style = MaterialTheme.typography.bodyMedium)
            if (subadmin) Text("Group administrator", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        if (editable) {
            NextcloudCardOverflow(
                itemLabel = group,
                expanded = menuOpen,
                onExpandedChange = { menuOpen = it },
                actions = listOf(
                    NextcloudCardAction(if (subadmin) "Stop administering group" else "Make group administrator", onClick = onToggleSubadmin),
                    NextcloudCardAction("Remove from group", destructive = true, onClick = onRemove),
                ),
            )
        }
    }
}

@Composable
private fun AdminCreateUserFlow(controller: AdminUsersController, onClose: () -> Unit) {
    var userId by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var group by remember { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf(false) }
    val draft = AdminNewUser(userId.trim(), displayName, email.trim(), password, listOfNotNull(group), quota = null)
    val valid = runCatching { AdminUserRequests.createUser(draft) }.isSuccess
    if (!confirming) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onClose,
            title = { Text("New account") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                    OutlinedTextField(userId, { userId = it.take(64) }, label = { Text("Account name") }, singleLine = true)
                    OutlinedTextField(displayName, { displayName = it.take(64) }, label = { Text("Display name") }, singleLine = true)
                    OutlinedTextField(email, { email = it.take(255) }, label = { Text("Email") }, singleLine = true)
                    OutlinedTextField(
                        password, { password = it.take(MAX_ACCOUNT_PASSWORD_LENGTH) },
                        label = { Text("Password (optional with email)") }, singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    )
                    NextcloudChoiceField(
                        label = "Group",
                        options = controller.state.groups.map { NextcloudChoiceOption(it.id, it.displayName) },
                        selectedId = group,
                        onSelected = { group = it },
                        placeholder = "No group",
                        fieldKey = "new-user-group",
                    )
                    Text("Without a password, Nextcloud emails a link to set one.", style = MaterialTheme.typography.bodySmall)
                }
            },
            dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
            confirmButton = { Button(onClick = { confirming = true }, enabled = valid) { Text("Continue") } },
        )
    } else {
        AccountPasswordConfirmationDialog(
            title = "Create ${draft.userId}?",
            description = "A new account will be created" + (group?.let { " in group $it" } ?: "") + ".",
            confirmLabel = "Create account",
            busy = controller.state.mutating,
            onConfirm = { secret ->
                controller.create(draft, secret) {
                    password = ""
                    onClose()
                }
            },
            onDismiss = { confirming = false },
        )
    }
}

internal fun AdminUserMutation.confirmationTitle(): String = when (this) {
    is AdminUserMutation.SetEnabled -> if (enabled) "Enable $userId?" else "Disable $userId?"
    is AdminUserMutation.AddToGroup -> "Add $userId to $groupId?"
    is AdminUserMutation.RemoveFromGroup -> "Remove $userId from $groupId?"
    is AdminUserMutation.AddSubadmin -> "Make $userId an administrator of $groupId?"
    is AdminUserMutation.RemoveSubadmin -> "Stop $userId administering $groupId?"
    is AdminUserMutation.SetQuota -> "Set the quota of $userId to ${quota.label}?"
    is AdminUserMutation.SetDisplayName -> "Rename $userId?"
    is AdminUserMutation.SetEmail -> "Change the email of $userId?"
    is AdminUserMutation.Delete -> "Delete $userId?"
}

internal fun AdminUserMutation.confirmationDescription(): String = when (this) {
    is AdminUserMutation.SetEnabled -> if (enabled) "The account can sign in again." else
        "The account is signed out everywhere and cannot sign in until it is enabled again. Its files are kept."
    is AdminUserMutation.RemoveFromGroup -> "The account loses access to shares and apps limited to this group."
    is AdminUserMutation.Delete -> "The account, its files, and its app data are deleted from the server. This cannot be undone."
    else -> "The change applies on the server immediately."
}

internal fun AdminUserMutation.confirmationLabel(): String = when (this) {
    is AdminUserMutation.Delete -> "Delete account"
    is AdminUserMutation.SetEnabled -> if (enabled) "Enable" else "Disable"
    is AdminUserMutation.RemoveFromGroup -> "Remove"
    else -> "Apply"
}
