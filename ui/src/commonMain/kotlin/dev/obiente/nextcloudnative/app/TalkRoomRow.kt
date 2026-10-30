package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme

/** One Talk conversation in the room list, with its divider, as a single list item. */
@Composable
internal fun TalkRoomRow(room: TalkRoom, onOpenRoom: (TalkRoom) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { onOpenRoom(room) }
                .padding(horizontal = NextcloudSpacing.XLarge, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
        ) {
            Surface(color = NextcloudTheme.colors.appIconContainer, shape = CircleShape) {
                Icon(
                    NextcloudIcons.app("talk"),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(10.dp).size(24.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(room.displayName, Modifier.sharedElement(SharedElementKeys.talkRoomTitle(room.token)), fontWeight = FontWeight.SemiBold)
                room.lastMessage?.let {
                    Text(
                        it,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (room.unreadMessages > 0) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape) {
                    Text(
                        room.unreadMessages.toString(),
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            Icon(NextcloudIcons.ChevronRight, contentDescription = "Open ${room.displayName}")
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = 84.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}
