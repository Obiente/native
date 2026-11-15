package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing

/** Groups with member counts, read-only. */
@Composable
internal fun AdminGroupsPane(controller: AdminUsersController, modifier: Modifier = Modifier) {
    val state = controller.state
    LaunchedEffect(controller) { controller.loadGroups() }
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(NextcloudSpacing.Medium),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item(key = "handoff") { PasswordConfirmationHandoffNotice() }
        state.groupsFailure?.let { failure -> item(key = "failure") { Text(failure, color = MaterialTheme.colorScheme.error) } }
        if (!state.groupsLoaded) item(key = "loading") { Text("Loading groups...") }
        if (state.groupsLoaded && state.groups.isEmpty() && state.groupsFailure == null) {
            item(key = "empty") { Text("This server has no groups.", style = MaterialTheme.typography.bodyMedium) }
        }
        items(state.groups, key = AdminGroup::id) { group -> AdminGroupRow(group) }
    }
}

@Composable
private fun AdminGroupRow(group: AdminGroup) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(NextcloudRadii.Card)) {
        Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(NextcloudSpacing.Medium)) {
            Text(group.displayName, style = MaterialTheme.typography.titleSmall)
            Text(
                listOfNotNull(
                    group.id.takeIf { it != group.displayName },
                    group.userCount?.let { "$it member(s)" },
                    group.disabledCount?.takeIf { it > 0 }?.let { "$it disabled" },
                ).joinToString(" - "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
