package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing

@Composable
internal fun CalendarWorkspaceNotice(message: String, onRetry: () -> Unit, onRecovery: (() -> Unit)?) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Small),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(NextcloudRadii.Small),
    ) {
        Column(Modifier.padding(horizontal = NextcloudSpacing.Medium, vertical = NextcloudSpacing.Small)) {
            Text(message, style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                TextButton(onClick = onRetry) { Text("Retry") }
                onRecovery?.let { TextButton(onClick = it) { Text("Recovery options") } }
            }
        }
    }
}
