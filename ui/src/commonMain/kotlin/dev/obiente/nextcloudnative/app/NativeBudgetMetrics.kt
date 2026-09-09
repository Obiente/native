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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
internal fun NativeBudgetMetricGrid(model: NativeBudgetDashboardModel) {
    val metrics = listOfNotNull(model.netWorth, model.income, model.expenses, model.savings, model.pensionWorth)
    if (metrics.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val compact = maxWidth < 560.dp
        val columns = when {
            maxWidth >= 1_000.dp -> 4
            maxWidth >= 560.dp -> 3
            else -> 2
        }
        Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
            val visibleMetrics = if (compact && !expanded) metrics.take(2) else metrics
            visibleMetrics.chunked(columns).forEach { rowMetrics ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                ) {
                    rowMetrics.forEach { metric ->
                        NativeBudgetMetricCard(
                            metric = metric,
                            currency = model.currency,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(columns - rowMetrics.size) { Box(modifier = Modifier.weight(1f)) }
                }
            }
            if (compact && metrics.size > 2) {
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Fewer totals" else "More totals") }
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
                metric.label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                formatNativeBudgetMoney(metric.value, currency),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            metric.supportingText?.let { supporting ->
                Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
