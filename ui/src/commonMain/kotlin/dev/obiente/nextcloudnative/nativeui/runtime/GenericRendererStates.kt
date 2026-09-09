package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme

@Composable
internal fun GenericRendererLoading(title: String) {
    GenericCenteredState {
        Surface(color = NextcloudTheme.colors.appIconContainer, shape = MaterialTheme.shapes.medium) {
            Box(modifier = Modifier.padding(NextcloudSpacing.Large), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
            }
        }
        Text("Loading $title", style = MaterialTheme.typography.titleMedium)
        Text(
            "Fetching the latest data from your server...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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
    Box(modifier = Modifier.fillMaxSize().padding(NextcloudSpacing.XLarge), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
            content = content,
        )
    }
}

@Composable
internal fun GenericStateIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, error: Boolean = false) {
    Surface(
        color = if (error) MaterialTheme.colorScheme.errorContainer else NextcloudTheme.colors.appIconContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (error) MaterialTheme.colorScheme.onErrorContainer else NextcloudTheme.colors.appIcon,
            modifier = Modifier.padding(NextcloudSpacing.Large).size(32.dp),
        )
    }
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
