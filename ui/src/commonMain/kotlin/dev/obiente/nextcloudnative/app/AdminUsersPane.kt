package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import kotlinx.coroutines.delay

/** Accounts list with search and paging beside, or above, the selected account's details. */
@Composable
internal fun AdminUsersPane(controller: AdminUsersController, modifier: Modifier = Modifier) {
    val state = controller.state
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(controller, query) {
        if (query != controller.state.query || !controller.state.listLoaded) {
            // Typing is debounced; the first load starts at once.
            if (controller.state.listLoaded) delay(300)
            controller.search(query)
        }
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val twoPane = maxWidth >= 720.dp
        val list: @Composable (Modifier) -> Unit = { listModifier ->
            AdminUserList(state, query, { query = it.take(120) }, controller::select, controller::loadMore, listModifier)
        }
        when {
            twoPane -> Row(Modifier.fillMaxSize()) {
                list(Modifier.width(340.dp).fillMaxHeight())
                VerticalDivider()
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (state.selectedUserId == null) {
                        EmptyMessage("Choose an account to see its details.")
                    } else {
                        AdminUserDetail(state, onClose = null)
                    }
                }
            }
            state.selectedUserId != null -> {
                PlatformBackHandler(enabled = true) { controller.select(null) }
                AdminUserDetail(state, onClose = { controller.select(null) })
            }
            else -> list(Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun AdminUserList(
    state: AdminUsersUiState,
    query: String,
    onQueryChanged: (String) -> Unit,
    onSelect: (String) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChanged,
            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium).testTag("admin-users-search"),
            label = { Text("Search accounts") },
            singleLine = true,
        )
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
private fun AdminUserDetail(state: AdminUsersUiState, onClose: (() -> Unit)?) {
    val user = state.selectedUser
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
        }
        state.detailFailure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (user == null) return@Column
        if (!user.detailsVisible) {
            Text("This account cannot see the details of this user.", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        AdminFacts(user)
        Text("Groups", style = MaterialTheme.typography.titleSmall)
        if (user.groups.isEmpty()) Text("Not in any group.", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            user.groups.forEach { group ->
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(50)) {
                    Text(
                        if (group in user.subadminGroups) "$group (group administrator)" else group,
                        Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
        PasswordConfirmationHandoffNotice()
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
