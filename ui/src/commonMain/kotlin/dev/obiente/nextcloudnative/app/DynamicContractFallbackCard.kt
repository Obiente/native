package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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

/** The notice above a metadata fallback: what happened, and the action that can help, if any. */
@Composable
internal fun DynamicContractFallbackCard(
    reason: DynamicContractFallbackReason?,
    onRetryDiscovery: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(
            horizontal = NextcloudSpacing.Large,
            vertical = NextcloudSpacing.Small,
        ),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(NextcloudRadii.Small),
    ) {
        Column(
            modifier = Modifier.padding(NextcloudSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall),
        ) {
            val notice = reason.fallbackNotice()
            Text(notice.title, style = MaterialTheme.typography.titleSmall)
            Text(notice.message, style = MaterialTheme.typography.bodySmall)
            notice.actionLabel?.let { label -> TextButton(onClick = onRetryDiscovery) {
                Text(label)
            } }
        }
    }
}
