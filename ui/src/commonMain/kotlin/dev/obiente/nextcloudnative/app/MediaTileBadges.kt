package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons

/** Stack and video cues drawn over a gallery tile; the tile itself stays the only action. */
@Composable
internal fun BoxScope.MediaTileBadges(file: NextcloudFile, stackBadge: String?) {
    stackBadge?.let { label ->
        MediaTileBadge(modifier = Modifier.align(Alignment.TopEnd)) {
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
    mediaTileVideoBadgeLabel(file)?.let { label ->
        MediaTileBadge(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .semantics { contentDescription = mediaTileVideoBadgeDescription(file) },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(NextcloudIcons.Play, contentDescription = null, modifier = Modifier.size(14.dp))
                if (label.isNotEmpty()) {
                    Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 2.dp))
                }
            }
        }
    }
}

@Composable
private fun MediaTileBadge(modifier: Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.padding(6.dp),
        color = Color.Black.copy(alpha = 0.72f),
        contentColor = Color.White,
        shape = RoundedCornerShape(6.dp),
    ) {
        Row(modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)) { content() }
    }
}

/** Null for non-video media; empty when the duration is unknown so only the play cue shows. */
internal fun mediaTileVideoBadgeLabel(file: NextcloudFile): String? {
    if (file.isDirectory || file.mediaAssetFormat() != MediaAssetFormat.Video) return null
    return file.mediaDurationSeconds?.takeIf { it >= 0 }?.let(::formatMediaDuration).orEmpty()
}

private fun mediaTileVideoBadgeDescription(file: NextcloudFile): String =
    file.mediaDurationSeconds?.takeIf { it >= 0 }
        ?.let { "Video, ${formatMediaDuration(it)}" }
        ?: "Video"
