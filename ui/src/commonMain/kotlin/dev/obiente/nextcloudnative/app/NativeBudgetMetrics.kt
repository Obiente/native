package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing

@Composable
internal fun NativeBudgetMetricGrid(model: NativeBudgetDashboardModel) {
    val metrics = listOfNotNull(model.netWorth, model.income, model.expenses, model.savings, model.pensionWorth)
    if (metrics.isEmpty()) return
    val primary = model.netWorth ?: metrics.first()
    val secondary = metrics.filterNot { it === primary }
    Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
        Card(Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            shape = RoundedCornerShape(NextcloudRadii.Card)) {
            Column(Modifier.padding(NextcloudSpacing.Large),
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                Text(primary.label, style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(formatNativeBudgetMoney(primary.value, model.currency),
                    style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                primary.supportingText?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer) }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
            val columns = when {
                maxWidth / fontScale >= 900.dp -> 4
                maxWidth / fontScale >= 520.dp -> 3
                maxWidth / fontScale >= 300.dp -> 2
                else -> 1
            }
            Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                secondary.chunked(columns).forEach { metricsRow ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                        metricsRow.forEach { metric ->
                            NativeBudgetMetricCard(metric, model.currency, Modifier.weight(1f))
                        }
                        repeat(columns - metricsRow.size) { Box(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun NativeBudgetMetricCard(
    metric: NativeBudgetMetric,
    currency: String?,
    modifier: Modifier = Modifier,
) {
    val accent = when (metric.tone) {
        NativeBudgetMetricTone.Neutral -> MaterialTheme.colorScheme.primary
        NativeBudgetMetricTone.Positive -> Color(0xFF3F8F50)
        NativeBudgetMetricTone.Negative -> MaterialTheme.colorScheme.error
    }
    Card(
        modifier = modifier.heightIn(min = 88.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(NextcloudRadii.Medium),
    ) {
        Column(
            modifier = Modifier.padding(NextcloudSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall),
        ) {
            Text(
                metric.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                formatNativeBudgetMoney(metric.value, currency),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = accent,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            metric.supportingText?.let { supporting ->
                Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
