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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme

@Composable
internal fun NativeRosterSurface(roster: NativeRosterPresentation) {
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
        item { Text(roster.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
        item { Text("Members", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
        items(roster.people, key = NativeRosterPerson::userId) { person ->
            NativeRosterPersonRow(
                title = person.displayName,
                subtitle = buildString {
                    person.score?.let { append("$it points") }
                    if (person.owner) {
                        if (isNotEmpty()) append("  •  ")
                        append("Owner")
                    }
                },
            )
        }
        item {
            Text("Pending invitations", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        if (roster.invitations.isEmpty()) {
            item { Text("No pending invitations", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            items(roster.invitations, key = NativeRosterInvitation::userId) { invitation ->
                NativeRosterPersonRow(invitation.userId, "Waiting to join")
            }
        }
    }
}

@Composable
private fun NativeRosterPersonRow(title: String, subtitle: String) {
    Surface(color = NextcloudTheme.colors.appTile, shape = RoundedCornerShape(NextcloudRadii.Card)) {
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
        }
    }
}
