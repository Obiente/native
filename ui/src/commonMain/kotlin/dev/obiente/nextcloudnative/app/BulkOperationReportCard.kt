package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing

/**
 * Shows a partial-success result: how many items completed, and for each problem item what
 * happened and whether repeating it is safe. [onRetry] receives only retry-safe item keys.
 */
@Composable
internal fun BulkOperationReportCard(
    report: BulkOperationReport,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onRetry: ((Set<String>) -> Unit)? = null,
) {
    var expanded by remember(report) { mutableStateOf(false) }
    val problems = report.problems
    Surface(
        color = if (report.fullySucceeded) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.errorContainer
        },
        shape = RoundedCornerShape(NextcloudRadii.Small),
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = NextcloudSpacing.Large, vertical = NextcloudSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        ) {
            Text(report.summary(), style = MaterialTheme.typography.bodyMedium)
            if (expanded) {
                problems.take(MAX_VISIBLE_PROBLEMS).forEach { item ->
                    Column {
                        Text(
                            item.label,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            buildString {
                                append(item.outcome.label())
                                item.reason?.let { append(": ").append(it) }
                                if (item.retrySafe) append(" Safe to try again.")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (problems.size > MAX_VISIBLE_PROBLEMS) {
                    Text(
                        "${problems.size - MAX_VISIBLE_PROBLEMS} more not shown.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                if (problems.isNotEmpty()) {
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) "Hide details" else "Show details")
                    }
                }
                val retryable = report.retryableKeys
                if (onRetry != null && retryable.isNotEmpty()) {
                    TextButton(onClick = { onRetry(retryable) }) { Text("Try ${itemCount(retryable.size)} again") }
                }
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}

private fun BulkItemOutcome.label(): String = when (this) {
    BulkItemOutcome.Completed -> "Done"
    BulkItemOutcome.AlreadyDone -> "Already done"
    BulkItemOutcome.Failed -> "Failed"
    BulkItemOutcome.Unconfirmed -> "Not confirmed"
    BulkItemOutcome.Skipped -> "Not attempted"
}

private const val MAX_VISIBLE_PROBLEMS = 20
