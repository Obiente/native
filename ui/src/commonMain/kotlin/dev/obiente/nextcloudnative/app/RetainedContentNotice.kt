package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons

/** A failed refresh does not make the content already on screen unusable. */
@Composable
internal fun RetainedContentNotice(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    actionLabel: String = "Retry",
) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface, shape = MaterialTheme.shapes.medium) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(NextcloudIcons.Info, contentDescription = null)
            Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onRetry) { Text(actionLabel) }
        }
    }
}
