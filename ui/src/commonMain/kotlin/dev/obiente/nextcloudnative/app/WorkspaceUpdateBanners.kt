package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing

@Composable
internal fun SupportUpdateAvailableBanner(
    reports: List<SupportDiagnosticsSubmissionState.SubmittedReport>,
    enabled: Boolean,
    onReview: () -> Unit,
) {
    val messageCount = reports.sumOf(SupportDiagnosticsSubmissionState.SubmittedReport::unreadMaintainerMessages)
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Obiente Support updated your report", style = MaterialTheme.typography.titleSmall)
                Text(
                    when {
                        messageCount == 1 -> "You have one new private support message."
                        messageCount > 1 -> "You have $messageCount new private support messages."
                        reports.size == 1 -> "The status of your private support report changed."
                        else -> "The status of ${reports.size} private support reports changed."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = onReview, enabled = enabled) { Text("View support") }
        }
    }
}

@Composable
internal fun AppUpdateAvailableBanner(
    release: AppUpdateRelease,
    enabled: Boolean = true,
    onReview: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("nati.ve ${release.versionName} is available", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (release is AndroidDirectRelease) {
                        "Review the certificate-verified APK before installing."
                    } else {
                        "Review the downloaded package before opening the system installer."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = onReview, enabled = enabled) { Text("Review update") }
        }
    }
}
