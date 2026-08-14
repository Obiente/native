package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudCardAction
import dev.obiente.nextcloudnative.app.design.NextcloudCardOverflow
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme
import dev.obiente.nextcloudnative.app.design.nextcloudCardInteractions

@Composable
internal fun NativeRosterSurface(
    roster: NativeRosterPresentation,
    createLabel: String? = null,
    onCreate: (() -> Unit)? = null,
    memberActions: (NativeRosterPerson) -> List<NextcloudCardAction> = { emptyList() },
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = NextcloudSpacing.Large,
            top = NextcloudSpacing.Small,
            end = NextcloudSpacing.Large,
            bottom = NextcloudSpacing.XXLarge,
        ),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    roster.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                if (createLabel != null && onCreate != null) {
                    Button(onClick = onCreate) { Text(createLabel) }
                }
            }
        }
        if (roster.omittedPeople > 0 || roster.omittedInvitations > 0) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = RoundedCornerShape(NextcloudRadii.Card),
                ) {
                    Text(
                        "Some team information could not be shown. Refresh to try again.",
                        modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        item { Text("Members", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
        items(roster.people, key = { person -> "member:${person.userId}" }) { person ->
            NativeRosterPersonRow(
                stateKey = "member:${person.userId}",
                itemLabel = person.displayName,
                title = person.displayName,
                subtitle = buildString {
                    person.score?.let { append("$it points") }
                    if (person.owner) {
                        if (isNotEmpty()) append("  •  ")
                        append("Owner")
                    }
                },
                actions = memberActions(person),
            )
        }
        item {
            Text("Pending invitations", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        if (roster.invitations.isEmpty() && roster.omittedInvitations == 0) {
            item { Text("No pending invitations", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            items(roster.invitations, key = { invitation -> "invitation:${invitation.userId}" }) { invitation ->
                NativeRosterPersonRow(
                    stateKey = "invitation:${invitation.userId}",
                    itemLabel = invitation.userId,
                    title = invitation.userId,
                    subtitle = "Waiting to join",
                )
            }
        }
    }
}

@Composable
private fun NativeRosterPersonRow(
    stateKey: String,
    itemLabel: String,
    title: String,
    subtitle: String,
    actions: List<NextcloudCardAction> = emptyList(),
) {
    var actionsExpanded by rememberSaveable(stateKey) { mutableStateOf(false) }
    Surface(
        modifier = Modifier.nextcloudCardInteractions(
            onOpen = null,
            onShowActions = actions.takeIf { it.isNotEmpty() }?.let { { actionsExpanded = true } },
            actionsLabel = "Actions for $itemLabel",
        ),
        color = NextcloudTheme.colors.appTile,
        shape = RoundedCornerShape(NextcloudRadii.Card),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(color = NextcloudTheme.colors.appIconContainer, shape = CircleShape) {
                Icon(
                    NextcloudIcons.People,
                    contentDescription = null,
                    modifier = Modifier.padding(NextcloudSpacing.Small).size(24.dp),
                    tint = NextcloudTheme.colors.appIcon,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (subtitle.isNotBlank()) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            NextcloudCardOverflow(
                itemLabel = itemLabel,
                actions = actions,
                expanded = actionsExpanded,
                onExpandedChange = { actionsExpanded = it },
            )
        }
    }
}
