package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudLoadingState
import dev.obiente.nextcloudnative.app.design.NextcloudSkeletonLayout
import dev.obiente.nextcloudnative.app.design.NextcloudStateLayout
import dev.obiente.nextcloudnative.app.design.NextcloudStatusIcon
import dev.obiente.nextcloudnative.app.design.NextcloudStatusTone
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing

/** First load of a dynamic view, shaped like the surface that is coming when it has a shape. */
@Composable
internal fun GenericRendererLoading(title: String, skeleton: NextcloudSkeletonLayout? = null) {
    NextcloudLoadingState("Loading $title", skeleton = skeleton)
}

@Composable
internal fun GenericRendererEmpty(
    resourceId: String,
    resourceName: String,
    createLabel: String? = null,
    onCreate: (() -> Unit)? = null,
) {
    GenericCenteredState {
        GenericStateIcon(NextcloudIcons.Apps)
        Text("No ${resourceName.lowercase()} yet", style = MaterialTheme.typography.titleLarge)
        Text(
            "New items will appear here when the server returns them.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (onCreate != null) {
            Button(
                onClick = onCreate,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics {
                        contentDescription = "Create $resourceId"
                    },
            ) {
                Icon(NextcloudIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    createLabel?.takeIf(String::isNotBlank) ?: "Create item",
                    modifier = Modifier.padding(start = NextcloudSpacing.Small),
                )
            }
        }
    }
}

@Composable
internal fun GenericCenteredState(content: @Composable ColumnScope.() -> Unit) {
    NextcloudStateLayout(content = content)
}

@Composable
internal fun GenericStateIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, error: Boolean = false) {
    NextcloudStatusIcon(icon, if (error) NextcloudStatusTone.Error else NextcloudStatusTone.Neutral)
}

@Composable
internal fun GenericRendererError(
    message: String,
    retry: (() -> Unit)? = null,
    retryLabel: String = "Try again",
) {
    GenericCenteredState {
        GenericStateIcon(NextcloudIcons.Error, error = true)
        Text("Could not show this view", style = MaterialTheme.typography.titleLarge)
        Text(
            message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        retry?.let { action ->
            Button(onClick = action, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(NextcloudIcons.Refresh, contentDescription = null, modifier = Modifier.size(19.dp))
                Text(retryLabel, modifier = Modifier.padding(start = NextcloudSpacing.Small))
            }
        }
    }
}
